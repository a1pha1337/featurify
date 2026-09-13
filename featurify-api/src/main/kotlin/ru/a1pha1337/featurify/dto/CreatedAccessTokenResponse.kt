package ru.a1pha1337.featurify.dto

import java.time.Instant
import java.util.UUID

// Deliberately no generated toString containing the credential.
class CreatedAccessTokenResponse(
    val id: UUID,
    val name: String,
    val createdAt: Instant,
    val token: String,
)
