package ru.a1pha1337.featurify.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class CreateTenantRequest(
    @field:NotBlank
    @field:Size(max = 255)
    @field:Pattern(regexp = ValidationPatterns.TENANT_KEY)
    val key: String,
    @field:NotBlank @field:Size(max = 255)
    val displayName: String,
)
