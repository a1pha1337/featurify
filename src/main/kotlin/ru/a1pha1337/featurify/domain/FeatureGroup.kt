package ru.a1pha1337.featurify.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("feature_group")
data class FeatureGroup(
    @Id val id: UUID? = null,
    val tenantId: UUID,
    val key: String,
    val displayName: String,
    val status: FeatureStatus = FeatureStatus.ACTIVE,
    @Version val version: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
)
