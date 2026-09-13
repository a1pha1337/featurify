package ru.a1pha1337.featurify.dto

import ru.a1pha1337.featurify.domain.FeatureType

data class FeatureResponse(
    val group: String?,
    val key: String,
    val type: FeatureType,
    val value: Any,
    val enumOptions: List<String>?,
    val version: Long,
)
