package ru.a1pha1337.featurify.security

import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component

@Component
class ActorProvider {
    fun currentUsername(): String {
        val authentication: Authentication? = SecurityContextHolder.getContext().authentication
        if (authentication == null || !authentication.isAuthenticated) return "system"
        val principal = authentication.principal
        return when (principal) {
            is OAuth2User -> principal.getAttribute<String>("preferred_username") ?: authentication.name
            is Jwt -> principal.getClaimAsString("preferred_username") ?: authentication.name
            else -> authentication.name
        }
    }
}
