package ru.a1pha1337.featurify.dto

data class NamespaceManifest(
    val key: String,
    val displayName: String,
    val management: ManifestManagement = ManifestManagement(),
    val features: List<CreateFeatureRequest> = emptyList(),
    val groups: List<ManifestGroup> = emptyList(),
)
