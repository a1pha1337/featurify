package ru.a1pha1337.featurify.dto

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class MoveFeatureRequest(
    @field:NotNull
    val version: Long?,
    @field:Size(max = 255)
    @field:Pattern(regexp = ValidationPatterns.FEATURE_GROUP)
    val targetGroup: String? = null,
)
