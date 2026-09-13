package ru.a1pha1337.featurify.controller

import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.TransientDataAccessException
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.ServletWebRequest
import org.springframework.web.context.request.WebRequest
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler
import ru.a1pha1337.featurify.dto.ValidationDetail
import ru.a1pha1337.featurify.service.ConflictException
import ru.a1pha1337.featurify.service.DomainValidationException
import ru.a1pha1337.featurify.service.NotFoundException

@RestControllerAdvice
class ApiExceptionHandler : ResponseEntityExceptionHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(NotFoundException::class)
    fun notFound(
        ex: NotFoundException,
        request: HttpServletRequest,
    ) = problem(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.message ?: "Resource was not found", request.requestURI)

    @ExceptionHandler(ConflictException::class)
    fun conflict(
        ex: ConflictException,
        request: HttpServletRequest,
    ) = problem(HttpStatus.CONFLICT, "CONFLICT", ex.message ?: "Resource conflict", request.requestURI)

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(
        ex: DataIntegrityViolationException,
        request: HttpServletRequest,
    ) = problem(
        HttpStatus.CONFLICT,
        "CONFLICT",
        "The operation conflicts with the current resource state",
        request.requestURI,
    )

    @ExceptionHandler(DomainValidationException::class)
    fun domainValidation(
        ex: DomainValidationException,
        request: HttpServletRequest,
    ) = problem(
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        ex.message ?: "Request validation failed",
        request.requestURI,
        ex.violations.map { ValidationDetail(it.first, it.second) },
    )

    @ExceptionHandler(AccessDeniedException::class)
    fun forbidden(
        ex: AccessDeniedException,
        request: HttpServletRequest,
    ) = problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access to the requested resource is denied", request.requestURI)

    @ExceptionHandler(DataAccessResourceFailureException::class, TransientDataAccessException::class)
    fun unavailable(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        log.error("API storage temporarily unavailable", ex)
        return problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "SERVICE_UNAVAILABLE",
            "Service temporarily unavailable",
            request.requestURI,
        )
    }

    // MVC's 400/404/405/406/415 responses retain protocol headers such as Allow.
    override fun handleExceptionInternal(
        ex: Exception,
        body: Any?,
        headers: HttpHeaders,
        statusCode: HttpStatusCode,
        request: WebRequest,
    ): ResponseEntity<Any>? {
        if ((request as ServletWebRequest).response?.isCommitted == true) return null
        val details =
            when (ex) {
                is MethodArgumentNotValidException ->
                    ex.bindingResult.allErrors.map {
                        ValidationDetail((it as? FieldError)?.field ?: it.objectName, it.defaultMessage ?: "invalid")
                    }

                is MissingServletRequestParameterException ->
                    listOf(
                        ValidationDetail(
                            ex.parameterName,
                            "must be provided",
                        ),
                    )

                else -> emptyList()
            }
        val (code, detail) =
            when {
                ex is HttpMessageNotReadableException -> "MALFORMED_JSON" to "Request body is missing or malformed"
                statusCode.value() == 400 -> "VALIDATION_ERROR" to "Request validation failed"
                statusCode.value() == 404 -> "NOT_FOUND" to "Resource was not found"
                statusCode.value() == 405 -> "METHOD_NOT_ALLOWED" to "HTTP method is not supported for this resource"
                statusCode.value() == 406 -> "NOT_ACCEPTABLE" to "Requested response media type is not supported"
                statusCode.value() == 415 -> "UNSUPPORTED_MEDIA_TYPE" to "Request media type is not supported"
                statusCode.is5xxServerError -> "INTERNAL_ERROR" to "An unexpected error occurred"
                else -> "REQUEST_ERROR" to "Request could not be processed"
            }
        return problem(statusCode, code, detail, request.request.requestURI, details, headers)
    }

    @ExceptionHandler(Exception::class)
    fun unexpected(
        ex: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<Any> {
        log.error("Unhandled API error", ex)
        return problem(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "INTERNAL_ERROR",
            "An unexpected error occurred",
            request.requestURI,
        )
    }

    private fun problem(
        status: HttpStatusCode,
        code: String,
        detail: String,
        path: String,
        details: List<ValidationDetail> = emptyList(),
        headers: HttpHeaders = HttpHeaders(),
    ): ResponseEntity<Any> =
        ResponseEntity
            .status(status)
            .headers(headers)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(ApiProblems.create(status, code, detail, path, details))
}
