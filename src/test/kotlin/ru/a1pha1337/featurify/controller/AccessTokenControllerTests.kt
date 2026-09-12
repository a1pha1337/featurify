package ru.a1pha1337.featurify.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import ru.a1pha1337.featurify.dto.AccessTokenResponse
import ru.a1pha1337.featurify.dto.CreateAccessTokenRequest
import ru.a1pha1337.featurify.dto.CreatedAccessTokenResponse
import ru.a1pha1337.featurify.service.AccessTokenService
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

class AccessTokenControllerTests {
    private val service = mockk<AccessTokenService>(relaxUnitFun = true)
    private val mvc =
        MockMvcBuilders
            .standaloneSetup(AccessTokenController(service))
            .setControllerAdvice(ApiExceptionHandler())
            .build()
    private val id = UUID.randomUUID()
    private val now = Instant.parse("2026-09-12T12:00:00Z")

    @Test
    fun `creation returns secret once with no store cache policy`() {
        every { service.create("blue", CreateAccessTokenRequest("backend")) } returns
            CreatedAccessTokenResponse(id, "backend", now, "test-only-secret")
        mvc
            .perform(
                post("/api/v1/namespaces/blue/tokens")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"backend"}"""),
            ).andExpect { assertThat(it.response.status).isEqualTo(201) }
            .andExpect { assertThat(it.response.getHeader("Cache-Control")).isEqualTo("no-store") }
            .andExpect {
                assertThat(
                    JsonMapper().readTree(it.response.contentAsString).at("/token").asString(),
                ).isEqualTo("test-only-secret")
            }.andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/tokenHash").isMissingNode).isTrue() }
    }

    @Test
    fun `list excludes secrets and hashes and revoke uses namespace scope`() {
        every { service.list("blue") } returns listOf(AccessTokenResponse(id, "backend", now))
        mvc
            .perform(get("/api/v1/namespaces/blue/tokens"))
            .andExpect { assertThat(it.response.status).isEqualTo(200) }
            .andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/0/name").asString()).isEqualTo("backend") }
            .andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/0/token").isMissingNode).isTrue() }
            .andExpect { assertThat(JsonMapper().readTree(it.response.contentAsString).at("/0/tokenHash").isMissingNode).isTrue() }
        mvc.perform(delete("/api/v1/namespaces/blue/tokens/$id")).andExpect { assertThat(it.response.status).isEqualTo(204) }
        verify { service.revoke("blue", id) }
    }

    @Test
    fun `blank token name is rejected before service call`() {
        mvc
            .perform(
                post("/api/v1/namespaces/blue/tokens")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":" "}"""),
            ).andExpect { assertThat(it.response.status).isEqualTo(400) }
        verify(exactly = 0) { service.create(any(), any()) }
    }
}
