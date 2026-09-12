package ru.a1pha1337.featurify.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import tools.jackson.databind.json.JsonMapper
import java.time.Instant
import java.util.UUID

@Table("feature")
data class Feature(
    @Id val id: UUID? = null,
    val namespaceId: UUID,
    val key: String,
    val type: FeatureType,
    val groupId: UUID? = null,
    val booleanValue: Boolean? = null,
    val enumValue: String? = null,
    val description: String = "",
    @Version val version: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    @MappedCollection(idColumn = "feature_id", keyColumn = "element")
    val vectorElements: Map<String, FeatureVectorElement> = emptyMap(),
) {
    fun vectorValues(): Map<String, Boolean> = vectorElements.mapValues { it.value.enabled }

    fun value(): Any =
        when (type) {
            FeatureType.BOOLEAN -> booleanValue!!
            FeatureType.ENUM -> enumValue!!
            FeatureType.VECTOR -> vectorValues()
        }

    fun valueAsString(): String =
        when (type) {
            FeatureType.BOOLEAN -> booleanValue.toString()
            FeatureType.ENUM -> enumValue!!
            FeatureType.VECTOR -> JsonMapper().writeValueAsString(vectorValues().toSortedMap())
        }
}
