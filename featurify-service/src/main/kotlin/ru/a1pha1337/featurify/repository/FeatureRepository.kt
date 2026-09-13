package ru.a1pha1337.featurify.repository

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import ru.a1pha1337.featurify.domain.Feature
import java.util.UUID

@Repository
@Transactional(readOnly = true)
class FeatureRepository(
    private val records: FeatureRecordRepository,
) {
    @Transactional
    fun save(feature: Feature): Feature = records.save(FeatureRecord.fromDomain(feature)).toDomain()

    @Transactional
    fun delete(feature: Feature) = records.delete(FeatureRecord.fromDomain(feature))

    fun findAllByNamespaceIdAndGroupId(
        namespaceId: UUID,
        groupId: UUID,
        pageable: Pageable,
    ): Page<Feature> = records.findAllByNamespaceIdAndGroupId(namespaceId, groupId, pageable).map { it.toDomain() }

    fun findAllByNamespaceIdAndGroupIdAndKeyContaining(
        namespaceId: UUID,
        groupId: UUID,
        key: String,
        pageable: Pageable,
    ): Page<Feature> = records.findAllByNamespaceIdAndGroupIdAndKeyContaining(namespaceId, groupId, key, pageable).map { it.toDomain() }

    fun findAllByNamespaceIdAndGroupIdIsNull(
        namespaceId: UUID,
        pageable: Pageable,
    ): Page<Feature> = records.findAllByNamespaceIdAndGroupIdIsNull(namespaceId, pageable).map { it.toDomain() }

    fun findAllByNamespaceIdAndGroupIdIsNullAndKeyContaining(
        namespaceId: UUID,
        key: String,
        pageable: Pageable,
    ): Page<Feature> = records.findAllByNamespaceIdAndGroupIdIsNullAndKeyContaining(namespaceId, key, pageable).map { it.toDomain() }

    fun findByNamespaceIdAndGroupIdAndKey(
        namespaceId: UUID,
        groupId: UUID,
        key: String,
    ): Feature? = records.findByNamespaceIdAndGroupIdAndKey(namespaceId, groupId, key)?.toDomain()

    fun findByNamespaceIdAndGroupIdIsNullAndKey(
        namespaceId: UUID,
        key: String,
    ): Feature? = records.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, key)?.toDomain()

    fun findAllByNamespaceId(
        namespaceId: UUID,
        pageable: Pageable,
    ): Page<Feature> = records.findAllByNamespaceId(namespaceId, pageable).map { it.toDomain() }

    fun findAllByNamespaceIdAndKeyContaining(
        namespaceId: UUID,
        key: String,
        pageable: Pageable,
    ): Page<Feature> = records.findAllByNamespaceIdAndKeyContaining(namespaceId, key, pageable).map { it.toDomain() }
}
