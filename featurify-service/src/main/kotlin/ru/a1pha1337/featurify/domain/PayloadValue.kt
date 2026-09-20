package ru.a1pha1337.featurify.domain

import tools.jackson.core.StreamReadFeature
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/** A complete JSON document. Canonical object ordering prevents formatting-only changes in audit and reconciliation. */
class PayloadValue(
    json: String,
) : FeatureValue {
    val json: String

    init {
        require(json.isNotBlank() && json.length <= 65536) { "JSON must contain 1-65536 characters" }
        val parsed = mapper.readValue(json, Any::class.java)
        this.json = mapper.writeValueAsString(canonical(parsed))
        require(this.json.length <= 65536) { "Normalized JSON must not exceed 65536 characters" }
    }

    override val type: FeatureType get() = FeatureType.PAYLOAD

    override fun publicValue(): JsonNode = mapper.readTree(json)

    override fun equals(other: Any?): Boolean = other is PayloadValue && json == other.json

    override fun hashCode(): Int = json.hashCode()

    companion object {
        private val mapper =
            JsonMapper
                .builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .build()

        private fun canonical(value: Any?): Any? =
            when (value) {
                is Map<*, *> -> value.entries.associate { (key, item) -> key.toString() to canonical(item) }.toSortedMap()
                is List<*> -> value.map { canonical(it) }
                else -> value
            }
    }
}
