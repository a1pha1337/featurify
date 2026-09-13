package ru.a1pha1337.featurify.dto

import java.time.Instant
import java.util.UUID

data class AccessTokenResponse(
    val id: UUID,
    val name: String,
    val createdAt: Instant,
)
