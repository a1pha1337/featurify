package ru.a1pha1337.featurify.repository

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.CrudRepository
import ru.a1pha1337.featurify.domain.Feature
import java.util.UUID

interface FeatureRepository : CrudRepository<Feature, UUID> {
    fun findAllByNamespaceIdAndGroupId(
        namespaceId: UUID,
        groupId: UUID,
        pageable: Pageable,
    ): Page<Feature>

    fun findAllByNamespaceIdAndGroupIdAndKeyContaining(
        namespaceId: UUID,
        groupId: UUID,
        key: String,
        pageable: Pageable,
    ): Page<Feature>

    fun findAllByNamespaceIdAndGroupIdIsNull(
        namespaceId: UUID,
        pageable: Pageable,
    ): Page<Feature>

    fun findAllByNamespaceIdAndGroupIdIsNullAndKeyContaining(
        namespaceId: UUID,
        key: String,
        pageable: Pageable,
    ): Page<Feature>

    fun findByNamespaceIdAndGroupIdAndKey(
        namespaceId: UUID,
        groupId: UUID,
        key: String,
    ): Feature?

    fun findByNamespaceIdAndGroupIdIsNullAndKey(
        namespaceId: UUID,
        key: String,
    ): Feature?

    fun findAllByNamespaceId(
        namespaceId: UUID,
        pageable: Pageable,
    ): Page<Feature>

    fun findAllByNamespaceIdAndKeyContaining(
        namespaceId: UUID,
        key: String,
        pageable: Pageable,
    ): Page<Feature>
}
