package ru.a1pha1337.featurify.dto

data class ApplyNamespaceManifestRequest(
    val owner: ManifestOwner,
    val generation: Long,
    val spec: NamespaceManifest,
)
