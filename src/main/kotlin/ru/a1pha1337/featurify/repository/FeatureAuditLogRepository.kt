package ru.a1pha1337.featurify.repository

import org.springframework.data.repository.CrudRepository
import ru.a1pha1337.featurify.domain.FeatureAuditLog
import java.util.UUID

interface FeatureAuditLogRepository : CrudRepository<FeatureAuditLog, UUID> {
    fun findAllByNamespaceIdAndFeatureGroupAndFeatureKeyOrderByChangedAtDesc(
        namespaceId: UUID,
        featureGroup: String,
        featureKey: String,
    ): List<FeatureAuditLog>

    fun findAllByNamespaceIdAndFeatureGroupIsNullAndFeatureKeyOrderByChangedAtDesc(
        namespaceId: UUID,
        featureKey: String,
    ): List<FeatureAuditLog>
}
