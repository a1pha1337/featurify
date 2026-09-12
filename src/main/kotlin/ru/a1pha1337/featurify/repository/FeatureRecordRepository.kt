package ru.a1pha1337.featurify.repository

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.CrudRepository
import java.util.UUID

interface FeatureRecordRepository : CrudRepository<FeatureRecord, UUID> {
    fun findAllByNamespaceIdAndGroupId(
        namespaceId: UUID,
        groupId: UUID,
        pageable: Pageable,
    ): Page<FeatureRecord>

    fun findAllByNamespaceIdAndGroupIdAndKeyContaining(
        namespaceId: UUID,
        groupId: UUID,
        key: String,
        pageable: Pageable,
    ): Page<FeatureRecord>

    fun findAllByNamespaceIdAndGroupIdIsNull(
        namespaceId: UUID,
        pageable: Pageable,
    ): Page<FeatureRecord>

    fun findAllByNamespaceIdAndGroupIdIsNullAndKeyContaining(
        namespaceId: UUID,
        key: String,
        pageable: Pageable,
    ): Page<FeatureRecord>

    fun findByNamespaceIdAndGroupIdAndKey(
        namespaceId: UUID,
        groupId: UUID,
        key: String,
    ): FeatureRecord?

    fun findByNamespaceIdAndGroupIdIsNullAndKey(
        namespaceId: UUID,
        key: String,
    ): FeatureRecord?

    fun findAllByNamespaceId(
        namespaceId: UUID,
        pageable: Pageable,
    ): Page<FeatureRecord>

    fun findAllByNamespaceIdAndKeyContaining(
        namespaceId: UUID,
        key: String,
        pageable: Pageable,
    ): Page<FeatureRecord>
}
