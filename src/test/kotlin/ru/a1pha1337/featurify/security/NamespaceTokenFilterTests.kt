package ru.a1pha1337.featurify.security

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
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
    private val tokens = mockk<AccessTokenService>(relaxUnitFun = true)
    private val mapper = JsonMapper.builder().build()
    private val filter = NamespaceTokenFilter(tokens, mapper)

    private fun request() = MockHttpServletRequest("GET", "/api/v1/features").apply { addHeader("Authorization", "Bearer token") }

    @Test
    fun `storage failure returns service unavailable problem without invoking controller`() {
        every { tokens.authenticateNamespace("token") } throws DataAccessResourceFailureException("private")
        val response = MockHttpServletResponse()
        val chain = mockk<FilterChain>()
        filter.doFilter(request(), response, chain)
        assertThat(response.status).isEqualTo(503)
        assertThat(response.contentType).isEqualTo("application/problem+json")
        val body = mapper.readTree(response.contentAsString)
        assertThat(body.path("status").asInt()).isEqualTo(503)
        assertThat(body.path("detail").asString()).isEqualTo("Authentication temporarily unavailable")
        assertThat(response.contentAsString).doesNotContain("private")
        verify(exactly = 0) { chain.doFilter(any(), any()) }
    }

    @Test
    fun `authenticated namespace and credential removal are scoped to current request`() {
        val now = Instant.now()
        val namespace = Namespace(UUID.randomUUID(), "blue", "Blue", true, now, now)
        every { tokens.authenticateNamespace("token") } returns namespace
        val chain =
            FilterChain { _, _ ->
                val authentication = checkNotNull(SecurityContextHolder.getContext().authentication)
                assertThat(authentication.principal).isEqualTo(NamespacePrincipal(namespace.id!!, "blue"))
                assertThat(authentication.credentials).isNull()
                throw IllegalStateException("downstream")
            }
        assertThatThrownBy { filter.doFilter(request(), MockHttpServletResponse(), chain) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(SecurityContextHolder.getContext().authentication).isNull()
    }
}
