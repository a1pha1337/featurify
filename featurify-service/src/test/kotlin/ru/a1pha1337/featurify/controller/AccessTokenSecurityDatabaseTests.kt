package ru.a1pha1337.featurify.controller

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.annotation.Transactional
import ru.a1pha1337.featurify.dto.CreateNamespaceRequest
import ru.a1pha1337.featurify.service.FeatureToggleService
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

@SpringBootTest(properties = ["spring.grpc.server.enabled=false", "vaadin.productionMode=true"])
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "FEATURIFY_DB_TESTS", matches = "true")
class AccessTokenSecurityDatabaseTests {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var features: FeatureToggleService

    @Test
    fun `token administration requires Keycloak authentication`() {
        val path = "/api/v1/namespaces/default/tokens"
        mvc
            .perform(post(path).contentType(MediaType.APPLICATION_JSON).content("""{"name":"backend"}"""))
            .andExpect { assertThat(it.response.status).isEqualTo(401) }
        mvc.perform(get(path)).andExpect { assertThat(it.response.status).isEqualTo(401) }
        mvc.perform(delete("$path/${UUID.randomUUID()}")).andExpect { assertThat(it.response.status).isEqualTo(401) }
    }

    @Test
    fun `authenticated administrator can generate and list namespace tokens`() {
        val namespace = features.createNamespace(CreateNamespaceRequest("test-${UUID.randomUUID()}", "Token API test"))
        val path = "/api/v1/namespaces/${namespace.key}/tokens"
        mvc
            .perform(post(path).with(jwt()).contentType(MediaType.APPLICATION_JSON).content("""{"name":"backend"}"""))
            .andExpect { assertThat(it.response.status).isEqualTo(201) }
            .andExpect { assertThat(it.response.getHeader("Cache-Control")).isEqualTo("no-store") }
            .andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/token").isString).isTrue() }
            .andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/tokenHash").isMissingNode).isTrue() }
        mvc
            .perform(get(path).with(jwt()))
            .andExpect { assertThat(it.response.status).isEqualTo(200) }
            .andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/0/name").asString()).isEqualTo("backend") }
            .andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/0/token").isMissingNode).isTrue() }
    }
}
