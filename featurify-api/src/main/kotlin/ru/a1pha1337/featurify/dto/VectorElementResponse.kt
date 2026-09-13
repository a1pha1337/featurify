package ru.a1pha1337.featurify.dto

data class VectorElementResponse(
    val group: String?,
    val key: String,
    val element: String,
    val value: Boolean,
    val version: Long,
)
