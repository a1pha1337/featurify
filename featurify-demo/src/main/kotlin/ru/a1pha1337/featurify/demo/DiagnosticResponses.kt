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

data class ClientDiagnostic(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val timeout: String,
    val defaultGroup: String?,
    val cache: CacheDiagnostic,
)

data class CacheDiagnostic(
    val ttl: String,
    val maximumSize: Long,
)
