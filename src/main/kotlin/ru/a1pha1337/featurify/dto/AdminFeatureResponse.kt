package ru.a1pha1337.featurify.dto

import ru.a1pha1337.featurify.domain.FeatureStatus
import ru.a1pha1337.featurify.domain.FeatureType
import java.time.Instant

data class AdminFeatureResponse(
    val group: String?,
    val key: String,
    val type: FeatureType,
    val value: Any,
    val enumOptions: List<String>?,
    val description: String,
    val status: FeatureStatus,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
)
