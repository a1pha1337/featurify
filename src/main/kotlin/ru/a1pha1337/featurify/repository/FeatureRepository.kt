package ru.a1pha1337.featurify.repository

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.repository.CrudRepository
import ru.a1pha1337.featurify.domain.Feature
import ru.a1pha1337.featurify.domain.FeatureStatus
import java.util.UUID

interface FeatureRepository : CrudRepository<Feature, UUID> {
    fun findByTenantIdAndKey(tenantId: UUID, key: String): Feature?
    fun findAllByTenantIdAndStatus(tenantId: UUID, status: FeatureStatus, pageable: Pageable): Page<Feature>
    fun findAllByTenantIdAndStatusAndKeyContaining(
        tenantId: UUID,
        status: FeatureStatus,
        key: String,
        pageable: Pageable,
    ): Page<Feature>
    fun findAllByTenantIdAndStatusIn(
        tenantId: UUID,
        statuses: Collection<FeatureStatus>,
        pageable: Pageable,
    ): Page<Feature>
    fun findAllByTenantIdAndStatusInAndKeyContaining(
        tenantId: UUID,
        statuses: Collection<FeatureStatus>,
        key: String,
        pageable: Pageable,
    ): Page<Feature>
}
