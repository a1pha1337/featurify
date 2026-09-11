package ru.a1pha1337.featurify.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("feature")
data class Feature(
    @Id val id: UUID? = null,
    val tenantId: UUID,
    val key: String,
    val type: FeatureType,
    val groupKey: String? = null,
    val booleanValue: Boolean? = null,
    val enumValue: String? = null,
    val description: String = "",
    val status: FeatureStatus = FeatureStatus.ACTIVE,
    @Version val version: Long? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun valueAsString(): String = when (type) {
        FeatureType.BOOLEAN -> booleanValue.toString()
        FeatureType.ENUM -> enumValue!!
    }
}
