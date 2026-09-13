package ru.a1pha1337.featurify.client

import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import ru.a1pha1337.featurify.grpc.proto.FeatureServiceGrpc
import ru.a1pha1337.featurify.grpc.proto.GetFeatureRequest
import ru.a1pha1337.featurify.grpc.proto.GetVectorFeatureRequest
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Owns the supplied channel; close this client when it is no longer needed. */
class GrpcFeaturifyClient(
    private val channel: ManagedChannel,
    token: String,
    timeout: Duration = Duration.ofSeconds(2),
) : FeaturifyClient,
    AutoCloseable {
    private val timeoutNanos: Long
    private val stub: FeatureServiceGrpc.FeatureServiceBlockingStub

    init {
        require(token.isNotBlank() && token.all { it in '!'..'~' }) { "Featurify token must be non-empty printable ASCII without spaces" }
        require(!timeout.isNegative && !timeout.isZero && timeout <= Duration.ofDays(1)) {
            "Featurify timeout must be positive and at most one day"
        }
        timeoutNanos = timeout.toNanos()
        val metadata = Metadata()
        metadata.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER), "Bearer $token")
        stub = FeatureServiceGrpc.newBlockingStub(channel).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
    }

    override fun getBooleanFeature(
        key: String,
        group: String?,
    ) = stub.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).getBooleanFeature(request(key, group))

    override fun getEnumFeature(
        key: String,
        group: String?,
    ) = stub.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).getEnumFeature(request(key, group))

    override fun getVectorFeature(
        key: String,
        element: String,
        group: String?,
    ) = stub.withDeadlineAfter(timeoutNanos, TimeUnit.NANOSECONDS).getVectorFeature(
        GetVectorFeatureRequest
            .newBuilder()
            .setKey(key)
            .setGroup(group.orEmpty())
            .setElement(element)
            .build(),
    )

    private fun request(
        key: String,
        group: String?,
    ) = GetFeatureRequest
        .newBuilder()
        .setKey(key)
        .setGroup(group.orEmpty())
        .build()

    override fun close() {
        channel.shutdown()
        try {
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                channel.shutdownNow()
            }
        } catch (_: InterruptedException) {
            channel.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
