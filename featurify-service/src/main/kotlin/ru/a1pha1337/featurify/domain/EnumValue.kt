package ru.a1pha1337.featurify.domain

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
