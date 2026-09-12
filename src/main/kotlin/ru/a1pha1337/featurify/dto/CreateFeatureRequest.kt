package ru.a1pha1337.featurify.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import ru.a1pha1337.featurify.domain.FeatureType

data class CreateFeatureRequest(
    @field:NotBlank @field:Size(max = 255)
    @field:Pattern(regexp = ValidationPatterns.FEATURE_KEY)
    val key: String,
    @field:NotNull
    val type: FeatureType?,
    @field:Size(max = 255)
    @field:Pattern(regexp = ValidationPatterns.FEATURE_GROUP)
    val group: String? = null,
    @field:Size(max = 2000)
    val description: String = "",
    val booleanValue: Boolean? = null,
    @field:Size(max = 255)
    val enumValue: String? = null,
    @field:Size(max = 100)
    val enumOptions: List<
        @NotBlank
        @Size(max = 255)
        String,
    > = emptyList(),
    @field:Size(max = 100)
    val vectorValues: Map<
        @NotBlank
        @Size(max = 255)
        String,
        @NotNull Boolean,
    > = emptyMap(),
)
