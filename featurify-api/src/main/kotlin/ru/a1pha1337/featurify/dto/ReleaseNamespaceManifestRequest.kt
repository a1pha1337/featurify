package ru.a1pha1337.featurify.dto

data class ReleaseNamespaceManifestRequest(
    val owner: ManifestOwner,
    val generation: Long,
    val deletionPolicy: DeletionPolicy,
)
