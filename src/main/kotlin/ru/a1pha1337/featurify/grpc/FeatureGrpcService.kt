package ru.a1pha1337.featurify.grpc

import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.dao.TransientDataAccessException
import org.springframework.grpc.server.service.GrpcService
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.grpc.proto.BooleanFeatureResponse
import ru.a1pha1337.featurify.grpc.proto.EnumFeatureResponse
import ru.a1pha1337.featurify.grpc.proto.FeatureServiceGrpc
import ru.a1pha1337.featurify.grpc.proto.GetFeatureRequest
import ru.a1pha1337.featurify.grpc.proto.GetVectorFeatureRequest
import ru.a1pha1337.featurify.service.BackendFeatureService
import ru.a1pha1337.featurify.service.DomainValidationException
import ru.a1pha1337.featurify.service.NotFoundException

@GrpcService(interceptors = [TokenAuthenticationInterceptor::class])
class FeatureGrpcService(
    private val features: BackendFeatureService,
) : FeatureServiceGrpc.FeatureServiceImplBase() {
    override fun getBooleanFeature(
        request: GetFeatureRequest,
        observer: StreamObserver<BooleanFeatureResponse>,
    ) = respond(observer) {
        val feature = features.get(requireNamespace(), request.group, request.key)
        if (feature.type != FeatureType.BOOLEAN) {
            throw GrpcErrors.exception(
                Status.FAILED_PRECONDITION,
                "FEATURE_TYPE_MISMATCH",
                "Feature is not BOOLEAN",
            )
        }
        BooleanFeatureResponse
            .newBuilder()
            .setValue(feature.booleanValue!!)
            .setVersion(feature.version ?: 0)
            .build()
    }

    override fun getEnumFeature(
        request: GetFeatureRequest,
        observer: StreamObserver<EnumFeatureResponse>,
    ) = respond(observer) {
        val feature = features.get(requireNamespace(), request.group, request.key)
        if (feature.type != FeatureType.ENUM) {
            throw GrpcErrors.exception(
                Status.FAILED_PRECONDITION,
                "FEATURE_TYPE_MISMATCH",
                "Feature is not ENUM",
            )
        }
        EnumFeatureResponse
            .newBuilder()
            .setValue(feature.enumValue!!)
            .setVersion(feature.version ?: 0)
            .build()
    }

    override fun getVectorFeature(
        request: GetVectorFeatureRequest,
        observer: StreamObserver<BooleanFeatureResponse>,
    ) = respond(observer) {
        if (request.element.isBlank() || request.element.length > 255) {
            throw DomainValidationException("Invalid element", listOf("element" to "must contain 1-255 characters"))
        }
        val feature = features.get(requireNamespace(), request.group, request.key)
        if (feature.type != FeatureType.VECTOR) {
            throw GrpcErrors.exception(Status.FAILED_PRECONDITION, "FEATURE_TYPE_MISMATCH", "Feature is not VECTOR")
        }
        val element = feature.vectorElements[request.element] ?: throw NotFoundException("Vector element was not found")
        BooleanFeatureResponse
            .newBuilder()
            .setValue(element.enabled)
            .setVersion(feature.version ?: 0)
            .build()
    }

    private fun requireNamespace() =
        TokenAuthenticationInterceptor.NAMESPACE.get()
            ?: throw GrpcErrors.exception(
                Status.UNAUTHENTICATED,
                "UNAUTHORIZED",
                "A valid namespace access token is required",
            )

    private fun <T> respond(
        observer: StreamObserver<T>,
        action: () -> T,
    ) {
        try {
            observer.onNext(action())
            observer.onCompleted()
        } catch (exception: Exception) {
            val error =
                when (exception) {
                    is io.grpc.StatusRuntimeException -> exception
                    is NotFoundException ->
                        GrpcErrors.exception(
                            Status.NOT_FOUND,
                            "NOT_FOUND",
                            "Feature or group was not found",
                        )

                    is DomainValidationException ->
                        GrpcErrors.exception(
                            Status.INVALID_ARGUMENT,
                            "VALIDATION_ERROR",
                            "Request validation failed",
                            exception.violations,
                        )

                    is DataAccessResourceFailureException, is TransientDataAccessException ->
                        GrpcErrors.exception(
                            Status.UNAVAILABLE,
                            "SERVICE_UNAVAILABLE",
                            "Service temporarily unavailable",
                        )

                    else -> {
                        LoggerFactory.getLogger(javaClass).error("gRPC feature lookup failed", exception)
                        GrpcErrors.exception(Status.INTERNAL, "INTERNAL_ERROR", "Feature lookup failed")
                    }
                }
            observer.onError(error)
        }
    }
}
