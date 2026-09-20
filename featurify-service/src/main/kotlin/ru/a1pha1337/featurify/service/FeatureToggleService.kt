package ru.a1pha1337.featurify.service

import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.a1pha1337.featurify.domain.AuditOperation
import ru.a1pha1337.featurify.domain.BooleanValue
import ru.a1pha1337.featurify.domain.EnumValue
import ru.a1pha1337.featurify.domain.Feature
import ru.a1pha1337.featurify.domain.FeatureAuditLog
import ru.a1pha1337.featurify.domain.FeatureGroup
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.domain.Namespace
import ru.a1pha1337.featurify.domain.PayloadValue
import ru.a1pha1337.featurify.domain.VectorValue
import ru.a1pha1337.featurify.dto.AdminFeatureResponse
import ru.a1pha1337.featurify.dto.AuditLogResponse
import ru.a1pha1337.featurify.dto.CreateFeatureGroupRequest
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreateNamespaceRequest
import ru.a1pha1337.featurify.dto.FeatureGroupResponse
import ru.a1pha1337.featurify.dto.FeatureResponse
import ru.a1pha1337.featurify.dto.MoveFeatureRequest
import ru.a1pha1337.featurify.dto.NamespaceResponse
import ru.a1pha1337.featurify.dto.PatchFeatureRequest
import ru.a1pha1337.featurify.dto.ResolveResponse
import ru.a1pha1337.featurify.dto.ValidationPatterns
import ru.a1pha1337.featurify.dto.VectorElementResponse
import ru.a1pha1337.featurify.repository.FeatureAuditLogRepository
import ru.a1pha1337.featurify.repository.FeatureGroupRepository
import ru.a1pha1337.featurify.repository.FeatureRepository
import ru.a1pha1337.featurify.repository.NamespaceRepository
import ru.a1pha1337.featurify.security.ActorProvider
import java.time.Clock
import java.util.UUID

@Service
class FeatureToggleService(
    private val namespaceRepository: NamespaceRepository,
    private val featureRepository: FeatureRepository,
    private val groupRepository: FeatureGroupRepository,
    private val auditRepository: FeatureAuditLogRepository,
    private val actorProvider: ActorProvider,
    private val clock: Clock,
    private val manifestGuard: ManifestGuard,
) {
    @Transactional
    fun createNamespace(request: CreateNamespaceRequest): NamespaceResponse {
        val key = normalizeNamespaceKey(request.key)
        manifestGuard.lock(key)
        val now = clock.instant()
        if (key == "default") throw validation("key", "is reserved for the system Default namespace")
        return namespaceRepository
            .save(
                Namespace(
                    key = key,
                    displayName = request.displayName,
                    createdAt = now,
                    updatedAt = now,
                    defaultNamespace = false,
                ),
            ).toResponse()
    }

    @Transactional(readOnly = true)
    fun listNamespaces(): List<NamespaceResponse> = namespaceRepository.findAllByOrderByKey().map { it.toResponse() }

    @Transactional
    fun createGroup(
        namespaceKey: String?,
        request: CreateFeatureGroupRequest,
    ): FeatureGroupResponse {
        manifestGuard.lock(namespaceKey)
        val namespace = requireNamespace(namespaceKey)
        manifestGuard.create(checkNotNull(namespace.id))
        val key = normalizeGroup(request.key) ?: throw validation("key", "must not be blank")
        if (request.displayName.isBlank() || request.displayName.length > 255) {
            throw validation("displayName", "must contain between 1 and 255 characters")
        }
        if (groupRepository.findByNamespaceIdAndKey(namespace.id!!, key) != null) {
            throw ConflictException("Group '$key' already exists in this namespace. Choose another key.")
        }
        val now = clock.instant()
        return groupRepository
            .save(
                FeatureGroup(
                    namespaceId = namespace.id!!,
                    key = key,
                    displayName = request.displayName.trim(),
                    createdAt = now,
                    updatedAt = now,
                ),
            ).toResponse()
    }

    @Transactional(readOnly = true)
    fun listGroups(namespaceKey: String?): List<FeatureGroupResponse> {
        val namespace = requireNamespace(namespaceKey)
        return groupRepository.findAllByNamespaceIdOrderByKey(namespace.id!!).map { it.toResponse() }
    }

    @Transactional
    fun deleteNamespace(namespaceKey: String) {
        manifestGuard.lock(namespaceKey)
        val namespace = requireNamespace(namespaceKey)
        manifestGuard.deleteNamespace(checkNotNull(namespace.id))
        if (namespace.defaultNamespace || namespace.key == "default") {
            throw ConflictException("The system Default namespace cannot be deleted")
        }
        namespaceRepository.delete(namespace)
    }

    @Transactional
    fun deleteGroup(
        namespaceKey: String?,
        groupKey: String,
        version: Long?,
    ) {
        manifestGuard.lock(namespaceKey)
        val namespace = requireNamespace(namespaceKey)
        val group = requireGroup(namespace.id!!, groupKey)
        manifestGuard.deleteGroup(checkNotNull(namespace.id), checkNotNull(group.id))
        requireVersion(group.version, version)
        try {
            groupRepository.delete(group)
        } catch (exception: OptimisticLockingFailureException) {
            throw ConflictException("Group was changed by another request")
        }
    }

    @Transactional
    fun moveFeature(
        namespaceKey: String?,
        key: String,
        currentGroup: String?,
        request: MoveFeatureRequest,
    ): AdminFeatureResponse {
        manifestGuard.lock(namespaceKey)
        val namespace = requireNamespace(namespaceKey)
        val currentGroupKey = normalizeGroup(currentGroup)
        val current = requireFeature(namespace.id!!, key, currentGroupKey)
        requireVersion(current, request.version)
        val targetGroupKey = normalizeGroup(request.targetGroup)
        val targetGroup = targetGroupKey?.let { requireGroup(namespace.id!!, it) }
        if (targetGroup?.id == current.groupId) return current.toAdminResponse(targetGroupKey, optionsFor(current))
        manifestGuard.structure(current)
        val existing =
            if (targetGroup == null) {
                featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespace.id, key)
            } else {
                featureRepository.findByNamespaceIdAndGroupIdAndKey(namespace.id, targetGroup.id!!, key)
            }
        if (existing != null) {
            throw ConflictException("Feature '$key' already exists in ${targetGroupKey ?: "Global"}. Choose another group.")
        }
        val saved = saveWithConflict(current.copy(groupId = targetGroup?.id, updatedAt = clock.instant()))
        return saved.toAdminResponse(targetGroupKey, optionsFor(saved))
    }

    @Transactional
    fun createFeature(
        namespaceKey: String?,
        request: CreateFeatureRequest,
    ): AdminFeatureResponse {
        manifestGuard.lock(namespaceKey)
        val namespace = requireNamespace(namespaceKey)
        manifestGuard.create(checkNotNull(namespace.id))
        val key = normalizeFeatureKey(request.key)
        val type = request.type ?: throw validation("type", "must not be null")
        val group = normalizeGroup(request.group)?.let { requireGroup(namespace.id!!, it) }
        validateCreation(request, type)
        val now = clock.instant()
        val saved =
            featureRepository.save(
                Feature(
                    namespaceId = namespace.id!!,
                    key = key,
                    value =
                        when (type) {
                            FeatureType.BOOLEAN -> BooleanValue(request.booleanValue!!)
                            FeatureType.ENUM -> EnumValue(request.enumValue!!, request.enumOptions)
                            FeatureType.VECTOR -> VectorValue(request.vectorValues)
                            FeatureType.PAYLOAD -> PayloadValue(checkNotNull(request.payloadValue))
                        },
                    groupId = group?.id,
                    description = request.description,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        return saved.toAdminResponse(group?.key, optionsFor(saved))
    }

    @Transactional(readOnly = true)
    fun listFeatures(
        namespaceKey: String?,
        pageable: Pageable,
        query: String?,
    ): Page<FeatureResponse> {
        val namespace = requireActiveNamespace(namespaceKey)
        return listFeaturesInNamespace(namespace.id!!, pageable, query)
    }

    @Transactional(readOnly = true)
    fun listFeaturesInNamespace(
        namespaceId: UUID,
        pageable: Pageable,
        query: String?,
    ): Page<FeatureResponse> {
        val normalizedQuery = normalizeQuery(query)
        val features =
            if (normalizedQuery == null) {
                featureRepository.findAllByNamespaceId(namespaceId, pageable)
            } else {
                featureRepository.findAllByNamespaceIdAndKeyContaining(
                    namespaceId,
                    normalizedQuery,
                    pageable,
                )
            }
        return features.map { it.toPublicResponse(groupKeyFor(it), optionsFor(it)) }
    }

    @Transactional(readOnly = true)
    fun getFeature(
        namespaceKey: String?,
        key: String,
        group: String?,
    ): FeatureResponse {
        val namespace = requireActiveNamespace(namespaceKey)
        return getFeatureInNamespace(namespace.id!!, key, group)
    }

    @Transactional(readOnly = true)
    fun getFeatureInNamespace(
        namespaceId: UUID,
        key: String,
        group: String?,
    ): FeatureResponse {
        val feature = requireFeature(namespaceId, key, normalizeGroup(group))
        return feature.toPublicResponse(groupKeyFor(feature), optionsFor(feature))
    }

    @Transactional(readOnly = true)
    fun getVectorElementInNamespace(
        namespaceId: UUID,
        key: String,
        group: String?,
        element: String,
    ): VectorElementResponse {
        if (element.isBlank() || element.length > 255) throw validation("element", "must contain 1-255 characters")
        val feature = requireFeature(namespaceId, key, normalizeGroup(group))
        if (feature.type != FeatureType.VECTOR) throw ConflictException("Feature is not VECTOR")
        val value = (feature.value as VectorValue).elements[element] ?: throw NotFoundException("Vector element was not found")
        return VectorElementResponse(groupKeyFor(feature), feature.key, element, value, feature.version ?: 0)
    }

    @Transactional(readOnly = true)
    fun resolve(
        namespaceKey: String?,
        keys: List<String>,
        group: String?,
    ): ResolveResponse = resolveInNamespace(requireActiveNamespace(namespaceKey).id!!, keys, group)

    @Transactional(readOnly = true)
    fun resolveInNamespace(
        namespaceId: UUID,
        keys: List<String>,
        group: String?,
    ): ResolveResponse {
        if (keys.isEmpty() || keys.any { it.isBlank() }) {
            throw validation("keys", "must contain at least one non-blank key")
        }
        val groupKey = normalizeGroup(group)
        val resolved = LinkedHashMap<String, FeatureResponse>()
        keys.distinct().forEach { key ->
            val feature = requireFeature(namespaceId, key, groupKey)
            resolved[key] = feature.toPublicResponse(groupKeyFor(feature), optionsFor(feature))
        }
        return ResolveResponse(resolved)
    }

    @Transactional(readOnly = true)
    fun listForAdmin(
        namespaceKey: String?,
        pageable: Pageable,
        query: String?,
        group: String? = null,
        globalOnly: Boolean = false,
    ): Page<AdminFeatureResponse> {
        val namespace = requireNamespace(namespaceKey)
        val normalizedQuery = normalizeQuery(query)
        val groupId = normalizeGroup(group)?.let { requireGroup(namespace.id!!, it).id!! }
        val features =
            if (groupId != null) {
                if (normalizedQuery == null) {
                    featureRepository.findAllByNamespaceIdAndGroupId(namespace.id!!, groupId, pageable)
                } else {
                    featureRepository.findAllByNamespaceIdAndGroupIdAndKeyContaining(
                        namespace.id!!,
                        groupId,
                        normalizedQuery,
                        pageable,
                    )
                }
            } else if (globalOnly) {
                if (normalizedQuery == null) {
                    featureRepository.findAllByNamespaceIdAndGroupIdIsNull(namespace.id!!, pageable)
                } else {
                    featureRepository.findAllByNamespaceIdAndGroupIdIsNullAndKeyContaining(
                        namespace.id!!,
                        normalizedQuery,
                        pageable,
                    )
                }
            } else if (normalizedQuery == null) {
                featureRepository.findAllByNamespaceId(namespace.id!!, pageable)
            } else {
                featureRepository.findAllByNamespaceIdAndKeyContaining(
                    namespace.id!!,
                    normalizedQuery,
                    pageable,
                )
            }
        return features.map { it.toAdminResponse(groupKeyFor(it), optionsFor(it)) }
    }

    @Transactional
    fun editFeature(
        namespaceKey: String?,
        key: String,
        group: String?,
        targetGroup: String?,
        request: PatchFeatureRequest,
    ): AdminFeatureResponse {
        val saved = patchFeature(namespaceKey, key, group, request)
        return moveFeature(namespaceKey, key, group, MoveFeatureRequest(saved.version, targetGroup))
    }

    @Transactional
    fun patchFeature(
        namespaceKey: String?,
        key: String,
        group: String?,
        request: PatchFeatureRequest,
    ): AdminFeatureResponse {
        manifestGuard.lock(namespaceKey)
        val namespace = requireNamespace(namespaceKey)
        val groupKey = normalizeGroup(group)
        val current = requireFeature(namespace.id!!, key, groupKey)
        requireVersion(current, request.version)
        manifestGuard.patch(current, request)
        validatePatch(current, request)

        val newValue =
            when (val value = current.value) {
                is BooleanValue -> value.copy(enabled = request.booleanValue ?: value.enabled)
                is EnumValue -> {
                    val selected = request.enumValue ?: value.selected
                    if (selected !in value.options) throw validation("enumValue", "must be one of ${value.options}")
                    value.copy(selected = selected)
                }
                is VectorValue -> value.copy(elements = value.elements + (request.vectorValues ?: emptyMap()))
                is PayloadValue -> request.payloadValue?.let { PayloadValue(it) } ?: value
            }
        val changed =
            current.copy(
                value = newValue,
                description = request.description ?: current.description,
                updatedAt = clock.instant(),
            )
        val saved = saveWithConflict(changed)
        if (current.valueAsString() != saved.valueAsString()) {
            audit(namespace, saved, AuditOperation.VALUE_CHANGED, current.valueAsString(), saved.valueAsString())
        }
        return saved.toAdminResponse(groupKeyFor(saved), optionsFor(saved))
    }

    @Transactional
    fun deleteFeature(
        namespaceKey: String?,
        key: String,
        group: String?,
        version: Long?,
    ) {
        manifestGuard.lock(namespaceKey)
        val namespace = requireNamespace(namespaceKey)
        val current = requireFeature(namespace.id!!, key, normalizeGroup(group))
        requireVersion(current, version)
        manifestGuard.structure(current)
        try {
            featureRepository.delete(current)
        } catch (exception: OptimisticLockingFailureException) {
            throw ConflictException("Feature was changed by another request")
        }
    }

    @Transactional(readOnly = true)
    fun history(
        namespaceKey: String?,
        key: String,
        group: String?,
    ): List<AuditLogResponse> {
        val namespace = requireNamespace(namespaceKey)
        val groupKey = normalizeGroup(group)
        requireFeature(namespace.id!!, key, groupKey)
        val entries =
            if (groupKey == null) {
                auditRepository.findAllByNamespaceIdAndFeatureGroupIsNullAndFeatureKeyOrderByChangedAtDesc(
                    namespace.id,
                    key,
                )
            } else {
                auditRepository.findAllByNamespaceIdAndFeatureGroupAndFeatureKeyOrderByChangedAtDesc(
                    namespace.id,
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

    internal fun validateCreation(
        request: CreateFeatureRequest,
        type: FeatureType,
    ) {
        if (type != FeatureType.PAYLOAD && request.payloadValue != null) {
            throw validation("payloadValue", "is only allowed for PAYLOAD")
        }
        if (type != FeatureType.VECTOR && request.vectorValues.isNotEmpty()) {
            throw validation("vectorValues", "is only allowed for VECTOR")
        }
        when (type) {
            FeatureType.PAYLOAD -> {
                if (request.booleanValue != null) throw validation("booleanValue", "is only allowed for BOOLEAN")
                if (request.enumValue != null) throw validation("enumValue", "is only allowed for ENUM")
                if (request.enumOptions.isNotEmpty()) throw validation("enumOptions", "is only allowed for ENUM")
                validatePayload(request.payloadValue ?: throw validation("payloadValue", "is required for PAYLOAD"))
            }
            FeatureType.VECTOR -> {
                if (request.booleanValue != null) throw validation("booleanValue", "is only allowed for BOOLEAN")
                if (request.enumValue != null) throw validation("enumValue", "is only allowed for ENUM")
                if (request.enumOptions.isNotEmpty()) throw validation("enumOptions", "is only allowed for ENUM")
                validateVectorValues(request.vectorValues)
            }

            FeatureType.BOOLEAN -> {
                if (request.booleanValue == null) throw validation("booleanValue", "is required for BOOLEAN")
                if (request.enumValue != null) throw validation("enumValue", "is only allowed for ENUM")
                if (request.enumOptions.isNotEmpty()) throw validation("enumOptions", "is only allowed for ENUM")
            }

            FeatureType.ENUM -> {
                if (request.booleanValue != null) throw validation("booleanValue", "is only allowed for BOOLEAN")
                if (request.enumValue.isNullOrBlank()) throw validation("enumValue", "is required for ENUM")
                if (request.enumOptions.isEmpty()) throw validation("enumOptions", "must not be empty for ENUM")
                if (request.enumOptions.any { it.isBlank() }) {
                    throw validation(
                        "enumOptions",
                        "must not contain blank values",
                    )
                }
                if (request.enumOptions.distinct().size != request.enumOptions.size) {
                    throw validation("enumOptions", "must not contain duplicates")
                }
                if (request.enumValue !in request.enumOptions) {
                    throw validation("enumValue", "must be present in enumOptions")
                }
            }
        }
    }

    private fun validateVectorValues(values: Map<String, Boolean>) {
        if (values.size !in 1..100) throw validation("vectorValues", "must contain between 1 and 100 elements")
        if (values.keys.any { it.isBlank() || it.length > 255 || it != it.trim() }) {
            throw validation("vectorValues", "element names must contain 1-255 characters without surrounding whitespace")
        }
    }

    private fun validatePayload(json: String) {
        try {
            PayloadValue(json)
        } catch (exception: IllegalArgumentException) {
            throw validation("payloadValue", "must be a valid JSON document of at most 65536 characters")
        } catch (exception: tools.jackson.core.JacksonException) {
            throw validation("payloadValue", "must be a valid JSON document of at most 65536 characters")
        }
    }

    private fun validatePatch(
        current: Feature,
        request: PatchFeatureRequest,
    ) {
        if (request.description == null &&
            request.booleanValue == null &&
            request.enumValue == null &&
            request.vectorValues == null &&
            request.payloadValue == null
        ) {
            throw validation("request", "must change value and/or description")
        }
        request.payloadValue?.let {
            if (current.type != FeatureType.PAYLOAD) throw validation("payloadValue", "is only allowed for PAYLOAD")
            validatePayload(it)
        }
        request.vectorValues?.let { values ->
            if (current.type != FeatureType.VECTOR) throw validation("vectorValues", "is only allowed for VECTOR")
            validateVectorValues(values)
            if (values.keys.any { it !in (current.value as VectorValue).elements }) {
                throw validation("vectorValues", "must contain only existing elements")
            }
        }
        if (current.type != FeatureType.ENUM && request.enumValue != null) {
            throw validation("enumValue", "is only allowed for ENUM")
        }
        if (current.type != FeatureType.BOOLEAN && request.booleanValue != null) {
            throw validation("booleanValue", "is only allowed for BOOLEAN")
        }
    }

    private fun requireVersion(
        feature: Feature,
        requested: Long?,
    ) {
        if (requested == null) throw validation("version", "must not be null")
        if (feature.version != requested) {
            throw ConflictException("Feature was changed by another request; current version is ${feature.version}")
        }
    }

    private fun saveWithConflict(feature: Feature): Feature =
        try {
            featureRepository.save(feature)
        } catch (exception: OptimisticLockingFailureException) {
            throw ConflictException("Feature was changed by another request")
        }

    private fun audit(
        namespace: Namespace,
        feature: Feature,
        operation: AuditOperation,
        oldValue: String?,
        newValue: String?,
    ) {
        auditRepository.save(
            FeatureAuditLog(
                namespaceId = namespace.id!!,
                featureId = feature.id!!,
                namespaceKey = namespace.key,
                featureGroup = groupKeyFor(feature),
                featureKey = feature.key,
                operation = operation,
                oldValue = oldValue,
                newValue = newValue,
                changedBy = actorProvider.currentUsername(),
                changedAt = clock.instant(),
            ),
        )
    }

    private fun requireNamespace(namespaceKey: String?): Namespace =
        if (namespaceKey == null) {
            namespaceRepository.findByDefaultNamespaceTrue()
                ?: throw NotFoundException("Default namespace was not found")
        } else {
            namespaceRepository.findByKey(namespaceKey)
                ?: throw NotFoundException("Namespace '$namespaceKey' was not found")
        }

    private fun requireActiveNamespace(namespaceKey: String?): Namespace =
        requireNamespace(namespaceKey).also {
            if (!it.active) throw NotFoundException("Namespace '${namespaceKey ?: it.key}' was not found")
        }

    private fun requireFeature(
        namespaceId: UUID,
        key: String,
        group: String?,
    ): Feature {
        val groupId = group?.let { requireGroup(namespaceId, it).id!! }
        val feature =
            if (groupId == null) {
                featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, key)
            } else {
                featureRepository.findByNamespaceIdAndGroupIdAndKey(namespaceId, groupId, key)
            }
        return feature ?: throw NotFoundException("Feature '${featureName(group, key)}' was not found")
    }

    private fun normalizeGroup(group: String?): String? {
        val normalized = group?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (normalized.length > 255 || !Regex(ValidationPatterns.FEATURE_GROUP).matches(normalized)) {
            throw validation("group", "must start with a letter and contain only letters, digits, dots or hyphens")
        }
        return normalized
    }

    private fun normalizeNamespaceKey(key: String): String {
        val normalized = key.trim()
        if (normalized.length !in 1..255 || !Regex(ValidationPatterns.NAMESPACE_KEY).matches(normalized)) {
            throw validation("key", "must start with a letter and contain only letters, digits, dots or hyphens")
        }
        return normalized
    }

    private fun normalizeFeatureKey(key: String): String {
        val normalized = key.trim()
        if (normalized.length !in 1..255 || !Regex(ValidationPatterns.FEATURE_KEY).matches(normalized)) {
            throw validation("key", "must start with a letter and contain only letters, digits, dots or hyphens")
        }
        return normalized
    }

    private fun featureName(
        group: String?,
        key: String,
    ) = group?.let { "$it/$key" } ?: key

    private fun requireGroup(
        namespaceId: UUID,
        key: String,
    ): FeatureGroup =
        groupRepository.findByNamespaceIdAndKey(namespaceId, key)
            ?: throw NotFoundException("Group '$key' was not found")

    private fun requireVersion(
        version: Long?,
        requested: Long?,
    ) {
        if (requested == null) throw validation("version", "must not be null")
        if (version != requested) {
            throw ConflictException("Resource was changed by another request; current version is $version")
        }
    }

    private fun groupKeyFor(feature: Feature): String? =
        feature.groupId?.let {
            groupRepository.findById(it).orElse(null)?.key
        }

    private fun optionsFor(feature: Feature): List<String> = (feature.value as? EnumValue)?.options ?: emptyList()

    private fun normalizeQuery(query: String?): String? {
        val normalized = query?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (normalized.length < 3) throw validation("query", "must contain at least 3 characters")
        return normalized
    }

    private fun Namespace.toResponse(): NamespaceResponse {
        val binding = manifestGuard.binding(checkNotNull(id))
        return NamespaceResponse(
            id,
            key,
            displayName,
            active,
            createdAt,
            updatedAt,
            defaultNamespace,
            managedBy = binding?.owner?.let { "${it.clusterId}/${it.kubernetesNamespace}/${it.name}" },
            exclusive = binding?.spec?.management?.ownershipPolicy == ru.a1pha1337.featurify.dto.OwnershipPolicy.Exclusive,
        )
    }

    private fun FeatureGroup.toResponse() =
        FeatureGroupResponse(
            id = id!!,
            key = key,
            displayName = displayName,
            version = version ?: 0,
            createdAt = createdAt,
            updatedAt = updatedAt,
            managed = manifestGuard.binding(namespaceId)?.groups?.contains(id) == true,
        )

    private fun Feature.toPublicResponse(
        group: String?,
        options: List<String>,
    ) = FeatureResponse(
        group = group,
        key = key,
        type = type,
        value = value.publicValue(),
        enumOptions = options.takeIf { type == FeatureType.ENUM },
        version = version ?: 0,
    )

    private fun Feature.toAdminResponse(
        group: String?,
        options: List<String>,
    ) = AdminFeatureResponse(
        group = group,
        key = key,
        type = type,
        value = value.publicValue(),
        enumOptions = options.takeIf { type == FeatureType.ENUM },
        description = description,
        version = version ?: 0,
        createdAt = createdAt,
        updatedAt = updatedAt,
        managed = manifestGuard.binding(namespaceId)?.features?.contains(id) == true,
        valueManaged =
            manifestGuard.binding(namespaceId)?.let {
                id in it.features && it.spec?.management?.valuePolicy == ru.a1pha1337.featurify.dto.ValuePolicy.Managed
            } == true,
    )

    private fun validation(
        field: String,
        message: String,
    ) = DomainValidationException("Request validation failed", listOf(field to message))
}
