package ru.a1pha1337.featurify.demo

data class ClientDiagnostic(
    val host: String,
    val port: Int,
    val tls: Boolean,
    val timeout: String,
    val defaultGroup: String?,
    val cache: CacheDiagnostic,
)
