package ru.a1pha1337.featurify.repository

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.CrudRepository
import ru.a1pha1337.featurify.domain.Feature
import java.util.UUID

interface FeatureRepository : CrudRepository<Feature, UUID> {
    fun findAllByTenantIdAndGroupId(
        tenantId: UUID, groupId: UUID, pageable: Pageable,
    ): Page<Feature>
    fun findAllByTenantIdAndGroupIdAndKeyContaining(
        tenantId: UUID, groupId: UUID, key: String, pageable: Pageable,
    ): Page<Feature>
    fun findAllByTenantIdAndGroupIdIsNull(
        tenantId: UUID, pageable: Pageable,
    ): Page<Feature>
    fun findAllByTenantIdAndGroupIdIsNullAndKeyContaining(
        tenantId: UUID, key: String, pageable: Pageable,
    ): Page<Feature>
    fun findByTenantIdAndGroupIdAndKey(tenantId: UUID, groupId: UUID, key: String): Feature?
    fun findByTenantIdAndGroupIdIsNullAndKey(tenantId: UUID, key: String): Feature?
    fun findAllByTenantId(tenantId: UUID, pageable: Pageable): Page<Feature>
    fun findAllByTenantIdAndKeyContaining(tenantId: UUID, key: String, pageable: Pageable): Page<Feature>
}
