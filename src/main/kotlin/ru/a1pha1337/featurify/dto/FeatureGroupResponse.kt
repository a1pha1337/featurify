package ru.a1pha1337.featurify.dto

import ru.a1pha1337.featurify.domain.FeatureStatus
import java.time.Instant
import java.util.UUID

data class FeatureGroupResponse(
    val id: UUID,
    val key: String,
    val displayName: String,
    val status: FeatureStatus,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)
