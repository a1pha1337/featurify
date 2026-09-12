package ru.a1pha1337.featurify.controller

import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import ru.a1pha1337.featurify.dto.AccessTokenResponse
import ru.a1pha1337.featurify.dto.CreateAccessTokenRequest
import ru.a1pha1337.featurify.dto.CreatedAccessTokenResponse
import ru.a1pha1337.featurify.service.AccessTokenService
import java.time.Instant
import java.util.UUID

class AccessTokenControllerTests {
    private val service = mock(AccessTokenService::class.java)
    private val mvc =
        MockMvcBuilders
            .standaloneSetup(AccessTokenController(service))
            .setControllerAdvice(ApiExceptionHandler())
            .build()
    private val id = UUID.randomUUID()
    private val now = Instant.parse("2026-09-12T12:00:00Z")

    @Test
    fun `creation returns secret once with no store cache policy`() {
        `when`(service.create("blue", CreateAccessTokenRequest("backend")))
            .thenReturn(CreatedAccessTokenResponse(id, "backend", now, "test-only-secret"))
        mvc
            .perform(
                post("/api/v1/namespaces/blue/tokens")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"backend"}"""),
            ).andExpect(status().isCreated)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.token").value("test-only-secret"))
            .andExpect(jsonPath("$.tokenHash").doesNotExist())
    }

    @Test
    fun `list excludes secrets and hashes and revoke uses namespace scope`() {
        `when`(service.list("blue")).thenReturn(listOf(AccessTokenResponse(id, "backend", now)))
        mvc
            .perform(get("/api/v1/namespaces/blue/tokens"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].name").value("backend"))
            .andExpect(jsonPath("$[0].token").doesNotExist())
            .andExpect(jsonPath("$[0].tokenHash").doesNotExist())
        mvc.perform(delete("/api/v1/namespaces/blue/tokens/$id")).andExpect(status().isNoContent)
        verify(service).revoke("blue", id)
    }

    @Test
    fun `blank token name is rejected before service call`() {
        mvc
            .perform(
                post("/api/v1/namespaces/blue/tokens")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":" "}"""),
            ).andExpect(status().isBadRequest)
        verifyNoInteractions(service)
    }
}
