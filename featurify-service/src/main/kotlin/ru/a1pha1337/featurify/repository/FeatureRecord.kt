package ru.a1pha1337.featurify.repository

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.MappedCollection
import org.springframework.data.relational.core.mapping.Table
import ru.a1pha1337.featurify.domain.BooleanValue
import ru.a1pha1337.featurify.domain.EnumValue
import ru.a1pha1337.featurify.domain.Feature
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.domain.PayloadValue
import ru.a1pha1337.featurify.domain.VectorValue
import java.time.Instant
import java.util.UUID

/** JDBC aggregate; nullable child rows are converted to a single typed domain value. */
@Table("feature")
data class FeatureRecord(
    @Id val id: UUID? = null,
    val namespaceId: UUID,
    val key: String,
    val type: FeatureType,
    val groupId: UUID? = null,
    val description: String = "",
    @Version val version: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    @MappedCollection(idColumn = "feature_id")
    val booleanValue: BooleanValueRecord? = null,
    @MappedCollection(idColumn = "feature_id")
    val enumValue: EnumValueRecord? = null,
    @MappedCollection(idColumn = "feature_id", keyColumn = "sort_order")
    val enumOptions: List<EnumOptionRecord> = emptyList(),
    @MappedCollection(idColumn = "feature_id", keyColumn = "element")
    val vectorElements: Map<String, VectorElementRecord> = emptyMap(),
    @MappedCollection(idColumn = "feature_id")
    val payloadValue: PayloadValueRecord? = null,
) {
    fun toDomain(): Feature =
        Feature(
            id = id,
            namespaceId = namespaceId,
            key = key,
            groupId = groupId,
            description = description,
            version = version,
            createdAt = createdAt,
            updatedAt = updatedAt,
            value =
                when (type) {
                    FeatureType.BOOLEAN -> BooleanValue(checkNotNull(booleanValue).enabled)
                    FeatureType.ENUM -> EnumValue(checkNotNull(enumValue).value, enumOptions.map { it.value })
                    FeatureType.VECTOR -> VectorValue(vectorElements.mapValues { it.value.enabled })
                    FeatureType.PAYLOAD -> PayloadValue(checkNotNull(payloadValue).value)
                },
        )

    companion object {
        fun fromDomain(feature: Feature): FeatureRecord =
            FeatureRecord(
                id = feature.id,
                namespaceId = feature.namespaceId,
                key = feature.key,
                type = feature.type,
                groupId = feature.groupId,
                description = feature.description,
                version = feature.version,
                createdAt = feature.createdAt,
                updatedAt = feature.updatedAt,
                booleanValue = (feature.value as? BooleanValue)?.let { BooleanValueRecord(it.enabled) },
                enumValue = (feature.value as? EnumValue)?.let { EnumValueRecord(it.selected) },
                enumOptions = (feature.value as? EnumValue)?.options?.map { EnumOptionRecord(it) } ?: emptyList(),
                vectorElements = (feature.value as? VectorValue)?.elements?.mapValues { VectorElementRecord(it.value) } ?: emptyMap(),
                payloadValue = (feature.value as? PayloadValue)?.let { PayloadValueRecord(it.json) },
            )
    }
}
