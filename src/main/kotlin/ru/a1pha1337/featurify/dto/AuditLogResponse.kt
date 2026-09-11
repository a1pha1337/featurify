package ru.a1pha1337.featurify.dto

import ru.a1pha1337.featurify.domain.AuditOperation
import java.time.Instant

data class AuditLogResponse(
    val operation: AuditOperation,
    val oldValue: String?,
    val newValue: String?,
    val changedBy: String,
    val changedAt: Instant,
)
