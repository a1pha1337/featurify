package ru.a1pha1337.featurify.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("namespace_access_token")
data class NamespaceAccessToken(
    @Id val id: UUID? = null,
    val namespaceId: UUID,
    val name: String,
    val tokenHash: String,
    val createdAt: Instant,
) {
    override fun toString() = "NamespaceAccessToken(id=$id, namespaceId=$namespaceId)"
}
