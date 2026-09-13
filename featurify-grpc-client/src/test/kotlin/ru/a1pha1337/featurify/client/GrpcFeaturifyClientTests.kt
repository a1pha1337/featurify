package ru.a1pha1337.featurify.client

import com.google.protobuf.Any
import com.google.rpc.BadRequest
import com.google.rpc.ErrorInfo
import io.grpc.Context
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.protobuf.StatusProto
import io.grpc.stub.StreamObserver
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.a1pha1337.featurify.grpc.proto.BooleanFeatureResponse
import ru.a1pha1337.featurify.grpc.proto.EnumFeatureResponse
import ru.a1pha1337.featurify.grpc.proto.FeatureServiceGrpc
import ru.a1pha1337.featurify.grpc.proto.GetFeatureRequest
import ru.a1pha1337.featurify.grpc.proto.GetVectorFeatureRequest
import java.time.Duration
import java.util.concurrent.TimeUnit

class GrpcFeaturifyClientTests {
    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var client: GrpcFeaturifyClient
    private val requests = mutableListOf<kotlin.Any>()
    private val authorization = mutableListOf<String?>()
    private val deadlines = mutableListOf<Long>()
    private val richError =
        com.google.rpc.Status
            .newBuilder()
            .setCode(Status.Code.INVALID_ARGUMENT.value())
            .setMessage("Request validation failed")
            .addDetails(
                Any.pack(
                    ErrorInfo
                        .newBuilder()
                        .setDomain("featurify")
                        .setReason("VALIDATION_ERROR")
                        .build(),
                ),
            ).addDetails(
                Any.pack(
                    BadRequest
                        .newBuilder()
                        .addFieldViolations(
                            BadRequest.FieldViolation
                                .newBuilder()
                                .setField("key")
                                .setDescription("invalid key"),
                        ).build(),
                ),
            ).build()

    @BeforeEach
    fun start() {
        val name = InProcessServerBuilder.generateName()
        server =
            InProcessServerBuilder
                .forName(name)
                .directExecutor()
                .intercept(
                    object : ServerInterceptor {
                        override fun <ReqT : kotlin.Any, RespT : kotlin.Any> interceptCall(
                            call: ServerCall<ReqT, RespT>,
                            headers: Metadata,
                            next: ServerCallHandler<ReqT, RespT>,
                        ): ServerCall.Listener<ReqT> {
                            authorization.add(headers.get(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)))
                            return next.startCall(call, headers)
                        }
                    },
                ).addService(
                    object : FeatureServiceGrpc.FeatureServiceImplBase() {
                        override fun getBooleanFeature(
                            request: GetFeatureRequest,
                            observer: StreamObserver<BooleanFeatureResponse>,
                        ) {
                            requests.add(request)
                            deadlines.add(Context.current().deadline!!.timeRemaining(TimeUnit.MILLISECONDS))
                            when (request.key) {
                                "invalid" -> observer.onError(StatusProto.toStatusRuntimeException(richError))
                                "unavailable" -> observer.onError(Status.UNAVAILABLE.asRuntimeException())
                                "slow" -> Unit // Leave the call open until its deadline expires.
                                else -> {
                                    observer.onNext(
                                        BooleanFeatureResponse
                                            .newBuilder()
                                            .setValue(false)
                                            .setVersion(7)
                                            .build(),
                                    )
                                    observer.onCompleted()
                                }
                            }
                        }

                        override fun getEnumFeature(
                            request: GetFeatureRequest,
                            observer: StreamObserver<EnumFeatureResponse>,
                        ) {
                            requests.add(request)
                            observer.onNext(
                                EnumFeatureResponse
                                    .newBuilder()
                                    .setValue("blue")
                                    .setVersion(8)
                                    .build(),
                            )
                            observer.onCompleted()
                        }

                        override fun getVectorFeature(
                            request: GetVectorFeatureRequest,
                            observer: StreamObserver<BooleanFeatureResponse>,
                        ) {
                            requests.add(request)
                            observer.onNext(
                                BooleanFeatureResponse
                                    .newBuilder()
                                    .setValue(true)
                                    .setVersion(9)
                                    .build(),
                            )
                            observer.onCompleted()
                        }
                    },
                ).build()
                .start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
        client = GrpcFeaturifyClient(channel, "test-token", Duration.ofMillis(250))
    }

    @AfterEach
    fun stop() {
        client.close()
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun `all feature types preserve values versions groups and authentication`() {
        val boolean = client.getBooleanFeature("enabled")
        assertThat(boolean.value).isFalse()
        assertThat(boolean.version).isEqualTo(7)
        val enum = client.getEnumFeature("color", "checkout")
        assertThat(enum.value).isEqualTo("blue")
        assertThat(enum.version).isEqualTo(8)
        val vector = client.getVectorFeature("animals", "DOG", "checkout")
        assertThat(vector.value).isTrue()
        assertThat(vector.version).isEqualTo(9)
        assertThat(requests).containsExactly(
            GetFeatureRequest.newBuilder().setKey("enabled").build(),
            GetFeatureRequest
                .newBuilder()
                .setKey("color")
                .setGroup("checkout")
                .build(),
            GetVectorFeatureRequest
                .newBuilder()
                .setKey("animals")
                .setElement("DOG")
                .setGroup("checkout")
                .build(),
        )
        assertThat(authorization).containsExactly("Bearer test-token", "Bearer test-token", "Bearer test-token")
    }

    @Test
    fun `rich errors and transport errors reach the caller unchanged`() {
        val invalid = catchThrowableOfType(StatusRuntimeException::class.java) { client.getBooleanFeature("invalid") }
        assertThat(invalid.status.code).isEqualTo(Status.Code.INVALID_ARGUMENT)
        assertThat(StatusProto.fromThrowable(invalid)).isEqualTo(richError)
        val unavailable = catchThrowableOfType(StatusRuntimeException::class.java) { client.getBooleanFeature("unavailable") }
        assertThat(unavailable.status.code).isEqualTo(Status.Code.UNAVAILABLE)
        assertThat(requests).hasSize(2)
    }

    @Test
    fun `deadline bounds a slow call and is renewed for the next call`() {
        val error = catchThrowableOfType(StatusRuntimeException::class.java) { client.getBooleanFeature("slow") }
        assertThat(error.status.code).isEqualTo(Status.Code.DEADLINE_EXCEEDED)
        assertThat(client.getBooleanFeature("enabled").version).isEqualTo(7)
        assertThat(deadlines).hasSize(2).allSatisfy { assertThat(it).isBetween(1L, 250L) }
    }

    @Test
    fun `closing the client terminates its channel`() {
        client.close()
        assertThat(channel.isShutdown).isTrue()
        assertThat(channel.isTerminated).isTrue()
    }
}
