package ru.a1pha1337.featurify.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("feature_audit_log")
data class FeatureAuditLog(
    @Id val id: UUID? = null,
    val namespaceId: UUID,
    val featureId: UUID,
    val namespaceKey: String,
    val featureGroup: String?,
    val featureKey: String,
    val operation: AuditOperation,
    val oldValue: String?,
    val newValue: String?,
    val changedBy: String,
    val changedAt: Instant,
)
