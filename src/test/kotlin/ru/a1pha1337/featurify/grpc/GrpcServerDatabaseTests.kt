package ru.a1pha1337.featurify.grpc

import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import io.grpc.protobuf.StatusProto
import com.google.rpc.ErrorInfo
import com.google.rpc.BadRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort
import org.springframework.boot.test.context.SpringBootTest
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.dto.*
import ru.a1pha1337.featurify.grpc.proto.FeatureServiceGrpc
import ru.a1pha1337.featurify.grpc.proto.GetFeatureRequest
import ru.a1pha1337.featurify.service.AccessTokenService
import ru.a1pha1337.featurify.service.FeatureToggleService
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real Spring Boot server, TCP transport and committed PostgreSQL data visible to RPC threads. */
@SpringBootTest(properties = ["spring.grpc.server.port=0", "spring.grpc.server.address=127.0.0.1", "vaadin.productionMode=true"])
@EnabledIfEnvironmentVariable(named = "FEATURIFY_DB_TESTS", matches = "true")
open class GrpcServerDatabaseTests {
    @LocalGrpcServerPort protected var port: Int = 0
    @Autowired private lateinit var features: FeatureToggleService
    @Autowired private lateinit var tokens: AccessTokenService
    private lateinit var channel: ManagedChannel
    private val namespaceKeys = mutableListOf<String>()

    @BeforeEach
    fun connect() {
        channel = createChannel()
    }

    protected open fun createChannel(): ManagedChannel =
        NettyChannelBuilder.forAddress("localhost", port).usePlaintext().build()

    @AfterEach
    fun cleanup() {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        namespaceKeys.forEach(features::deleteNamespace)
    }

    private fun namespace(value: Boolean): Pair<String, CreatedAccessTokenResponse> {
        val key = "grpc-test-${UUID.randomUUID()}"
        features.createNamespace(CreateNamespaceRequest(key, "gRPC test"))
        namespaceKeys += key
        features.createFeature(key, CreateFeatureRequest(key = "enabled", type = FeatureType.BOOLEAN, booleanValue = value))
        return key to tokens.create(key, CreateAccessTokenRequest("backend"))
    }

    private fun stub(token: String? = null): FeatureServiceGrpc.FeatureServiceBlockingStub {
        val metadata = Metadata()
        if (token != null) metadata.put(TokenAuthenticationInterceptor.AUTHORIZATION, "Bearer $token")
        return FeatureServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(metadata))
    }

    private fun request(key: String = "enabled") = GetFeatureRequest.newBuilder().setKey(key).build()

    @Test
    fun `auto configured server enforces namespace tokens and immediate revocation`() {
        val (blue, blueToken) = namespace(false)
        val (_, redToken) = namespace(true)
        assertFalse(stub(blueToken.token).getBooleanFeature(request()).value)
        assertTrue(stub(redToken.token).getBooleanFeature(request()).value)
        assertEquals(Status.Code.UNAUTHENTICATED,
            assertThrows(StatusRuntimeException::class.java) { stub().getBooleanFeature(request()) }.status.code)
        tokens.revoke(blue, blueToken.id)
        assertEquals(Status.Code.UNAUTHENTICATED,
            assertThrows(StatusRuntimeException::class.java) { stub(blueToken.token).getBooleanFeature(request()) }.status.code)
        assertTrue(stub(redToken.token).getBooleanFeature(request()).value)
    }

    @Test
    fun `auto configured server preserves inbound message limit`() {
        val (_, token) = namespace(true)
        assertEquals(Status.Code.RESOURCE_EXHAUSTED,
            assertThrows(StatusRuntimeException::class.java) {
                stub(token.token).getBooleanFeature(request("a".repeat(17 * 1024)))
            }.status.code)
    }

    @Test
    fun `standard rich error details reach clients through Spring transport`() {
        val (_, token) = namespace(true)
        val invalid = assertThrows(StatusRuntimeException::class.java) { stub(token.token).getBooleanFeature(request("")) }
        val status = StatusProto.fromThrowable(invalid)!!
        assertEquals(Status.Code.INVALID_ARGUMENT.value(), status.code)
        assertEquals("VALIDATION_ERROR", status.detailsList.first { it.`is`(ErrorInfo::class.java) }.unpack(ErrorInfo::class.java).reason)
        assertEquals("key", status.detailsList.first { it.`is`(BadRequest::class.java) }.unpack(BadRequest::class.java).fieldViolationsList.single().field)
        val unauthorized = assertThrows(StatusRuntimeException::class.java) { stub().getBooleanFeature(request()) }
        assertEquals("UNAUTHORIZED", StatusProto.fromThrowable(unauthorized)!!.detailsList.single().unpack(ErrorInfo::class.java).reason)
    }
}
