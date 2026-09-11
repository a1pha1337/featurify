package ru.a1pha1337.featurify.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.util.UUID

@Table("feature_enum_option")
data class FeatureEnumOption(
    @Id val id: UUID? = null,
    val featureId: UUID,
    val value: String,
    val sortOrder: Int,
)
