package ru.a1pha1337.featurify.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("tenant")
data class Tenant(
    @Id val id: UUID? = null,
    val key: String,
    val displayName: String,
    val active: Boolean = true,
    val createdAt: Instant,
    val updatedAt: Instant,
)
