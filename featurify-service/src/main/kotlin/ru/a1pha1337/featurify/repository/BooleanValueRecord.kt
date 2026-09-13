package ru.a1pha1337.featurify.repository

import org.springframework.data.relational.core.mapping.Table

@Table("feature_boolean_value")
data class BooleanValueRecord(
    val enabled: Boolean,
)
