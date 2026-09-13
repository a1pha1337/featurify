package ru.a1pha1337.featurify.domain

import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

data class Feature(
    val id: UUID? = null,
    val namespaceId: UUID,
    val key: String,
    val value: FeatureValue,
    val groupId: UUID? = null,
    val description: String = "",
    val version: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val type: FeatureType get() = value.type

    fun valueAsString(): String =
        when (val current = value) {
            is BooleanValue -> current.enabled.toString()
            is EnumValue -> current.selected
            is VectorValue -> JsonMapper().writeValueAsString(current.elements.toSortedMap())
        }
}
