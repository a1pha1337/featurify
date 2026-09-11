package ru.a1pha1337.featurify.service

import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.a1pha1337.featurify.dto.AdminFeatureResponse
import ru.a1pha1337.featurify.dto.AuditLogResponse
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreateTenantRequest
import ru.a1pha1337.featurify.dto.FeatureResponse
import ru.a1pha1337.featurify.dto.PatchFeatureRequest
import ru.a1pha1337.featurify.dto.ResolveResponse
import ru.a1pha1337.featurify.dto.TenantResponse
import ru.a1pha1337.featurify.dto.ValidationPatterns
import ru.a1pha1337.featurify.domain.AuditOperation
import ru.a1pha1337.featurify.domain.Feature
import ru.a1pha1337.featurify.domain.FeatureAuditLog
import ru.a1pha1337.featurify.domain.FeatureEnumOption
import ru.a1pha1337.featurify.domain.FeatureStatus
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.domain.Tenant
import ru.a1pha1337.featurify.repository.FeatureAuditLogRepository
import ru.a1pha1337.featurify.repository.FeatureEnumOptionRepository
import ru.a1pha1337.featurify.repository.FeatureRepository
import ru.a1pha1337.featurify.repository.TenantRepository
import ru.a1pha1337.featurify.security.ActorProvider
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class FeatureToggleService(
    private val tenantRepository: TenantRepository,
    private val featureRepository: FeatureRepository,
    private val optionRepository: FeatureEnumOptionRepository,
    private val auditRepository: FeatureAuditLogRepository,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
) {
    @Transactional
    fun createTenant(request: CreateTenantRequest): TenantResponse {
        val now = clock.instant()
        if (request.defaultTenant) tenantRepository.clearDefault(now)
        return tenantRepository.save(
            Tenant(
                key = request.key,
                displayName = request.displayName,
                createdAt = now,
                updatedAt = now,
                defaultTenant = request.defaultTenant,
            ),
        ).toResponse()
    }

    @Transactional(readOnly = true)
    fun listTenants(): List<TenantResponse> = tenantRepository.findAllByOrderByKey().map { it.toResponse() }

    @Transactional
    fun createFeature(tenantKey: String?, request: CreateFeatureRequest): AdminFeatureResponse {
        val tenant = requireTenant(tenantKey)
        val type = request.type ?: throw validation("type", "must not be null")
        val groupKey = normalizeGroup(request.group)
        validateCreation(request, type)
        val now = clock.instant()
        val saved = featureRepository.save(
            Feature(
                tenantId = tenant.id!!,
                key = request.key,
                type = type,
                groupKey = groupKey,
                booleanValue = request.booleanValue,
                enumValue = request.enumValue,
                description = request.description,
                createdAt = now,
                updatedAt = now,
            ),
        )
        if (type == FeatureType.ENUM) {
            val featureId = checkNotNull(saved.id)
            optionRepository.saveAll(
                request.enumOptions.mapIndexed { index, value ->
                    FeatureEnumOption(featureId = featureId, value = value, sortOrder = index)
                },
            )
        }
        return saved.toAdminResponse(optionsFor(saved))
    }

    @Transactional(readOnly = true)
    fun listActive(tenantKey: String?, pageable: Pageable, query: String?): Page<FeatureResponse> {
        val tenant = requireActiveTenant(tenantKey)
        val normalizedQuery = normalizeQuery(query)
        val features = if (normalizedQuery == null) {
            featureRepository.findAllByTenantIdAndStatus(tenant.id!!, FeatureStatus.ACTIVE, pageable)
        } else {
            featureRepository.findAllByTenantIdAndStatusAndKeyContaining(
                tenant.id!!,
                FeatureStatus.ACTIVE,
                normalizedQuery,
                pageable,
            )
        }
        return features.map { it.toPublicResponse(optionsFor(it)) }
    }

    @Transactional(readOnly = true)
    fun getActive(tenantKey: String?, key: String, group: String?): FeatureResponse {
        val tenant = requireActiveTenant(tenantKey)
        val feature = requireActiveFeature(tenant.id!!, key, normalizeGroup(group))
        return feature.toPublicResponse(optionsFor(feature))
    }

    @Transactional(readOnly = true)
    fun resolve(tenantKey: String?, keys: List<String>, group: String?): ResolveResponse {
        if (keys.isEmpty() || keys.any { it.isBlank() }) {
            throw validation("keys", "must contain at least one non-blank key")
        }
        val tenant = requireActiveTenant(tenantKey)
        val groupKey = normalizeGroup(group)
        val resolved = LinkedHashMap<String, FeatureResponse>()
        keys.distinct().forEach { key ->
            val feature = requireActiveFeature(tenant.id!!, key, groupKey)
            resolved[key] = feature.toPublicResponse(optionsFor(feature))
        }
        return ResolveResponse(resolved)
    }

    @Transactional(readOnly = true)
    fun listForAdmin(
        tenantKey: String?,
        pageable: Pageable,
        statuses: Set<FeatureStatus>,
        query: String?,
    ): Page<AdminFeatureResponse> {
        val tenant = requireTenant(tenantKey)
        if (statuses.isEmpty()) return Page.empty(pageable)
        val normalizedQuery = normalizeQuery(query)
        val features = if (normalizedQuery == null) {
            featureRepository.findAllByTenantIdAndStatusIn(tenant.id!!, statuses, pageable)
        } else {
            featureRepository.findAllByTenantIdAndStatusInAndKeyContaining(
                tenant.id!!,
                statuses,
                normalizedQuery,
                pageable,
            )
        }
        return features.map { it.toAdminResponse(optionsFor(it)) }
    }

    @Transactional
    fun patchFeature(
        tenantKey: String?,
        key: String,
        group: String?,
        request: PatchFeatureRequest,
    ): AdminFeatureResponse {
        val tenant = requireTenant(tenantKey)
        val groupKey = normalizeGroup(group)
        val current = requireFeature(tenant.id!!, key, groupKey)
        requireVersion(current, request.version)
        if (current.status == FeatureStatus.ARCHIVED) {
            throw ConflictException("Archived feature '${featureName(groupKey, key)}' cannot be changed")
        }
        validatePatch(current, request)

        val newBoolean = if (current.type == FeatureType.BOOLEAN) request.booleanValue ?: current.booleanValue else null
        val newEnum = if (current.type == FeatureType.ENUM) request.enumValue ?: current.enumValue else null
        if (current.type == FeatureType.ENUM && request.enumValue != null) {
            val allowed = optionsFor(current)
            if (request.enumValue !in allowed) throw validation("enumValue", "must be one of $allowed")
        }

        val changed = current.copy(
            booleanValue = newBoolean,
            enumValue = newEnum,
            description = request.description ?: current.description,
            updatedAt = clock.instant(),
        )
        val saved = saveWithConflict(changed)
        if (current.valueAsString() != saved.valueAsString()) {
            audit(tenant, saved, AuditOperation.VALUE_CHANGED, current.valueAsString(), saved.valueAsString())
        }
        return saved.toAdminResponse(optionsFor(saved))
    }

    @Transactional
    fun archive(tenantKey: String?, key: String, group: String?, version: Long?): AdminFeatureResponse {
        val tenant = requireTenant(tenantKey)
        val groupKey = normalizeGroup(group)
        val current = requireFeature(tenant.id!!, key, groupKey)
        requireVersion(current, version)
        if (current.status == FeatureStatus.ARCHIVED) {
            throw ConflictException("Feature '${featureName(groupKey, key)}' is already archived")
        }
        val saved = saveWithConflict(
            current.copy(status = FeatureStatus.ARCHIVED, updatedAt = clock.instant()),
        )
        audit(tenant, saved, AuditOperation.ARCHIVED, FeatureStatus.ACTIVE.name, FeatureStatus.ARCHIVED.name)
        return saved.toAdminResponse(optionsFor(saved))
    }

    @Transactional(readOnly = true)
    fun history(tenantKey: String?, key: String, group: String?): List<AuditLogResponse> {
        val tenant = requireTenant(tenantKey)
        val groupKey = normalizeGroup(group)
        requireFeature(tenant.id!!, key, groupKey)
        val entries = if (groupKey == null) {
            auditRepository.findAllByTenantIdAndFeatureGroupIsNullAndFeatureKeyOrderByChangedAtDesc(tenant.id, key)
        } else {
            auditRepository.findAllByTenantIdAndFeatureGroupAndFeatureKeyOrderByChangedAtDesc(
                tenant.id,
                groupKey,
                key,
            )
        }
        return entries.map {
            AuditLogResponse(
                group = it.featureGroup,
                operation = it.operation,
                oldValue = it.oldValue,
                newValue = it.newValue,
                changedBy = it.changedBy,
                changedAt = it.changedAt,
            )
        }
    }

    private fun validateCreation(request: CreateFeatureRequest, type: FeatureType) {
        when (type) {
            FeatureType.BOOLEAN -> {
                if (request.booleanValue == null) throw validation("booleanValue", "is required for BOOLEAN")
                if (request.enumValue != null) throw validation("enumValue", "is only allowed for ENUM")
                if (request.enumOptions.isNotEmpty()) throw validation("enumOptions", "is only allowed for ENUM")
            }
            FeatureType.ENUM -> {
                if (request.booleanValue != null) throw validation("booleanValue", "is only allowed for BOOLEAN")
                if (request.enumValue.isNullOrBlank()) throw validation("enumValue", "is required for ENUM")
                if (request.enumOptions.isEmpty()) throw validation("enumOptions", "must not be empty for ENUM")
                if (request.enumOptions.any { it.isBlank() }) throw validation("enumOptions", "must not contain blank values")
                if (request.enumOptions.distinct().size != request.enumOptions.size) {
                    throw validation("enumOptions", "must not contain duplicates")
                }
                if (request.enumValue !in request.enumOptions) {
                    throw validation("enumValue", "must be present in enumOptions")
                }
            }
        }
    }

    private fun validatePatch(current: Feature, request: PatchFeatureRequest) {
        if (request.description == null && request.booleanValue == null && request.enumValue == null) {
            throw validation("request", "must change value and/or description")
        }
        if (current.type == FeatureType.BOOLEAN && request.enumValue != null) {
            throw validation("enumValue", "is only allowed for ENUM")
        }
        if (current.type == FeatureType.ENUM && request.booleanValue != null) {
            throw validation("booleanValue", "is only allowed for BOOLEAN")
        }
    }

    private fun requireVersion(feature: Feature, requested: Long?) {
        if (requested == null) throw validation("version", "must not be null")
        if (feature.version != requested) {
            throw ConflictException("Feature was changed by another request; current version is ${feature.version}")
        }
    }

    private fun saveWithConflict(feature: Feature): Feature = try {
        featureRepository.save(feature)
    } catch (exception: OptimisticLockingFailureException) {
        throw ConflictException("Feature was changed by another request")
    }

    private fun audit(
        tenant: Tenant,
        feature: Feature,
        operation: AuditOperation,
        oldValue: String?,
        newValue: String?,
    ) {
        auditRepository.save(
            FeatureAuditLog(
                tenantId = tenant.id!!,
                tenantKey = tenant.key,
                featureGroup = feature.groupKey,
                featureKey = feature.key,
                operation = operation,
                oldValue = oldValue,
                newValue = newValue,
                changedBy = actorProvider.currentUsername(),
                changedAt = clock.instant(),
            ),
        )
    }

    private fun requireTenant(tenantKey: String?): Tenant = if (tenantKey == null) {
        tenantRepository.findByDefaultTenantTrue()
            ?: throw NotFoundException("Default tenant was not found")
    } else {
        tenantRepository.findByKey(tenantKey)
            ?: throw NotFoundException("Tenant '$tenantKey' was not found")
    }

    private fun requireActiveTenant(tenantKey: String?): Tenant = requireTenant(tenantKey).also {
        if (!it.active) throw NotFoundException("Tenant '${tenantKey ?: it.key}' was not found")
    }

    private fun requireFeature(tenantId: UUID, key: String, group: String?): Feature {
        val feature = if (group == null) {
            featureRepository.findByTenantIdAndGroupKeyIsNullAndKey(tenantId, key)
        } else {
            featureRepository.findByTenantIdAndGroupKeyAndKey(tenantId, group, key)
        }
        return feature ?: throw NotFoundException("Feature '${featureName(group, key)}' was not found")
    }

    private fun requireActiveFeature(tenantId: UUID, key: String, group: String?): Feature =
        requireFeature(tenantId, key, group).also {
            if (it.status != FeatureStatus.ACTIVE) {
                throw NotFoundException("Feature '${featureName(group, key)}' was not found")
            }
        }

    private fun normalizeGroup(group: String?): String? {
        val normalized = group?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (normalized.length > 255 || !Regex(ValidationPatterns.FEATURE_GROUP).matches(normalized)) {
            throw validation("group", "must be a lowercase key containing letters, digits, dots or hyphens")
        }
        return normalized
    }

    private fun featureName(group: String?, key: String) = group?.let { "$it/$key" } ?: key

    private fun optionsFor(feature: Feature): List<String> = if (feature.type == FeatureType.ENUM) {
        optionRepository.findAllByFeatureIdOrderBySortOrder(feature.id!!).map { it.value }
    } else {
        emptyList()
    }

    private fun normalizeQuery(query: String?): String? {
        val normalized = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (normalized.length < 3) throw validation("query", "must contain at least 3 characters")
        return normalized
    }

    private fun Tenant.toResponse() = TenantResponse(id!!, key, displayName, active, createdAt, updatedAt, defaultTenant)

    private fun Feature.toPublicResponse(options: List<String>) = FeatureResponse(
        group = groupKey,
        key = key,
        type = type,
        value = if (type == FeatureType.BOOLEAN) booleanValue!! else enumValue!!,
        enumOptions = options.takeIf { type == FeatureType.ENUM },
        version = version ?: 0,
    )

    private fun Feature.toAdminResponse(options: List<String>) = AdminFeatureResponse(
        group = groupKey,
        key = key,
        type = type,
        value = if (type == FeatureType.BOOLEAN) booleanValue!! else enumValue!!,
        enumOptions = options.takeIf { type == FeatureType.ENUM },
        description = description,
        status = status,
        version = version ?: 0,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun validation(field: String, message: String) =
        DomainValidationException("Request validation failed", listOf(field to message))
}
