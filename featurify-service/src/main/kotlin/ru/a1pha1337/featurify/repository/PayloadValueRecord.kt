package ru.a1pha1337.featurify.repository

import org.springframework.data.relational.core.mapping.Table

@Table("feature_payload_value")
data class PayloadValueRecord(
    val value: String,
)
