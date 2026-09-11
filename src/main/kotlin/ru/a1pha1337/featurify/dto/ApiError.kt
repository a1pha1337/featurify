package ru.a1pha1337.featurify.dto

data class ApiError(
    val code: String,
    val message: String,
    val details: List<ValidationDetail> = emptyList(),
)
