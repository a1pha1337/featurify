package ru.a1pha1337.featurify.grpc

import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.grpc.proto.*
import ru.a1pha1337.featurify.service.BackendFeatureService
import ru.a1pha1337.featurify.service.DomainValidationException
import ru.a1pha1337.featurify.service.NotFoundException

@Component
class FeatureGrpcService(private val features: BackendFeatureService) : FeatureServiceGrpc.FeatureServiceImplBase() {
    override fun getBooleanFeature(request: GetFeatureRequest, observer: StreamObserver<BooleanFeatureResponse>) =
        respond(observer) {
            val feature = features.get(requireNamespace(), request.group, request.key)
            if (feature.type != FeatureType.BOOLEAN) throw Status.FAILED_PRECONDITION.withDescription("Feature is not BOOLEAN").asRuntimeException()
            BooleanFeatureResponse.newBuilder().setValue(feature.booleanValue!!).setVersion(feature.version ?: 0).build()
        }

    override fun getEnumFeature(request: GetFeatureRequest, observer: StreamObserver<EnumFeatureResponse>) =
        respond(observer) {
            val feature = features.get(requireNamespace(), request.group, request.key)
            if (feature.type != FeatureType.ENUM) throw Status.FAILED_PRECONDITION.withDescription("Feature is not ENUM").asRuntimeException()
            EnumFeatureResponse.newBuilder().setValue(feature.enumValue!!).setVersion(feature.version ?: 0).build()
        }

    private fun requireNamespace() = TokenAuthenticationInterceptor.NAMESPACE.get()
        ?: throw Status.UNAUTHENTICATED.asRuntimeException()

    private fun <T> respond(observer: StreamObserver<T>, action: () -> T) {
        try {
            observer.onNext(action())
            observer.onCompleted()
        } catch (exception: Exception) {
            val status = when (exception) {
                is io.grpc.StatusRuntimeException -> exception.status
                is NotFoundException -> Status.NOT_FOUND.withDescription("Feature or group was not found")
                is DomainValidationException -> Status.INVALID_ARGUMENT.withDescription(exception.message)
                else -> {
                    LoggerFactory.getLogger(javaClass).error("gRPC feature lookup failed", exception)
                    Status.INTERNAL.withDescription("Feature lookup failed")
                }
            }
            observer.onError(status.asRuntimeException())
        }
    }
}
