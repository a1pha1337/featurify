package ru.a1pha1337.featurify.controller

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.annotation.Transactional
import ru.a1pha1337.featurify.dto.CreateNamespaceRequest
import ru.a1pha1337.featurify.service.FeatureToggleService
import java.util.UUID

@SpringBootTest(properties = ["grpc.server.enabled=false", "vaadin.productionMode=true"])
@AutoConfigureMockMvc
@Transactional
@EnabledIfEnvironmentVariable(named = "FEATURIFY_DB_TESTS", matches = "true")
class AccessTokenSecurityDatabaseTests {
    @Autowired private lateinit var mvc: MockMvc
    @Autowired private lateinit var features: FeatureToggleService

    @Test
    fun `token administration requires Keycloak authentication`() {
        val path = "/api/v1/namespaces/default/tokens"
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content("""{"name":"backend"}"""))
            .andExpect(status().isUnauthorized)
        mvc.perform(get(path)).andExpect(status().isUnauthorized)
        mvc.perform(delete("$path/${UUID.randomUUID()}")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `authenticated administrator can generate and list namespace tokens`() {
        val namespace = features.createNamespace(CreateNamespaceRequest("test-${UUID.randomUUID()}", "Token API test"))
        val path = "/api/v1/namespaces/${namespace.key}/tokens"
        mvc.perform(post(path).with(jwt()).contentType(MediaType.APPLICATION_JSON).content("""{"name":"backend"}"""))
            .andExpect(status().isCreated).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.token").isString).andExpect(jsonPath("$.tokenHash").doesNotExist())
        mvc.perform(get(path).with(jwt())).andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").value("backend")).andExpect(jsonPath("$[0].token").doesNotExist())
    }
}
