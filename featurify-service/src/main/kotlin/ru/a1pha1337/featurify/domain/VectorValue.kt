package ru.a1pha1337.featurify.domain

data class VectorValue(
    val elements: Map<String, Boolean>,
) : FeatureValue {
    init {
        require(elements.size in 1..100)
        require(elements.keys.all { it.isNotBlank() && it.length <= 255 && it == it.trim() })
    }

    override val type: FeatureType get() = FeatureType.VECTOR

    override fun publicValue(): Map<String, Boolean> = elements
}
