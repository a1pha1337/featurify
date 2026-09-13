package ru.a1pha1337.featurify.domain

sealed interface FeatureValue {
    val type: FeatureType

    fun publicValue(): Any
}
