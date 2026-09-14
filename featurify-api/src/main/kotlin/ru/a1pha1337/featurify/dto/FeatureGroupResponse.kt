package ru.a1pha1337.featurify.dto

import java.time.Instant
import java.util.UUID

data class FeatureGroupResponse(
    val id: UUID,
    val key: String,
    val displayName: String,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val managed: Boolean = false,
)
