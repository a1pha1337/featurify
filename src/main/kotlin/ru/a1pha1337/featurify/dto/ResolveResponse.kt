package ru.a1pha1337.featurify.dto

data class ResolveResponse(
    val features: Map<String, FeatureResponse>,
)
