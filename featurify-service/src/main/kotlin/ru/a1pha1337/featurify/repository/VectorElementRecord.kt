package ru.a1pha1337.featurify.repository

import org.springframework.data.relational.core.mapping.Table

@Table("feature_vector_element")
data class VectorElementRecord(
    val enabled: Boolean,
)
