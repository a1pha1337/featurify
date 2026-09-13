package ru.a1pha1337.featurify.security

import org.springframework.security.access.AccessDeniedException
import java.util.UUID

data class NamespacePrincipal(
    val id: UUID,
    val key: String,
) {
    fun requireNamespace(requestedKey: String?): UUID {
        if (requestedKey != null && requestedKey != key) {
            throw AccessDeniedException("The token does not grant access to the requested namespace")
        }
        return id
    }
}
