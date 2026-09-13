package ru.a1pha1337.featurify.dto

import java.time.Instant
import java.util.UUID

data class NamespaceResponse(
    val id: UUID,
    val key: String,
    val displayName: String,
    val active: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val defaultNamespace: Boolean,
)
