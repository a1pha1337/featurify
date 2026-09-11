package ru.a1pha1337.featurify.controller

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.FieldError
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.a1pha1337.featurify.dto.ApiError
import ru.a1pha1337.featurify.dto.ValidationDetail
import ru.a1pha1337.featurify.service.ConflictException
import ru.a1pha1337.featurify.service.DomainValidationException
import ru.a1pha1337.featurify.service.NotFoundException

@RestControllerAdvice
class ApiExceptionHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(NotFoundException::class)
    fun notFound(exception: NotFoundException) = response(
        HttpStatus.NOT_FOUND,
        ApiError("NOT_FOUND", exception.message ?: "Resource was not found"),
    )

    @ExceptionHandler(ConflictException::class)
    fun conflict(exception: ConflictException) = response(
        HttpStatus.CONFLICT,
        ApiError("CONFLICT", exception.message ?: "Resource conflict"),
    )

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun integrity(exception: DataIntegrityViolationException) = response(
        HttpStatus.CONFLICT,
        ApiError("CONFLICT", "A resource with the same unique key already exists"),
    )

    @ExceptionHandler(DomainValidationException::class)
    fun domainValidation(exception: DomainValidationException) = response(
        HttpStatus.BAD_REQUEST,
        ApiError(
            "VALIDATION_ERROR",
            exception.message ?: "Request validation failed",
            exception.violations.map { ValidationDetail(it.first, it.second) },
        ),
    )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun beanValidation(exception: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val details = exception.bindingResult.allErrors.map {
            ValidationDetail((it as? FieldError)?.field ?: it.objectName, it.defaultMessage ?: "invalid")
        }
        return response(HttpStatus.BAD_REQUEST, ApiError("VALIDATION_ERROR", "Request validation failed", details))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformedBody(exception: HttpMessageNotReadableException) = response(
        HttpStatus.BAD_REQUEST,
        ApiError("MALFORMED_JSON", "Request body is missing or malformed"),
    )

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParameter(exception: MissingServletRequestParameterException) = response(
        HttpStatus.BAD_REQUEST,
        ApiError(
            "VALIDATION_ERROR",
            "Request validation failed",
            listOf(ValidationDetail(exception.parameterName, "must be provided")),
        ),
    )

    @ExceptionHandler(Exception::class)
    fun unexpected(exception: Exception): ResponseEntity<ApiError> {
        logger.error("Unhandled API error", exception)
        return response(
            HttpStatus.INTERNAL_SERVER_ERROR,
            ApiError("INTERNAL_ERROR", "An unexpected error occurred"),
        )
    }

    private fun response(status: HttpStatus, error: ApiError) = ResponseEntity.status(status).body(error)
}
