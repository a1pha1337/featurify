package ru.a1pha1337.featurify.grpc

import io.grpc.*
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.MetadataUtils
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*
import ru.a1pha1337.featurify.domain.*
import ru.a1pha1337.featurify.dto.CreateAccessTokenRequest
import ru.a1pha1337.featurify.grpc.proto.*
import ru.a1pha1337.featurify.repository.*
import ru.a1pha1337.featurify.service.*
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit

class FeatureGrpcTests {
    private val now = Instant.now()
    private val namespaces = mock(NamespaceRepository::class.java)
    private val tokenRepository = mock(NamespaceAccessTokenRepository::class.java)
    private val groups = mock(FeatureGroupRepository::class.java)
    private val features = mock(FeatureRepository::class.java)
    private val tokenRows = mutableMapOf<UUID, NamespaceAccessToken>()
    private val blue = Namespace(UUID.randomUUID(), "blue", "Blue", true, now, now)
    private val red = blue.copy(id = UUID.randomUUID(), key = "red")
    private val blueGroup = FeatureGroup(UUID.randomUUID(), blue.id!!, "checkout", "Checkout", createdAt = now, updatedAt = now)
    private val redGroup = blueGroup.copy(id = UUID.randomUUID(), namespaceId = red.id!!)
    private lateinit var tokenService: AccessTokenService
    private lateinit var channel: ManagedChannel
    private lateinit var server: Server
    private lateinit var blueToken: String
    private lateinit var redToken: String

    @BeforeEach
    fun start() {
        `when`(namespaces.findByKey("blue")).thenReturn(blue)
        `when`(namespaces.findByKey("red")).thenReturn(red)
        `when`(namespaces.findById(blue.id!!)).thenReturn(Optional.of(blue))
        `when`(namespaces.findById(red.id!!)).thenReturn(Optional.of(red))
        `when`(tokenRepository.save(any(NamespaceAccessToken::class.java))).thenAnswer {
            val saved = it.getArgument<NamespaceAccessToken>(0).copy(id = UUID.randomUUID())
            tokenRows[saved.id!!] = saved
            saved
        }
        `when`(tokenRepository.findById(any(UUID::class.java))).thenAnswer {
            Optional.ofNullable(tokenRows[it.getArgument<UUID>(0)])
        }
        `when`(groups.findByNamespaceIdAndKey(blue.id!!, "checkout")).thenReturn(blueGroup)
        `when`(groups.findByNamespaceIdAndKey(red.id!!, "checkout")).thenReturn(redGroup)
        val flag = Feature(UUID.randomUUID(), blue.id!!, "enabled", FeatureType.BOOLEAN,
            groupId = blueGroup.id, booleanValue = false, version = 7, createdAt = now, updatedAt = now)
        `when`(features.findByNamespaceIdAndGroupIdAndKey(blue.id!!, blueGroup.id!!, "enabled")).thenReturn(flag)
        `when`(features.findByNamespaceIdAndGroupIdAndKey(red.id!!, redGroup.id!!, "enabled"))
            .thenReturn(flag.copy(namespaceId = red.id!!, groupId = redGroup.id, booleanValue = true))
        `when`(features.findByNamespaceIdAndGroupIdIsNullAndKey(blue.id!!, "color"))
            .thenReturn(flag.copy(key = "color", groupId = null, type = FeatureType.ENUM, booleanValue = null, enumValue = "GREEN"))
        tokenService = AccessTokenService(tokenRepository, namespaces, Clock.systemUTC())
        blueToken = tokenService.create("blue", CreateAccessTokenRequest("backend")).token
        redToken = tokenService.create("red", CreateAccessTokenRequest("backend")).token
        val name = InProcessServerBuilder.generateName()
        server = InProcessServerBuilder.forName(name).directExecutor().addService(ServerInterceptors.intercept(
            FeatureGrpcService(BackendFeatureService(features, groups)), TokenAuthenticationInterceptor(tokenService),
        )).build().start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
    }

    @AfterEach
    fun stop() {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun request(group: String = "checkout", key: String = "enabled") =
        GetFeatureRequest.newBuilder().setGroup(group).setKey(key).build()

    private fun stub(token: String? = blueToken, additionalHeaders: Metadata = Metadata()): FeatureServiceGrpc.FeatureServiceBlockingStub {
        if (token != null) additionalHeaders.put(TokenAuthenticationInterceptor.AUTHORIZATION, "Bearer $token")
        return FeatureServiceGrpc.newBlockingStub(channel).withDeadlineAfter(5, TimeUnit.SECONDS)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(additionalHeaders))
    }

    private fun expect(code: Status.Code, action: () -> Unit) {
        assertEquals(code, assertThrows(StatusRuntimeException::class.java, action).status.code)
    }

    @Test
    fun `token selects namespace even for identical group and feature keys`() {
        assertFalse(stub().getBooleanFeature(request()).value)
        assertEquals(7, stub().getBooleanFeature(request()).version)
        assertTrue(stub(redToken).getBooleanFeature(request()).value)
        val spoofed = Metadata().apply { put(Metadata.Key.of("namespace", Metadata.ASCII_STRING_MARSHALLER), "red") }
        assertFalse(stub(blueToken, spoofed).getBooleanFeature(request()).value)
        assertEquals("GREEN", stub().getEnumFeature(request("", "color")).value)
        expect(Status.Code.NOT_FOUND) { stub(redToken).getEnumFeature(request("", "color")) }
    }

    @Test
    fun `missing malformed wrong and duplicated credentials are rejected before lookup`() {
        expect(Status.Code.UNAUTHENTICATED) { stub(null).getBooleanFeature(request()) }
        expect(Status.Code.UNAUTHENTICATED) { stub("garbage").getEnumFeature(request()) }
        val altered = blueToken.dropLast(1) + if (blueToken.last() == 'A') "B" else "A"
        expect(Status.Code.UNAUTHENTICATED) { stub(altered).getBooleanFeature(request()) }
        val duplicates = Metadata().apply { put(TokenAuthenticationInterceptor.AUTHORIZATION, "Bearer $redToken") }
        expect(Status.Code.UNAUTHENTICATED) { stub(blueToken, duplicates).getBooleanFeature(request()) }
        verifyNoInteractions(features)
    }

    @Test
    fun `deleted tokens and inactive or deleted namespaces cannot authenticate`() {
        tokenRows.remove(UUID.fromString(blueToken.substringAfter("ft_").substringBefore('.')))
        expect(Status.Code.UNAUTHENTICATED) { stub().getBooleanFeature(request()) }
        `when`(namespaces.findById(red.id!!)).thenReturn(Optional.of(red.copy(active = false)))
        expect(Status.Code.UNAUTHENTICATED) { stub(redToken).getBooleanFeature(request()) }
        `when`(namespaces.findById(red.id!!)).thenReturn(Optional.empty())
        expect(Status.Code.UNAUTHENTICATED) { stub(redToken).getBooleanFeature(request()) }
        verifyNoInteractions(features)
    }

    @Test
    fun `invalid input missing resources and wrong type have distinct errors`() {
        expect(Status.Code.INVALID_ARGUMENT) { stub().getBooleanFeature(request(key = "")) }
        expect(Status.Code.INVALID_ARGUMENT) { stub().getBooleanFeature(request(group = "Invalid Group")) }
        expect(Status.Code.NOT_FOUND) { stub().getBooleanFeature(request(group = "missing")) }
        expect(Status.Code.NOT_FOUND) { stub().getBooleanFeature(request(key = "missing")) }
        expect(Status.Code.FAILED_PRECONDITION) { stub().getEnumFeature(request()) }
        expect(Status.Code.FAILED_PRECONDITION) { stub().getBooleanFeature(request("", "color")) }
    }

    @Test
    fun `multiple namespace tokens are independent and only bcrypt hashes are stored`() {
        val extra = tokenService.create("blue", CreateAccessTokenRequest("second backend"))
        assertNotEquals(blueToken, extra.token)
        assertEquals(blue.id, tokenService.authenticate(extra.token))
        assertEquals(blue.id, tokenService.authenticate(blueToken))
        val row = tokenRows[extra.id]!!
        assertEquals(60, row.tokenHash.length)
        assertNotEquals(extra.token, row.tokenHash)
        assertFalse(row.toString().contains(row.tokenHash))
        assertFalse(extra.toString().contains(extra.token))
        assertTrue(org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().matches(extra.token.substringAfter('.'), row.tokenHash))
        `when`(tokenRepository.findByIdAndNamespaceId(extra.id, blue.id!!)).thenReturn(row)
        doAnswer { tokenRows.remove(it.getArgument<NamespaceAccessToken>(0).id); null }.`when`(tokenRepository).delete(row)
        tokenService.revoke("blue", extra.id)
        assertNull(tokenService.authenticate(extra.token))
        assertEquals(blue.id, tokenService.authenticate(blueToken))
        assertThrows(NotFoundException::class.java) { tokenService.revoke("red", extra.id) }
    }
}
