package ru.a1pha1337.featurify.domain

sealed interface FeatureValue {
    val type: FeatureType

    fun publicValue(): Any
}

data class BooleanValue(
    val enabled: Boolean,
) : FeatureValue {
    override val type: FeatureType get() = FeatureType.BOOLEAN

    override fun publicValue(): Boolean = enabled
}

data class EnumValue(
    val selected: String,
    val options: List<String>,
) : FeatureValue {
    init {
        require(options.size in 1..100 && options.distinct().size == options.size)
        require(options.all { it.isNotBlank() && it.length <= 255 })
        require(selected in options)
    }

    override val type: FeatureType get() = FeatureType.ENUM

    override fun publicValue(): String = selected
}

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
