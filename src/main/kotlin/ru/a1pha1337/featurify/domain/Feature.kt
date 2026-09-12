package ru.a1pha1337.featurify.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
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
) {
    fun valueAsString(): String =
        when (type) {
            FeatureType.BOOLEAN -> booleanValue.toString()
            FeatureType.ENUM -> enumValue!!
        }
}
