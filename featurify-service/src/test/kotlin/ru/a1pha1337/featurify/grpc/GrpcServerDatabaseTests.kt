package ru.a1pha1337.featurify.grpc

import com.google.rpc.BadRequest
import com.google.rpc.ErrorInfo
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.protobuf.StatusProto
import io.grpc.stub.MetadataUtils
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort
import org.springframework.boot.test.context.SpringBootTest
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.dto.CreateAccessTokenRequest
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreateNamespaceRequest
import ru.a1pha1337.featurify.dto.CreatedAccessTokenResponse
import ru.a1pha1337.featurify.dto.PatchFeatureRequest
import ru.a1pha1337.featurify.grpc.proto.FeatureServiceGrpc
import ru.a1pha1337.featurify.grpc.proto.GetFeatureRequest
import ru.a1pha1337.featurify.grpc.proto.GetVectorFeatureRequest
import ru.a1pha1337.featurify.service.AccessTokenService
import ru.a1pha1337.featurify.service.FeatureToggleService
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real Spring Boot server, TCP transport and committed PostgreSQL data visible to RPC threads. */
@SpringBootTest(properties = ["spring.grpc.server.port=0", "spring.grpc.server.address=127.0.0.1", "vaadin.productionMode=true"])
@EnabledIfEnvironmentVariable(named = "FEATURIFY_DB_TESTS", matches = "true")
open class GrpcServerDatabaseTests {
    @LocalGrpcServerPort
    protected var port: Int = 0

    @Autowired
    private lateinit var features: FeatureToggleService

    @Autowired
    private lateinit var tokens: AccessTokenService
    private lateinit var channel: ManagedChannel
    private val namespaceKeys = mutableListOf<String>()

    @BeforeEach
    fun connect() {
        channel = createChannel()
    }

    protected open fun createChannel(): ManagedChannel = NettyChannelBuilder.forAddress("localhost", port).usePlaintext().build()

    @AfterEach
    fun cleanup() {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        namespaceKeys.forEach(features::deleteNamespace)
    }

    private fun namespace(value: Boolean): Pair<String, CreatedAccessTokenResponse> {
        val key = "grpc-test-${UUID.randomUUID()}"
        features.createNamespace(CreateNamespaceRequest(key, "gRPC test"))
        namespaceKeys += key
        features.createFeature(
            key,
            CreateFeatureRequest(key = "enabled", type = FeatureType.BOOLEAN, booleanValue = value),
        )
        return key to tokens.create(key, CreateAccessTokenRequest("backend"))
    }

    private fun stub(token: String? = null): FeatureServiceGrpc.FeatureServiceBlockingStub {
        val metadata = Metadata()
        if (token != null) metadata.put(TokenAuthenticationInterceptor.AUTHORIZATION, "Bearer $token")
        return FeatureServiceGrpc
            .newBlockingStub(channel)
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
    }

    private fun request(key: String = "enabled") = GetFeatureRequest.newBuilder().setKey(key).build()

    @Test
    fun `auto configured server enforces namespace tokens and immediate revocation`() {
        val (blue, blueToken) = namespace(false)
        val (_, redToken) = namespace(true)
        assertThat(stub(blueToken.token).getBooleanFeature(request()).value).isFalse()
        assertThat(stub(redToken.token).getBooleanFeature(request()).value).isTrue()
        assertThat(
            catchThrowableOfType(StatusRuntimeException::class.java) {
                stub().getBooleanFeature(request())
            }.also { assertThat(it).isNotNull() }.status.code,
        ).isEqualTo(Status.Code.UNAUTHENTICATED)
        tokens.revoke(blue, blueToken.id)
        assertThat(
            catchThrowableOfType(StatusRuntimeException::class.java) {
                stub(blueToken.token).getBooleanFeature(request())
            }.also { assertThat(it).isNotNull() }.status.code,
        ).isEqualTo(Status.Code.UNAUTHENTICATED)
        assertThat(stub(redToken.token).getBooleanFeature(request()).value).isTrue()
    }

    @Test
    fun `auto configured server preserves inbound message limit`() {
        val (_, token) = namespace(true)
        assertThat(
            catchThrowableOfType(StatusRuntimeException::class.java) {
                stub(token.token).getBooleanFeature(request("a".repeat(17 * 1024)))
            }.also { assertThat(it).isNotNull() }.status.code,
        ).isEqualTo(Status.Code.RESOURCE_EXHAUSTED)
    }

    @Test
    fun `standard rich error details reach clients through Spring transport`() {
        val (_, token) = namespace(true)
        val invalid =
            catchThrowableOfType(StatusRuntimeException::class.java) {
                stub(token.token).getBooleanFeature(request(""))
            }.also { assertThat(it).isNotNull() }
        val status = StatusProto.fromThrowable(invalid)!!
        assertThat(status.code).isEqualTo(Status.Code.INVALID_ARGUMENT.value())
        assertThat(
            status.detailsList
                .first { it.`is`(ErrorInfo::class.java) }
                .unpack(ErrorInfo::class.java)
                .reason,
        ).isEqualTo("VALIDATION_ERROR")
        assertThat(
            status.detailsList
                .first {
                    it.`is`(BadRequest::class.java)
                }.unpack(BadRequest::class.java)
                .fieldViolationsList
                .single()
                .field,
        ).isEqualTo("key")
        val unauthorized =
            catchThrowableOfType(StatusRuntimeException::class.java) {
                stub().getBooleanFeature(request())
            }.also { assertThat(it).isNotNull() }
        assertThat(
            StatusProto
                .fromThrowable(unauthorized)!!
                .detailsList
                .single()
                .unpack(ErrorInfo::class.java)
                .reason,
        ).isEqualTo("UNAUTHORIZED")
    }

    @Test
    fun `vector RPC reads persisted changes over real transport`() {
        val (namespace, token) = namespace(false)
        val feature =
            features.createFeature(
                namespace,
                CreateFeatureRequest(
                    "feat",
                    FeatureType.VECTOR,
                    vectorValues = mapOf("CAT" to true, "DOG" to false, "SHIP" to false),
                ),
            )

        fun vectorRequest(element: String) =
            GetVectorFeatureRequest
                .newBuilder()
                .setKey("feat")
                .setElement(element)
                .build()
        assertThat(stub(token.token).getVectorFeature(vectorRequest("CAT")).value).isTrue()
        assertThat(stub(token.token).getVectorFeature(vectorRequest("DOG")).value).isFalse()
        val changed =
            features.patchFeature(
                namespace,
                "feat",
                null,
                PatchFeatureRequest(feature.version, vectorValues = mapOf("DOG" to true)),
            )
        assertThat(stub(token.token).getVectorFeature(vectorRequest("DOG")).value).isTrue()
        assertThat(stub(token.token).getVectorFeature(vectorRequest("SHIP")).value).isFalse()
        assertThat(stub(token.token).getVectorFeature(vectorRequest("DOG")).version).isEqualTo(changed.version)
    }
}
