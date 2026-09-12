package ru.a1pha1337.featurify.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant
import java.util.UUID

data class CreateAccessTokenRequest(@field:NotBlank @field:Size(max = 255) val name: String)
data class AccessTokenResponse(val id: UUID, val name: String, val createdAt: Instant)
// Deliberately no generated toString containing the credential.
class CreatedAccessTokenResponse(val id: UUID, val name: String, val createdAt: Instant, val token: String)
