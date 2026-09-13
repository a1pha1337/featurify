package ru.a1pha1337.featurify.repository

import org.springframework.data.relational.core.mapping.Table

@Table("feature_enum_value")
data class EnumValueRecord(
    val value: String,
)
