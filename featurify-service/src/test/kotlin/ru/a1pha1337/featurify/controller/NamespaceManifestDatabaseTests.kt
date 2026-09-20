package ru.a1pha1337.featurify.controller

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.data.domain.Pageable
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.dto.ApplyNamespaceManifestRequest
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreateNamespaceRequest
import ru.a1pha1337.featurify.dto.DeletionPolicy
import ru.a1pha1337.featurify.dto.ManifestGroup
import ru.a1pha1337.featurify.dto.ManifestManagement
import ru.a1pha1337.featurify.dto.ManifestOwner
import ru.a1pha1337.featurify.dto.NamespaceManifest
import ru.a1pha1337.featurify.dto.OwnershipPolicy
import ru.a1pha1337.featurify.dto.PatchFeatureRequest
import ru.a1pha1337.featurify.dto.ReleaseNamespaceManifestRequest
import ru.a1pha1337.featurify.dto.ValuePolicy
import ru.a1pha1337.featurify.repository.ManifestRepository
import ru.a1pha1337.featurify.service.ConflictException
import ru.a1pha1337.featurify.service.FeatureToggleService
import ru.a1pha1337.featurify.service.NamespaceManifestService
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@SpringBootTest(properties = ["spring.grpc.server.enabled=false", "vaadin.productionMode=true"])
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "FEATURIFY_DB_TESTS", matches = "true")
class NamespaceManifestDatabaseTests {
    @Autowired private lateinit var service: NamespaceManifestService

    @Autowired private lateinit var manual: FeatureToggleService

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var bindings: ManifestRepository

    @Autowired private lateinit var tokens: ru.a1pha1337.featurify.service.AccessTokenService

    private val key = "operator-test-${UUID.randomUUID()}"
    private val owner = ManifestOwner("test-cluster", UUID.randomUUID().toString(), "applications", "checkout")
    private val boolean = CreateFeatureRequest("enabled", FeatureType.BOOLEAN, booleanValue = false)

    private fun request(management: ManifestManagement = ManifestManagement()): ApplyNamespaceManifestRequest =
        ApplyNamespaceManifestRequest(owner, 1, NamespaceManifest(key, "Checkout", management, listOf(boolean)))

    private fun values() = manual.listForAdmin(key, Pageable.unpaged(), null).content

    private fun release(
        policy: DeletionPolicy = DeletionPolicy.Retain,
        generation: Long = 2,
    ) = service.release(key, ReleaseNamespaceManifestRequest(owner, generation, policy), "operator-subject")

    @AfterEach
    fun cleanupOwnFixtures() {
        // Only UUID-qualified fixtures created by this test, never a shared namespace.
        jdbc.update(
            "delete from namespace_manifest_audit where binding_id in (select id from namespace_manifest where namespace_key = ?)",
            key,
        )
        jdbc.update("delete from namespace_manifest where namespace_key = ?", key)
        jdbc.update("delete from namespace where key = ?", key)
    }

    @Test
    fun `apply is idempotent and pruning is atomic with audit`() {
        val initial = request()
        assertThat(service.apply(key, initial, "operator-subject").changed).isTrue()
        val version = values().single().version
        assertThat(service.apply(key, initial, "operator-subject").changed).isFalse()
        assertThat(values().single().version).isEqualTo(version)
        assertThat(
            jdbc.queryForObject(
                "select count(*) from namespace_manifest_audit where binding_id = ?",
                Long::class.java,
                NamespaceManifestService.ownerId(owner),
            ),
        ).isEqualTo(1)
        val changed = initial.copy(generation = 2, spec = initial.spec.copy(features = listOf(boolean.copy(booleanValue = true))))
        service.apply(key, changed, "operator-subject")
        assertThat(manual.history(key, "enabled", null)).hasSize(1)
        service.apply(key, changed, "operator-subject")
        assertThat(manual.history(key, "enabled", null)).hasSize(1)
        service.apply(key, changed.copy(generation = 3, spec = changed.spec.copy(features = emptyList())), "operator-subject")
        assertThat(values()).isEmpty()
    }

    @Test
    fun `payload REST creates structured values replaces JSON and returns validation problems`() {
        manual.createNamespace(CreateNamespaceRequest(key, "Payload REST"))
        val definition =
            CreateFeatureRequest("config", FeatureType.PAYLOAD, payloadValue = """{"large":12345678901234567890,"nested":[true,null]}""")
        val created =
            mvc
                .perform(
                    post("/api/v1/features")
                        .param("namespace", key)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(definition)),
                ).andReturn()
                .response
        assertThat(created.status).isEqualTo(201)
        assertThat(created.contentAsString).contains("12345678901234567890")
        val parsed = mapper.readTree(created.contentAsString)
        assertThat(parsed.at("/value/nested/0").asBoolean()).isTrue()
        assertThat(parsed.at("/value/nested/1").isNull).isTrue()
        val token =
            tokens
                .create(
                    key,
                    ru.a1pha1337.featurify.dto
                        .CreateAccessTokenRequest("payload-reader"),
                ).token
        val read =
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/features/config")
                        .header("Authorization", "Bearer $token"),
                ).andReturn()
                .response
        assertThat(read.status).isEqualTo(200)
        assertThat(mapper.readTree(read.contentAsString).at("/value")).isEqualTo(parsed.at("/value"))
        val forbidden =
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/features/config")
                        .param("namespace", "default")
                        .header("Authorization", "Bearer $token"),
                ).andReturn()
                .response
        assertThat(forbidden.status).isEqualTo(403)
        val version = parsed.at("/version").asLong()
        val invalid =
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/features/config")
                        .param("namespace", key)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(PatchFeatureRequest(version, payloadValue = "{"))),
                ).andReturn()
                .response
        assertThat(invalid.status).isEqualTo(400)
        assertThat(invalid.contentType).startsWith("application/problem+json")
        assertThat(mapper.readTree(invalid.contentAsString).at("/details/0/field").asString()).isEqualTo("payloadValue")
        val updated =
            mvc
                .perform(
                    org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/features/config")
                        .param("namespace", key)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(PatchFeatureRequest(version, payloadValue = "null"))),
                ).andReturn()
                .response
        assertThat(updated.status).isEqualTo(200)
        assertThat(mapper.readTree(updated.contentAsString).at("/value").isNull).isTrue()
        assertThat(manual.history(key, "config", null)).hasSize(1)
    }

    @Test
    fun `payload reconciles atomically preserves initial only edits and audits replacements`() {
        val definition = CreateFeatureRequest("config", FeatureType.PAYLOAD, payloadValue = """{"b":2,"a":1}""")
        val initial = request().let { it.copy(spec = it.spec.copy(features = listOf(definition))) }
        service.apply(key, initial, "operator-subject")
        val version = values().single().version
        assertThat(values().single().value.toString()).isEqualTo("""{"a":1,"b":2}""")
        assertThatThrownBy { manual.patchFeature(key, "config", null, PatchFeatureRequest(version, payloadValue = "{}")) }
            .isInstanceOf(ConflictException::class.java)
        val reordered =
            initial.copy(
                generation = 2,
                spec = initial.spec.copy(features = listOf(definition.copy(payloadValue = """{ "a":1, "b":2 }"""))),
            )
        service.apply(key, reordered, "operator-subject")
        assertThat(values().single().version).isEqualTo(version)
        assertThat(manual.history(key, "config", null)).isEmpty()

        val runtime =
            reordered.copy(
                generation = 3,
                spec = reordered.spec.copy(management = ManifestManagement(valuePolicy = ValuePolicy.InitialOnly)),
            )
        service.apply(key, runtime, "operator-subject")
        val edited = manual.patchFeature(key, "config", null, PatchFeatureRequest(version, payloadValue = "[true,null]"))
        assertThat(service.apply(key, runtime, "operator-subject").changed).isFalse()
        assertThat(values().single().value.toString()).isEqualTo("[true,null]")
        assertThat(values().single().version).isEqualTo(edited.version)
        assertThat(manual.history(key, "config", null)).hasSize(1)

        val invalid =
            initial.copy(
                generation = 4,
                spec = initial.spec.copy(features = listOf(boolean, definition.copy(payloadValue = "{"))),
            )
        assertThatThrownBy { service.apply(key, invalid, "operator-subject") }
            .isInstanceOf(ru.a1pha1337.featurify.service.DomainValidationException::class.java)
        assertThat(values()).hasSize(1)
        assertThat(values().single().value.toString()).isEqualTo("[true,null]")
        service.apply(key, initial.copy(generation = 5), "operator-subject")
        assertThat(values().single().value.toString()).isEqualTo("""{"a":1,"b":2}""")
        assertThat(manual.history(key, "config", null)).hasSize(2)
        service.apply(key, initial.copy(generation = 6, spec = initial.spec.copy(features = emptyList())), "operator-subject")
        assertThat(values()).isEmpty()
        assertThat(
            jdbc.queryForObject(
                "select count(*) from feature_payload_value p join feature f on f.id = p.feature_id join namespace n on n.id = f.namespace_id where n.key = ?",
                Long::class.java,
                key,
            ),
        ).isZero()
    }

    @Test
    fun `exclusive protects writes through shared service and retain releases ownership`() {
        service.apply(key, request(), "operator-subject")
        val current = values().single()
        assertThatThrownBy { manual.createFeature(key, boolean.copy(key = "manual")) }.isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { manual.patchFeature(key, current.key, null, PatchFeatureRequest(current.version, booleanValue = true)) }
            .isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { manual.deleteFeature(key, current.key, null, current.version) }.isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { manual.deleteNamespace(key) }.isInstanceOf(ConflictException::class.java)
        release()
        assertThat(values().single().managed).isFalse()
        manual.patchFeature(key, current.key, null, PatchFeatureRequest(current.version, booleanValue = true))
        assertThatThrownBy { service.apply(key, request(), "operator-subject") }.isInstanceOf(ConflictException::class.java)
        assertThatThrownBy {
            service.apply(
                key,
                request().copy(generation = 3),
                "operator-subject",
            )
        }.isInstanceOf(ConflictException::class.java)
        release()
    }

    @Test
    fun `shared preserves manual resources and group cascade conflicts roll back all writes`() {
        val initial =
            request(ManifestManagement(ownershipPolicy = OwnershipPolicy.Shared))
                .let { it.copy(spec = it.spec.copy(groups = listOf(ManifestGroup("payments", "Payments")))) }
        service.apply(key, initial, "operator-subject")
        manual.createFeature(key, boolean.copy(key = "manual", group = "payments"))
        val update =
            initial.copy(
                generation = 2,
                spec = initial.spec.copy(displayName = "Must roll back", features = emptyList(), groups = emptyList()),
            )
        assertThatThrownBy { service.apply(key, update, "operator-subject") }.isInstanceOf(ConflictException::class.java)
        assertThat(values().map { it.key }).containsExactlyInAnyOrder("enabled", "manual")
        assertThat(manual.listNamespaces().single { it.key == key }.displayName).isEqualTo("Checkout")
        assertThatThrownBy { release(DeletionPolicy.Delete) }.isInstanceOf(ConflictException::class.java)
        assertThat(values()).hasSize(2)
        release()
        assertThat(values().all { !it.managed }).isTrue()
    }

    @Test
    fun `initial only schema changes retain runtime values and rollback invalid enum removal`() {
        val enum = CreateFeatureRequest("provider", FeatureType.ENUM, enumValue = "stripe", enumOptions = listOf("stripe", "paypal"))
        val vector = CreateFeatureRequest("methods", FeatureType.VECTOR, vectorValues = mapOf("CARD" to false, "CASH" to false))
        val initial =
            request(ManifestManagement(valuePolicy = ValuePolicy.InitialOnly))
                .let { it.copy(spec = it.spec.copy(features = listOf(boolean, enum, vector))) }
        service.apply(key, initial, "operator-subject")
        val byKey = values().associateBy { it.key }
        manual.patchFeature(key, "enabled", null, PatchFeatureRequest(byKey.getValue("enabled").version, booleanValue = true))
        manual.patchFeature(key, "provider", null, PatchFeatureRequest(byKey.getValue("provider").version, enumValue = "paypal"))
        manual.patchFeature(
            key,
            "methods",
            null,
            PatchFeatureRequest(byKey.getValue("methods").version, vectorValues = mapOf("CARD" to true)),
        )
        service.apply(key, initial, "operator-subject")
        assertThat(values().single { it.key == "enabled" }.value).isEqualTo(true)
        val newVector = vector.copy(vectorValues = mapOf("CARD" to false, "WIRE" to true))
        val invalid =
            initial.copy(
                generation = 2,
                spec = initial.spec.copy(features = listOf(newVector, enum.copy(enumOptions = listOf("stripe")))),
            )
        assertThatThrownBy { service.apply(key, invalid, "operator-subject") }.isInstanceOf(ConflictException::class.java)
        assertThat(values().single { it.key == "methods" }.value).isEqualTo(mapOf("CARD" to true, "CASH" to false))
        assertThat(values()).hasSize(3)
        service.apply(key, invalid.copy(spec = initial.spec.copy(features = listOf(newVector, enum))), "operator-subject")
        assertThat(values().single { it.key == "methods" }.value).isEqualTo(mapOf("CARD" to true, "WIRE" to true))
    }

    @Test
    fun `adoption is explicit and a second owner cannot take over`() {
        manual.createNamespace(CreateNamespaceRequest(key, "Existing"))
        manual.createFeature(key, boolean)
        assertThatThrownBy { service.apply(key, request(), "operator-subject") }.isInstanceOf(ConflictException::class.java)
        val adopted = request(ManifestManagement(adoptExisting = true))
        service.apply(key, adopted, "operator-subject")
        val another = adopted.copy(owner = owner.copy(uid = UUID.randomUUID().toString()))
        assertThatThrownBy { service.apply(key, another, "operator-subject") }.isInstanceOf(ConflictException::class.java)
        assertThatThrownBy {
            service.apply(
                key,
                adopted.copy(generation = 2),
                "another-subject",
            )
        }.isInstanceOf(ConflictException::class.java)
        release()
        service.apply(key, another, "operator-subject")
        assertThat(values().single().managed).isTrue()
    }

    @Test
    fun `delete policy removes exclusive namespace but shared leaves manual resources`() {
        val initial = request(ManifestManagement(ownershipPolicy = OwnershipPolicy.Shared))
        service.apply(key, initial, "operator-subject")
        manual.createFeature(key, boolean.copy(key = "manual"))
        release(DeletionPolicy.Delete)
        assertThat(values().map { it.key }).containsExactly("manual")
        val nextOwner = owner.copy(uid = UUID.randomUUID().toString())
        val adopted =
            initial.copy(
                owner = nextOwner,
                spec =
                    initial.spec.copy(
                        management = ManifestManagement(adoptExisting = true),
                        features = listOf(boolean.copy(key = "manual")),
                    ),
            )
        service.apply(key, adopted, "operator-subject")
        service.release(key, ReleaseNamespaceManifestRequest(nextOwner, 2, DeletionPolicy.Delete), "operator-subject")
        assertThat(manual.listNamespaces().none { it.key == key }).isTrue()
    }

    @Test
    fun `stale generations and changed spec at same generation are rejected`() {
        val initial = request().copy(generation = 5)
        service.apply(key, initial, "operator-subject")
        assertThatThrownBy {
            service.apply(
                key,
                initial.copy(generation = 4),
                "operator-subject",
            )
        }.isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { service.apply(key, initial.copy(spec = initial.spec.copy(features = emptyList())), "operator-subject") }
            .isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { release(generation = 4) }.isInstanceOf(ConflictException::class.java)
        assertThat(bindings.byId(NamespaceManifestService.ownerId(owner))?.generation).isEqualTo(5)
    }

    @Test
    fun `operator endpoint requires scope and accepts any namespace key`() {
        val body = mapper.writeValueAsString(request())

        fun call(scope: Boolean): Int =
            mvc
                .perform(
                    put("/api/v1/operator/namespaces/$key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .with(
                            jwt()
                                .jwt { it.subject("operator-subject") }
                                .authorities(SimpleGrantedAuthority(if (scope) "SCOPE_featurify.operator" else "SCOPE_openid")),
                        ),
                ).andReturn()
                .response.status
        assertThat(call(false)).isEqualTo(403)
        assertThat(call(true)).isEqualTo(200)
        assertThat(values()).hasSize(1)
        val ordinaryWrite =
            mvc
                .perform(
                    post("/api/v1/namespaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(CreateNamespaceRequest("forbidden", "Forbidden")))
                        .with(
                            jwt()
                                .authorities(SimpleGrantedAuthority("SCOPE_featurify.operator")),
                        ),
                ).andReturn()
                .response
        assertThat(ordinaryWrite.status).isEqualTo(403)
        assertThat(ordinaryWrite.contentType).startsWith("application/problem+json")
    }
}
