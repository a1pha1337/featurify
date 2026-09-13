package ru.a1pha1337.featurify.controller

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.transaction.annotation.Transactional
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.dto.CreateAccessTokenRequest
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreateNamespaceRequest
import ru.a1pha1337.featurify.dto.CreatedAccessTokenResponse
import ru.a1pha1337.featurify.dto.NamespaceResponse
import ru.a1pha1337.featurify.service.AccessTokenService
import ru.a1pha1337.featurify.service.FeatureToggleService
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@SpringBootTest(properties = ["spring.grpc.server.enabled=false", "vaadin.productionMode=true"])
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "FEATURIFY_DB_TESTS", matches = "true")
class FeatureSecurityDatabaseTests {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var features: FeatureToggleService

    @Autowired
    private lateinit var tokens: AccessTokenService

    @Autowired
    private lateinit var jdbc: JdbcTemplate
    private lateinit var blue: NamespaceResponse
    private lateinit var red: NamespaceResponse
    private lateinit var blueToken: CreatedAccessTokenResponse
    private lateinit var redToken: CreatedAccessTokenResponse

    @BeforeEach
    fun setup() {
        blue = features.createNamespace(CreateNamespaceRequest("blue-${UUID.randomUUID()}", "Blue"))
        red = features.createNamespace(CreateNamespaceRequest("red-${UUID.randomUUID()}", "Red"))
        features.createFeature(
            blue.key,
            CreateFeatureRequest(key = "enabled", type = FeatureType.BOOLEAN, booleanValue = false),
        )
        features.createFeature(
            red.key,
            CreateFeatureRequest(key = "enabled", type = FeatureType.BOOLEAN, booleanValue = true),
        )
        blueToken = tokens.create(blue.key, CreateAccessTokenRequest("backend"))
        redToken = tokens.create(red.key, CreateAccessTokenRequest("backend"))
    }

    @Test
    fun `all read endpoints require namespace token including HEAD and reject Keycloak alone`() {
        for (path in listOf("/api/v1/features", "/api/v1/features/enabled", "/api/v1/features:resolve?keys=enabled")) {
            mvc
                .perform(get(path))
                .andExpect { assertThat(it.response.status).isEqualTo(401) }
                .andExpect {
                    assertThat(
                        MediaType.parseMediaType(it.response.contentType!!).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
                    ).isTrue()
                }.andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/status").asInt()).isEqualTo(401) }
                .andExpect {
                    assertThat(
                        JsonMapper().readTree(it.response.contentAsString).at("/code").asString(),
                    ).isEqualTo("UNAUTHORIZED")
                }.andExpect {
                    assertThat(
                        JsonMapper().readTree(it.response.contentAsString).at("/type").asString(),
                    ).isEqualTo("urn:featurify:problem:unauthorized")
                }.andExpect { assertThat(it.response.getHeader("WWW-Authenticate")).isNotNull() }
            mvc.perform(get(path).with(jwt())).andExpect { assertThat(it.response.status).isEqualTo(401) }
            mvc.perform(get(path).with(oauth2Login())).andExpect { assertThat(it.response.status).isEqualTo(401) }
            mvc.perform(head(path)).andExpect { assertThat(it.response.status).isEqualTo(401) }
        }
    }

    @Test
    fun `read scope is always token namespace and query cannot select another namespace`() {
        mvc
            .perform(get("/api/v1/features").header("Authorization", "Bearer ${blueToken.token}"))
            .andExpect { assertThat(it.response.status).isEqualTo(200) }
            .andExpect {
                assertThat(
                    JsonMapper().readTree(it.response.contentAsString).at("/content/0/value").asBoolean(),
                ).isEqualTo(false)
            }
        mvc
            .perform(get("/api/v1/features/enabled").header("Authorization", "Bearer ${redToken.token}"))
            .andExpect { assertThat(it.response.status).isEqualTo(200) }
            .andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/value").asBoolean()).isEqualTo(true) }
        mvc
            .perform(
                get("/api/v1/features:resolve")
                    .param("keys", "enabled")
                    .param("namespace", blue.key)
                    .header("Authorization", "Bearer ${blueToken.token}"),
            ).andExpect { assertThat(it.response.status).isEqualTo(200) }
            .andExpect {
                assertThat(
                    JsonMapper().readTree(it.response.contentAsString).at("/features/enabled/value").asBoolean(),
                ).isEqualTo(false)
            }
        for (path in listOf("/api/v1/features", "/api/v1/features/enabled", "/api/v1/features:resolve?keys=enabled")) {
            mvc
                .perform(get(path).param("namespace", red.key).header("Authorization", "Bearer ${blueToken.token}"))
                .andExpect { assertThat(it.response.status).isEqualTo(403) }
                .andExpect {
                    assertThat(
                        MediaType.parseMediaType(it.response.contentType!!).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
                    ).isTrue()
                }.andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/code").asString()).isEqualTo("FORBIDDEN") }
        }
    }

    @Test
    fun `revoked tokens and inactive or deleted namespaces are rejected immediately`() {
        tokens.revoke(blue.key, blueToken.id)
        mvc
            .perform(get("/api/v1/features").header("Authorization", "Bearer ${blueToken.token}"))
            .andExpect { assertThat(it.response.status).isEqualTo(401) }
        jdbc.update("UPDATE namespace SET active = false WHERE id = ?", red.id)
        mvc
            .perform(get("/api/v1/features").header("Authorization", "Bearer ${redToken.token}"))
            .andExpect { assertThat(it.response.status).isEqualTo(401) }
        features.deleteNamespace(red.key)
        mvc
            .perform(get("/api/v1/features").header("Authorization", "Bearer ${redToken.token}"))
            .andExpect { assertThat(it.response.status).isEqualTo(401) }
    }

    @Test
    fun `malformed duplicated and oversized credentials are rejected`() {
        for (value in listOf("garbage", "Bearer bad", "Bearer " + "a".repeat(101))) {
            mvc.perform(get("/api/v1/features").header("Authorization", value)).andExpect { assertThat(it.response.status).isEqualTo(401) }
        }
        mvc
            .perform(
                get("/api/v1/features").header(
                    "Authorization",
                    "Bearer ${blueToken.token}",
                    "Bearer ${redToken.token}",
                ),
            ).andExpect { assertThat(it.response.status).isEqualTo(401) }
    }

    @Test
    fun `all admin routes reject namespace tokens and anonymous requests`() {
        val requests =
            listOf(
                post("/api/v1/features"),
                patch("/api/v1/features/enabled"),
                delete("/api/v1/features/enabled"),
                patch("/api/v1/features/enabled/group"),
                get("/api/v1/features/enabled/history"),
                post("/api/v1/namespaces"),
                get("/api/v1/namespaces"),
                delete("/api/v1/namespaces/${blue.key}"),
                post("/api/v1/groups"),
                get("/api/v1/groups"),
                delete("/api/v1/groups/checkout"),
                post("/api/v1/namespaces/${blue.key}/tokens"),
                get("/api/v1/namespaces/${blue.key}/tokens"),
                delete("/api/v1/namespaces/${blue.key}/tokens/${blueToken.id}"),
            )
        for (request in requests) {
            mvc
                .perform(request)
                .andExpect { assertThat(it.response.status).isEqualTo(401) }
                .andExpect {
                    assertThat(
                        MediaType.parseMediaType(it.response.contentType!!).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
                    ).isTrue()
                }
            mvc
                .perform(request.header("Authorization", "Bearer ${blueToken.token}"))
                .andExpect { assertThat(it.response.status).isEqualTo(401) }
                .andExpect {
                    assertThat(
                        JsonMapper().readTree(it.response.contentAsString).at("/code").asString(),
                    ).isEqualTo("UNAUTHORIZED")
                }
        }
    }

    @Test
    fun `Keycloak authentication permits administration and OAuth2 login session still works`() {
        mvc
            .perform(
                post("/api/v1/features")
                    .param("namespace", blue.key)
                    .with(jwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"key":"newFlag","type":"BOOLEAN","booleanValue":true}"""),
            ).andExpect { assertThat(it.response.status).isEqualTo(201) }
        mvc
            .perform(get("/api/v1/features/enabled/history").param("namespace", blue.key).with(jwt()))
            .andExpect { assertThat(it.response.status).isEqualTo(200) }
        mvc
            .perform(get("/api/v1/namespaces/${blue.key}/tokens").with(oauth2Login()))
            .andExpect { assertThat(it.response.status).isEqualTo(200) }
    }

    @Test
    fun `REST errors use problem details after namespace authentication`() {
        mvc
            .perform(get("/api/v1/features/missing").header("Authorization", "Bearer ${blueToken.token}"))
            .andExpect { assertThat(it.response.status).isEqualTo(404) }
            .andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/title").asString()).isEqualTo("Not Found") }
            .andExpect {
                assertThat(
                    JsonMapper().readTree(it.response.contentAsString).at("/instance").asString(),
                ).isEqualTo("/api/v1/features/missing")
            }.andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/status").asInt()).isEqualTo(404) }
            .andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/code").asString()).isEqualTo("NOT_FOUND") }
        mvc
            .perform(get("/api/v1/features:resolve").header("Authorization", "Bearer ${blueToken.token}"))
            .andExpect { assertThat(it.response.status).isEqualTo(400) }
            .andExpect {
                assertThat(
                    JsonMapper().readTree(it.response.contentAsString).at("/details/0/field").asString(),
                ).isEqualTo("keys")
            }
        mvc
            .perform(get("/api/v1/features").param("query", "a").header("Authorization", "Bearer ${blueToken.token}"))
            .andExpect { assertThat(it.response.status).isEqualTo(400) }
            .andExpect {
                assertThat(
                    JsonMapper().readTree(it.response.contentAsString).at("/details/0/field").asString(),
                ).isEqualTo("query")
            }
        mvc
            .perform(get("/api/v1/unknown").with(jwt()))
            .andExpect { assertThat(it.response.status).isEqualTo(404) }
            .andExpect {
                assertThat(
                    MediaType.parseMediaType(it.response.contentType!!).isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
                ).isTrue()
            }.andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/code").asString()).isEqualTo("NOT_FOUND") }
        mvc
            .perform(put("/api/v1/features").with(jwt()))
            .andExpect { assertThat(it.response.status).isEqualTo(405) }
            .andExpect {
                assertThat(
                    JsonMapper().readTree(it.response.contentAsString).at("/code").asString(),
                ).isEqualTo("METHOD_NOT_ALLOWED")
            }
    }

    @Test
    fun `vector REST reads isolate namespace and expose independent element states`() {
        features.createFeature(
            blue.key,
            CreateFeatureRequest(
                "feat",
                FeatureType.VECTOR,
                vectorValues =
                    mapOf(
                        "DOG" to true,
                        "SHIP" to false,
                    ),
            ),
        )
        features.createFeature(red.key, CreateFeatureRequest("feat", FeatureType.VECTOR, vectorValues = mapOf("DOG" to false)))
        val path = "/api/v1/features/feat/elements"
        mvc.perform(get("$path/DOG")).andExpect { assertThat(it.response.status).isEqualTo(401) }
        mvc.perform(head("$path/DOG")).andExpect { assertThat(it.response.status).isEqualTo(401) }
        mvc.perform(get("$path/DOG").with(jwt())).andExpect { assertThat(it.response.status).isEqualTo(401) }
        mvc
            .perform(get("$path/DOG").header("Authorization", "Bearer ${blueToken.token}"))
            .andExpect {
                assertThat(it.response.status).isEqualTo(200)
                val body = JsonMapper().readTree(it.response.contentAsString)
                assertThat(body.path("key").asString()).isEqualTo("feat")
                assertThat(body.path("element").asString()).isEqualTo("DOG")
                assertThat(body.path("value").asBoolean()).isTrue()
            }
        for ((token, element) in listOf(blueToken.token to "SHIP", redToken.token to "DOG")) {
            mvc
                .perform(get("$path/$element").header("Authorization", "Bearer $token"))
                .andExpect {
                    assertThat(it.response.status).isEqualTo(200)
                    assertThat(JsonMapper().readTree(it.response.contentAsString).path("value").asBoolean()).isFalse()
                }
        }
        mvc
            .perform(get("$path/dog").header("Authorization", "Bearer ${blueToken.token}"))
            .andExpect { assertThat(it.response.status).isEqualTo(404) }
        mvc
            .perform(get("$path/DOG").param("namespace", red.key).header("Authorization", "Bearer ${blueToken.token}"))
            .andExpect { assertThat(it.response.status).isEqualTo(403) }
    }

    @Test
    fun `admin REST creates and partially patches vector values and rejects null states`() {
        val url = "/api/v1/features"
        val response =
            mvc
                .perform(
                    post(url)
                        .param("namespace", blue.key)
                        .with(jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"key":"feat","type":"VECTOR","vectorValues":{"CAT":true,"DOG":false,"SHIP":false}}"""),
                ).andExpect { assertThat(it.response.status).isEqualTo(201) }
                .andReturn()
                .response
        val version = JsonMapper().readTree(response.contentAsString).path("version").asLong()
        mvc
            .perform(
                patch("$url/feat")
                    .param("namespace", blue.key)
                    .with(jwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"version":$version,"vectorValues":{"DOG":true}}"""),
            ).andExpect {
                assertThat(it.response.status).isEqualTo(200)
                val values = JsonMapper().readTree(it.response.contentAsString).path("value")
                assertThat(values.path("CAT").asBoolean()).isTrue()
                assertThat(values.path("DOG").asBoolean()).isTrue()
                assertThat(values.path("SHIP").asBoolean()).isFalse()
            }
        mvc
            .perform(
                post(url)
                    .param("namespace", blue.key)
                    .with(jwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"key":"invalid","type":"VECTOR","vectorValues":{"DOG":null}}"""),
            ).andExpect { assertThat(it.response.status).isEqualTo(400) }
    }
}
