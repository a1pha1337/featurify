package ru.a1pha1337.featurify.repository

import org.springframework.data.repository.CrudRepository
import ru.a1pha1337.featurify.domain.Feature
import ru.a1pha1337.featurify.domain.FeatureStatus
import java.util.UUID

interface FeatureRepository : CrudRepository<Feature, UUID> {
    fun findByTenantIdAndKey(tenantId: UUID, key: String): Feature?
    fun findAllByTenantIdAndStatusOrderByKey(tenantId: UUID, status: FeatureStatus): List<Feature>
    fun findAllByTenantIdOrderByKey(tenantId: UUID): List<Feature>
}
