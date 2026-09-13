package ru.a1pha1337.featurify.demo

import io.grpc.Status
import io.grpc.StatusRuntimeException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice
class GrpcExceptionHandler {
    @ExceptionHandler(StatusRuntimeException::class)
    fun grpcFailure(exception: StatusRuntimeException): ResponseEntity<ProblemDetail> {
        val code = exception.status.code
        val httpStatus =
            when (code) {
                Status.Code.INVALID_ARGUMENT, Status.Code.OUT_OF_RANGE -> HttpStatus.BAD_REQUEST
                Status.Code.NOT_FOUND -> HttpStatus.NOT_FOUND
                Status.Code.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED
                Status.Code.PERMISSION_DENIED -> HttpStatus.FORBIDDEN
                Status.Code.ALREADY_EXISTS, Status.Code.ABORTED, Status.Code.FAILED_PRECONDITION -> HttpStatus.CONFLICT
                Status.Code.RESOURCE_EXHAUSTED -> HttpStatus.TOO_MANY_REQUESTS
                Status.Code.DEADLINE_EXCEEDED -> HttpStatus.GATEWAY_TIMEOUT
                Status.Code.UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
                else -> HttpStatus.BAD_GATEWAY
            }
        val problem =
            ProblemDetail.forStatusAndDetail(httpStatus, exception.status.description ?: "Featurify RPC failed")
        problem.type = URI.create("urn:featurify:demo:grpc-error")
        problem.title = "Featurify gRPC error"
        problem.setProperty("grpcCode", code.name)
        return ResponseEntity.status(httpStatus).body(problem)
    }
}
