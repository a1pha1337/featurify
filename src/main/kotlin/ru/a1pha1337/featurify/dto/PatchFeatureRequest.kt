package ru.a1pha1337.featurify.dto

import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

data class PatchFeatureRequest(
    @field:NotNull
    val version: Long?,
    @field:Size(max = 2000)
    val description: String? = null,
    val booleanValue: Boolean? = null,
    @field:Size(max = 255)
    val enumValue: String? = null,
)
