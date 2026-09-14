package ru.a1pha1337.featurify.dto

data class ManifestOwner(
    val clusterId: String,
    val uid: String,
    val kubernetesNamespace: String,
    val name: String,
)
