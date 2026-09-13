package ru.a1pha1337.featurify.domain

data class BooleanValue(
    val enabled: Boolean,
) : FeatureValue {
    override val type: FeatureType get() = FeatureType.BOOLEAN

    override fun publicValue(): Boolean = enabled
}
