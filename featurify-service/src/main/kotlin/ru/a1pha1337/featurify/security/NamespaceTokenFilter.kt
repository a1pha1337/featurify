package ru.a1pha1337.featurify.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import ru.a1pha1337.featurify.controller.ApiProblems
import ru.a1pha1337.featurify.service.AccessTokenService
import tools.jackson.databind.ObjectMapper

/** Installed only in the stateless feature-read security chain, never as a servlet filter bean. */
class NamespaceTokenFilter(
    private val tokens: AccessTokenService,
    private val mapper: ObjectMapper,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        chain: FilterChain,
    ) {
        SecurityContextHolder.clearContext()
        val header = request.getHeaders("Authorization").toList().singleOrNull()
        val namespace =
            try {
                if (header != null && header.startsWith("Bearer ") && header.length <= 100) {
                    tokens.authenticateNamespace(header.substring(7))
                } else {
                    null
                }
            } catch (_: RuntimeException) {
                LoggerFactory.getLogger(javaClass).error("Access token authentication storage failed")
                ApiProblems.write(
                    mapper,
                    request,
                    response,
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SERVICE_UNAVAILABLE",
                    "Authentication temporarily unavailable",
                )
                return
            }
        if (namespace == null) {
            response.setHeader("WWW-Authenticate", "Bearer realm=\"featurify\"")
            ApiProblems.write(
                mapper,
                request,
                response,
                HttpStatus.UNAUTHORIZED,
                "UNAUTHORIZED",
                "A valid namespace access token is required",
            )
            return
        }
        val context = SecurityContextHolder.createEmptyContext()
        context.authentication =
            UsernamePasswordAuthenticationToken(
                NamespacePrincipal(namespace.id!!, namespace.key),
                null,
                listOf(SimpleGrantedAuthority("NAMESPACE_READ")),
            )
        SecurityContextHolder.setContext(context)
        try {
            chain.doFilter(request, response)
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
