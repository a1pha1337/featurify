package ru.a1pha1337.featurify.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class CreateFeatureGroupRequest(
    @field:NotBlank
    @field:Size(max = 255)
    @field:Pattern(regexp = ValidationPatterns.FEATURE_GROUP)
    val key: String,
    @field:NotBlank
    @field:Size(max = 255)
    val displayName: String,
)
