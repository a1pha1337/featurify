package ru.a1pha1337.featurify.demo

data class FeatureDiagnostic<T>(
    val type: String,
    val key: String,
    val group: String?,
    val element: String?,
    val value: T,
    val version: Long,
    val cacheAllowed: Boolean,
    val elapsedMicros: Long,
)
