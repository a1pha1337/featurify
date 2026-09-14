package ru.a1pha1337.featurify.dto

data class ManifestGroup(
    val key: String,
    val displayName: String,
    val features: List<CreateFeatureRequest> = emptyList(),
)
