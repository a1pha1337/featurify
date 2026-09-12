package ru.a1pha1337.featurify.controller

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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.dto.CreateAccessTokenRequest
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreateNamespaceRequest
import ru.a1pha1337.featurify.dto.CreatedAccessTokenResponse
import ru.a1pha1337.featurify.dto.NamespaceResponse
import ru.a1pha1337.featurify.service.AccessTokenService
import ru.a1pha1337.featurify.service.FeatureToggleService
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
                .andExpect(status().isUnauthorized)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.type").value("urn:featurify:problem:unauthorized"))
                .andExpect(header().exists("WWW-Authenticate"))
            mvc.perform(get(path).with(jwt())).andExpect(status().isUnauthorized)
            mvc.perform(get(path).with(oauth2Login())).andExpect(status().isUnauthorized)
            mvc.perform(head(path)).andExpect(status().isUnauthorized)
        }
    }

    @Test
    fun `read scope is always token namespace and query cannot select another namespace`() {
        mvc
            .perform(get("/api/v1/features").header("Authorization", "Bearer ${blueToken.token}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].value").value(false))
        mvc
            .perform(get("/api/v1/features/enabled").header("Authorization", "Bearer ${redToken.token}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.value").value(true))
        mvc
            .perform(
                get("/api/v1/features:resolve")
                    .param("keys", "enabled")
                    .param("namespace", blue.key)
                    .header("Authorization", "Bearer ${blueToken.token}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.features.enabled.value").value(false))
        for (path in listOf("/api/v1/features", "/api/v1/features/enabled", "/api/v1/features:resolve?keys=enabled")) {
            mvc
                .perform(get(path).param("namespace", red.key).header("Authorization", "Bearer ${blueToken.token}"))
                .andExpect(status().isForbidden)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        }
    }

    @Test
    fun `revoked tokens and inactive or deleted namespaces are rejected immediately`() {
        tokens.revoke(blue.key, blueToken.id)
        mvc
            .perform(get("/api/v1/features").header("Authorization", "Bearer ${blueToken.token}"))
            .andExpect(status().isUnauthorized)
        jdbc.update("UPDATE namespace SET active = false WHERE id = ?", red.id)
        mvc
            .perform(get("/api/v1/features").header("Authorization", "Bearer ${redToken.token}"))
            .andExpect(status().isUnauthorized)
        features.deleteNamespace(red.key)
        mvc
            .perform(get("/api/v1/features").header("Authorization", "Bearer ${redToken.token}"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `malformed duplicated and oversized credentials are rejected`() {
        for (value in listOf("garbage", "Bearer bad", "Bearer " + "a".repeat(101))) {
            mvc.perform(get("/api/v1/features").header("Authorization", value)).andExpect(status().isUnauthorized)
        }
        mvc
            .perform(
                get("/api/v1/features").header(
                    "Authorization",
                    "Bearer ${blueToken.token}",
                    "Bearer ${redToken.token}",
                ),
            ).andExpect(status().isUnauthorized)
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
                .andExpect(status().isUnauthorized)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            mvc
                .perform(request.header("Authorization", "Bearer ${blueToken.token}"))
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
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
            ).andExpect(status().isCreated)
        mvc
            .perform(get("/api/v1/features/enabled/history").param("namespace", blue.key).with(jwt()))
            .andExpect(status().isOk)
        mvc
            .perform(get("/api/v1/namespaces/${blue.key}/tokens").with(oauth2Login()))
            .andExpect(status().isOk)
    }

    @Test
    fun `REST errors use problem details after namespace authentication`() {
        mvc
            .perform(get("/api/v1/features/missing").header("Authorization", "Bearer ${blueToken.token}"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.title").value("Not Found"))
            .andExpect(jsonPath("$.instance").value("/api/v1/features/missing"))
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        mvc
            .perform(get("/api/v1/features:resolve").header("Authorization", "Bearer ${blueToken.token}"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.details[0].field").value("keys"))
        mvc
            .perform(get("/api/v1/features").param("query", "a").header("Authorization", "Bearer ${blueToken.token}"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.details[0].field").value("query"))
        mvc
            .perform(get("/api/v1/unknown").with(jwt()))
            .andExpect(status().isNotFound)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        mvc
            .perform(put("/api/v1/features").with(jwt()))
            .andExpect(status().isMethodNotAllowed)
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
    }
}
