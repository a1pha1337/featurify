package ru.a1pha1337.featurify.domain

import org.springframework.data.relational.core.mapping.Table

@Table("feature_vector_element")
data class FeatureVectorElement(
    val enabled: Boolean,
)
