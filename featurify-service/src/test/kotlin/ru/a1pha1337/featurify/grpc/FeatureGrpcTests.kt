package ru.a1pha1337.featurify.grpc

import com.google.rpc.BadRequest
import com.google.rpc.ErrorInfo
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.protobuf.StatusProto
import io.grpc.stub.MetadataUtils
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import ru.a1pha1337.featurify.domain.BooleanValue
import ru.a1pha1337.featurify.domain.EnumValue
import ru.a1pha1337.featurify.domain.Feature
import ru.a1pha1337.featurify.domain.FeatureGroup
import ru.a1pha1337.featurify.domain.Namespace
import ru.a1pha1337.featurify.domain.NamespaceAccessToken
import ru.a1pha1337.featurify.domain.VectorValue
import ru.a1pha1337.featurify.dto.CreateAccessTokenRequest
import ru.a1pha1337.featurify.grpc.proto.FeatureServiceGrpc
import ru.a1pha1337.featurify.grpc.proto.GetFeatureRequest
import ru.a1pha1337.featurify.grpc.proto.GetVectorFeatureRequest
import ru.a1pha1337.featurify.repository.FeatureGroupRepository
import ru.a1pha1337.featurify.repository.FeatureRepository
import ru.a1pha1337.featurify.repository.NamespaceAccessTokenRepository
import ru.a1pha1337.featurify.repository.NamespaceRepository
import ru.a1pha1337.featurify.service.AccessTokenService
import ru.a1pha1337.featurify.service.BackendFeatureService
import ru.a1pha1337.featurify.service.NotFoundException
import java.time.Clock
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.TimeUnit

class FeatureGrpcTests {
    private val now = Instant.now()
    private val namespaces = mockk<NamespaceRepository>(relaxed = true)
    private val tokenRepository = mockk<NamespaceAccessTokenRepository>(relaxed = true)
    private val groups = mockk<FeatureGroupRepository>(relaxed = true)
    private val features = mockk<FeatureRepository>(relaxed = true)
    private val tokenRows = mutableMapOf<UUID, NamespaceAccessToken>()
    private val blue = Namespace(UUID.randomUUID(), "blue", "Blue", true, now, now)
    private val red = blue.copy(id = UUID.randomUUID(), key = "red")
    private val blueGroup =
        FeatureGroup(UUID.randomUUID(), blue.id!!, "checkout", "Checkout", createdAt = now, updatedAt = now)
    private val redGroup = blueGroup.copy(id = UUID.randomUUID(), namespaceId = red.id!!)
    private lateinit var tokenService: AccessTokenService
    private lateinit var channel: ManagedChannel
    private lateinit var server: Server
    private lateinit var blueToken: String
    private lateinit var redToken: String

    @BeforeEach
    fun start() {
        every { namespaces.findByKey(any()) } returns null
        every { groups.findByNamespaceIdAndKey(any(), any()) } returns null
        every { features.findByNamespaceIdAndGroupIdIsNullAndKey(any(), any()) } returns null
        every { features.findByNamespaceIdAndGroupIdAndKey(any(), any(), any()) } returns null
        every { tokenRepository.findByIdAndNamespaceId(any(), any()) } returns null
        every { namespaces.findByKey("blue") } returns blue
        every { namespaces.findByKey("red") } returns red
        every { namespaces.findById(blue.id!!) } returns Optional.of(blue)
        every { namespaces.findById(red.id!!) } returns Optional.of(red)
        every { tokenRepository.save(any<NamespaceAccessToken>()) } answers {
            val saved = arg<NamespaceAccessToken>(0).copy(id = UUID.randomUUID())
            tokenRows[saved.id!!] = saved
            saved
        }
        every { tokenRepository.findById(any<UUID>()) } answers {
            Optional.ofNullable(tokenRows[arg<UUID>(0)])
        }
        every { groups.findByNamespaceIdAndKey(blue.id!!, "checkout") } returns blueGroup
        every { groups.findByNamespaceIdAndKey(red.id!!, "checkout") } returns redGroup
        val flag =
            Feature(
                UUID.randomUUID(),
                blue.id!!,
                "enabled",
                BooleanValue(false),
                groupId = blueGroup.id,
                version = 7,
                createdAt = now,
                updatedAt = now,
            )
        every { features.findByNamespaceIdAndGroupIdAndKey(blue.id!!, blueGroup.id!!, "enabled") } returns flag
        every { features.findByNamespaceIdAndGroupIdAndKey(red.id!!, redGroup.id!!, "enabled") } returns
            flag.copy(namespaceId = red.id!!, groupId = redGroup.id, value = BooleanValue(true))
        every { features.findByNamespaceIdAndGroupIdIsNullAndKey(blue.id!!, "color") } returns
            flag.copy(
                key = "color",
                groupId = null,
                value = EnumValue("GREEN", listOf("GREEN")),
            )
        tokenService = AccessTokenService(tokenRepository, namespaces, Clock.systemUTC())
        blueToken = tokenService.create("blue", CreateAccessTokenRequest("backend")).token
        redToken = tokenService.create("red", CreateAccessTokenRequest("backend")).token
        val name = InProcessServerBuilder.generateName()
        server =
            InProcessServerBuilder
                .forName(name)
                .directExecutor()
                .addService(
                    ServerInterceptors.intercept(
                        FeatureGrpcService(BackendFeatureService(features, groups)),
                        TokenAuthenticationInterceptor(tokenService),
                    ),
                ).build()
                .start()
        channel = InProcessChannelBuilder.forName(name).directExecutor().build()
    }

    @AfterEach
    fun stop() {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS)
    }

    private fun request(
        group: String = "checkout",
        key: String = "enabled",
    ) = GetFeatureRequest
        .newBuilder()
        .setGroup(group)
        .setKey(key)
        .build()

    private fun stub(
        token: String? = blueToken,
        additionalHeaders: Metadata = Metadata(),
    ): FeatureServiceGrpc.FeatureServiceBlockingStub {
        if (token != null) additionalHeaders.put(TokenAuthenticationInterceptor.AUTHORIZATION, "Bearer $token")
        return FeatureServiceGrpc
            .newBlockingStub(channel)
            .withDeadlineAfter(5, TimeUnit.SECONDS)
            .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(additionalHeaders))
    }

    private fun expect(
        code: Status.Code,
        action: () -> Unit,
    ) {
        assertThat(
            catchThrowableOfType(StatusRuntimeException::class.java) {
                action()
            }.also { assertThat(it).isNotNull() }.status.code,
        ).isEqualTo(code)
    }

    @Test
    fun `token selects namespace even for identical group and feature keys`() {
        assertThat(stub().getBooleanFeature(request()).value).isFalse()
        assertThat(stub().getBooleanFeature(request()).version).isEqualTo(7)
        assertThat(stub(redToken).getBooleanFeature(request()).value).isTrue()
        val spoofed = Metadata().apply { put(Metadata.Key.of("namespace", Metadata.ASCII_STRING_MARSHALLER), "red") }
        assertThat(stub(blueToken, spoofed).getBooleanFeature(request()).value).isFalse()
        assertThat(stub().getEnumFeature(request("", "color")).value).isEqualTo("GREEN")
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
        verify { features wasNot Called }
    }

    @Test
    fun `deleted tokens and inactive or deleted namespaces cannot authenticate`() {
        tokenRows.remove(UUID.fromString(blueToken.substringAfter("ft_").substringBefore('.')))
        expect(Status.Code.UNAUTHENTICATED) { stub().getBooleanFeature(request()) }
        every { namespaces.findById(red.id!!) } returns Optional.of(red.copy(active = false))
        expect(Status.Code.UNAUTHENTICATED) { stub(redToken).getBooleanFeature(request()) }
        every { namespaces.findById(red.id!!) } returns Optional.empty()
        expect(Status.Code.UNAUTHENTICATED) { stub(redToken).getBooleanFeature(request()) }
        verify { features wasNot Called }
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
        assertThat(extra.token).isNotEqualTo(blueToken)
        assertThat(tokenService.authenticate(extra.token)).isEqualTo(blue.id)
        assertThat(tokenService.authenticate(blueToken)).isEqualTo(blue.id)
        val row = tokenRows[extra.id]!!
        assertThat(row.tokenHash.length).isEqualTo(60)
        assertThat(row.tokenHash).isNotEqualTo(extra.token)
        assertThat(row.toString().contains(row.tokenHash)).isFalse()
        assertThat(extra.toString().contains(extra.token)).isFalse()
        assertThat(
            org.springframework.security.crypto.bcrypt
                .BCryptPasswordEncoder()
                .matches(extra.token.substringAfter('.'), row.tokenHash),
        ).isTrue()
        every { tokenRepository.findByIdAndNamespaceId(extra.id, blue.id!!) } returns row
        every { tokenRepository.delete(row) } answers {
            tokenRows.remove(arg<NamespaceAccessToken>(0).id)
            Unit
        }
        tokenService.revoke("blue", extra.id)
        assertThat(tokenService.authenticate(extra.token)).isNull()
        assertThat(tokenService.authenticate(blueToken)).isEqualTo(blue.id)
        catchThrowableOfType(NotFoundException::class.java) { tokenService.revoke("red", extra.id) }.also { assertThat(it).isNotNull() }
    }

    @Test
    fun `rich errors carry standard status error info and validation fields`() {
        val invalid =
            catchThrowableOfType(StatusRuntimeException::class.java) {
                stub().getBooleanFeature(request(key = ""))
            }.also { assertThat(it).isNotNull() }
        val detail = StatusProto.fromThrowable(invalid)!!
        assertThat(detail.code).isEqualTo(Status.Code.INVALID_ARGUMENT.value())
        val info = detail.detailsList.first { it.`is`(ErrorInfo::class.java) }.unpack(ErrorInfo::class.java)
        assertThat(info.domain).isEqualTo("featurify")
        assertThat(info.reason).isEqualTo("VALIDATION_ERROR")
        val fields = detail.detailsList.first { it.`is`(BadRequest::class.java) }.unpack(BadRequest::class.java)
        assertThat(fields.fieldViolationsList.single().field).isEqualTo("key")
        val unauthorized =
            catchThrowableOfType(StatusRuntimeException::class.java) {
                stub(null).getBooleanFeature(request())
            }.also { assertThat(it).isNotNull() }
        assertThat(
            StatusProto
                .fromThrowable(unauthorized)!!
                .detailsList
                .single()
                .unpack(ErrorInfo::class.java)
                .reason,
        ).isEqualTo("UNAUTHORIZED")
        val mismatch =
            catchThrowableOfType(StatusRuntimeException::class.java) {
                stub().getEnumFeature(request())
            }.also { assertThat(it).isNotNull() }
        assertThat(
            StatusProto
                .fromThrowable(mismatch)!!
                .detailsList
                .single()
                .unpack(ErrorInfo::class.java)
                .reason,
        ).isEqualTo("FEATURE_TYPE_MISMATCH")
    }

    @Test
    fun `authentication storage failure is unavailable with safe rich details`() {
        every { tokenRepository.findById(any<UUID>()) } throws org.springframework.dao.DataAccessResourceFailureException("private")
        val error =
            catchThrowableOfType(StatusRuntimeException::class.java) {
                stub().getBooleanFeature(request())
            }.also { assertThat(it).isNotNull() }
        assertThat(error.status.code).isEqualTo(Status.Code.UNAVAILABLE)
        assertThat(
            StatusProto
                .fromThrowable(error)!!
                .detailsList
                .single()
                .unpack(ErrorInfo::class.java)
                .reason,
        ).isEqualTo("SERVICE_UNAVAILABLE")
        assertThat(error.message!!.contains("private")).isFalse()
    }

    @Test
    fun `vector RPC resolves element within authenticated namespace and group`() {
        val vector =
            Feature(
                UUID.randomUUID(),
                blue.id!!,
                "feat",
                groupId = blueGroup.id,
                version = 4,
                createdAt = now,
                updatedAt = now,
                value =
                    VectorValue(
                        mapOf(
                            "CAT" to true,
                            "DOG" to true,
                            "SHIP" to false,
                        ),
                    ),
            )
        every { features.findByNamespaceIdAndGroupIdAndKey(blue.id!!, blueGroup.id!!, "feat") } returns vector
        every { features.findByNamespaceIdAndGroupIdAndKey(red.id!!, redGroup.id!!, "feat") } returns
            vector.copy(
                namespaceId = red.id!!,
                groupId = redGroup.id,
                value = VectorValue(mapOf("DOG" to false)),
            )

        fun vectorRequest(element: String) =
            GetVectorFeatureRequest
                .newBuilder()
                .setGroup("checkout")
                .setKey("feat")
                .setElement(element)
                .build()
        assertThat(stub().getVectorFeature(vectorRequest("DOG")).value).isTrue()
        assertThat(stub().getVectorFeature(vectorRequest("SHIP")).value).isFalse()
        assertThat(stub().getVectorFeature(vectorRequest("CAT")).version).isEqualTo(4)
        assertThat(stub(redToken).getVectorFeature(vectorRequest("DOG")).value).isFalse()
        expect(Status.Code.NOT_FOUND) { stub().getVectorFeature(vectorRequest("dog")) }
        expect(Status.Code.INVALID_ARGUMENT) { stub().getVectorFeature(vectorRequest("")) }
        expect(Status.Code.UNAUTHENTICATED) { stub(null).getVectorFeature(vectorRequest("DOG")) }
        expect(Status.Code.FAILED_PRECONDITION) { stub().getVectorFeature(vectorRequest("DOG").toBuilder().setKey("enabled").build()) }
        expect(Status.Code.FAILED_PRECONDITION) { stub().getBooleanFeature(request(key = "feat")) }
    }
}
