package ru.a1pha1337.featurify.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateAccessTokenRequest(
    @field:NotBlank @field:Size(max = 255) val name: String,
)
