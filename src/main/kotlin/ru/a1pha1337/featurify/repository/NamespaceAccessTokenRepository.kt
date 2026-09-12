package ru.a1pha1337.featurify.repository

import org.springframework.data.repository.CrudRepository
import ru.a1pha1337.featurify.domain.NamespaceAccessToken
import java.util.UUID

interface NamespaceAccessTokenRepository : CrudRepository<NamespaceAccessToken, UUID> {
    fun findAllByNamespaceIdOrderByCreatedAtDesc(namespaceId: UUID): List<NamespaceAccessToken>

    fun findByIdAndNamespaceId(
        id: UUID,
        namespaceId: UUID,
    ): NamespaceAccessToken?
}
