package ru.a1pha1337.featurify.repository

import org.springframework.data.repository.CrudRepository
import ru.a1pha1337.featurify.domain.FeatureAuditLog
import java.util.UUID

interface FeatureAuditLogRepository : CrudRepository<FeatureAuditLog, UUID> {
    fun findAllByTenantIdAndFeatureGroupAndFeatureKeyOrderByChangedAtDesc(
        tenantId: UUID,
        featureGroup: String,
        featureKey: String,
    ): List<FeatureAuditLog>

    fun findAllByTenantIdAndFeatureGroupIsNullAndFeatureKeyOrderByChangedAtDesc(
        tenantId: UUID,
        featureKey: String,
    ): List<FeatureAuditLog>
}
