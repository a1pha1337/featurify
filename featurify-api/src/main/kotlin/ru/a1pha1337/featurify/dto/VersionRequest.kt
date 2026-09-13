package ru.a1pha1337.featurify.dto

import jakarta.validation.constraints.NotNull

data class VersionRequest(
    @field:NotNull
    val version: Long?,
)
