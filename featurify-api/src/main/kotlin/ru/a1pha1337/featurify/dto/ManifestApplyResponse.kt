package ru.a1pha1337.featurify.dto

data class ManifestApplyResponse(
    val observedGeneration: Long,
    val changed: Boolean,
)
