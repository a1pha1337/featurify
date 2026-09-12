package ru.a1pha1337.featurify.security

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import ru.a1pha1337.featurify.domain.Namespace
import ru.a1pha1337.featurify.service.AccessTokenService
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

class NamespaceTokenFilterTests {
    private val tokens = mock(AccessTokenService::class.java)
    private val mapper = JsonMapper.builder().build()
    private val filter = NamespaceTokenFilter(tokens, mapper)

    private fun request() = MockHttpServletRequest("GET", "/api/v1/features").apply { addHeader("Authorization", "Bearer token") }

    @Test
    fun `storage failure returns service unavailable problem without invoking controller`() {
        `when`(tokens.authenticateNamespace("token")).thenThrow(DataAccessResourceFailureException("private"))
        val response = MockHttpServletResponse()
        val chain = mock(FilterChain::class.java)
        filter.doFilter(request(), response, chain)
        assertEquals(503, response.status)
        assertEquals("application/problem+json", response.contentType)
        val body = mapper.readTree(response.contentAsString)
        assertEquals(503, body.path("status").asInt())
        assertEquals("Authentication temporarily unavailable", body.path("detail").asText())
        assertFalse(response.contentAsString.contains("private"))
        verifyNoInteractions(chain)
    }

    @Test
    fun `authenticated namespace and credential removal are scoped to current request`() {
        val now = Instant.now()
        val namespace = Namespace(UUID.randomUUID(), "blue", "Blue", true, now, now)
        `when`(tokens.authenticateNamespace("token")).thenReturn(namespace)
        val chain =
            FilterChain { _, _ ->
                val authentication = checkNotNull(SecurityContextHolder.getContext().authentication)
                assertEquals(NamespacePrincipal(namespace.id!!, "blue"), authentication.principal)
                assertNull(authentication.credentials)
                throw IllegalStateException("downstream")
            }
        assertThrows(IllegalStateException::class.java) { filter.doFilter(request(), MockHttpServletResponse(), chain) }
        assertNull(SecurityContextHolder.getContext().authentication)
    }
}
