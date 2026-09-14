package ru.a1pha1337.featurify.service

import jakarta.validation.Validator
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
import ru.a1pha1337.featurify.domain.FeatureValue
import ru.a1pha1337.featurify.domain.Namespace
import ru.a1pha1337.featurify.domain.VectorValue
import ru.a1pha1337.featurify.dto.ApplyNamespaceManifestRequest
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreateNamespaceRequest
import ru.a1pha1337.featurify.dto.DeletionPolicy
import ru.a1pha1337.featurify.dto.ManifestApplyResponse
import ru.a1pha1337.featurify.dto.ManifestOwner
import ru.a1pha1337.featurify.dto.NamespaceManifest
import ru.a1pha1337.featurify.dto.OwnershipPolicy
import ru.a1pha1337.featurify.dto.ReleaseNamespaceManifestRequest
import ru.a1pha1337.featurify.dto.ValidationPatterns
import ru.a1pha1337.featurify.dto.ValuePolicy
import ru.a1pha1337.featurify.repository.FeatureAuditLogRepository
import ru.a1pha1337.featurify.repository.FeatureGroupRepository
import ru.a1pha1337.featurify.repository.FeatureRepository
import ru.a1pha1337.featurify.repository.ManifestBinding
import ru.a1pha1337.featurify.repository.ManifestRepository
import ru.a1pha1337.featurify.repository.NamespaceRepository
import ru.a1pha1337.featurify.security.ActorProvider
import java.time.Clock
import java.util.UUID

@Service
class NamespaceManifestService(
    private val manifests: ManifestRepository,
    private val namespaces: NamespaceRepository,
    private val groups: FeatureGroupRepository,
    private val features: FeatureRepository,
    private val audit: FeatureAuditLogRepository,
    private val featureService: FeatureToggleService,
    private val validator: Validator,
    private val actor: ActorProvider,
    private val clock: Clock,
) {
    @Transactional
    fun apply(
        key: String,
        request: ApplyNamespaceManifestRequest,
        principal: String,
    ): ManifestApplyResponse {
        validate(request.spec)
        if (request.spec.key != key) invalid("key", "must match the URL")
        val previous = lockOwner(key, request.owner, request.generation, principal)
        if (previous?.released == true) conflict("This CR has been released; use a new CR UID and explicit adoption")
        if (previous != null) {
            if (previous.generation == request.generation && previous.spec != request.spec) conflict("Generation already has another spec")
            if (previous.spec?.management?.ownershipPolicy != request.spec.management.ownershipPolicy) {
                conflict("ownershipPolicy is immutable; release and explicitly adopt with a new CR")
            }
        }
        var namespace = namespaces.findByKey(key)
        val existing = namespace?.id?.let(manifests::byNamespace)
        if (existing != null && existing.id != previous?.id) conflict("Namespace belongs to another CR")
        val adopt = request.spec.management.adoptExisting
        if (namespace != null && previous == null && !adopt) conflict("Namespace exists; explicit adoptExisting is required")
        val now = clock.instant()
        var changed = false
        if (namespace == null) {
            namespace = namespaces.save(Namespace(key = key, displayName = request.spec.displayName, createdAt = now, updatedAt = now))
            changed = true
        } else if (namespace.displayName != request.spec.displayName) {
            namespace = namespaces.save(namespace.copy(displayName = request.spec.displayName, updatedAt = now))
            changed = true
        }
        val namespaceId = checkNotNull(namespace.id)
        val oldGroups = groups.findAllByNamespaceIdOrderByKey(namespaceId)
        val oldFeatures = features.findAllByNamespaceId(namespaceId, Pageable.unpaged()).content
        val desired = definitions(request.spec)
        val groupKeys = oldGroups.associate { it.id to it.key }
        val desiredGroups =
            request.spec.groups
                .map { it.key }
                .toSet()
        val ownedGroups = previous?.groups ?: emptySet()
        val ownedFeatures = previous?.features ?: emptySet()
        val removedGroups = oldGroups.filter { it.id in ownedGroups && it.key !in desiredGroups }
        if (removedGroups.any { group -> oldFeatures.any { it.groupId == group.id && it.id !in ownedFeatures } }) {
            conflict("A removed group contains manual features; move them before removing the group")
        }
        if (request.spec.management.ownershipPolicy == OwnershipPolicy.Exclusive) {
            if (oldGroups.any { it.id !in ownedGroups && it.key !in desiredGroups } ||
                oldFeatures.any { it.id !in ownedFeatures && (groupKeys[it.groupId] to it.key) !in desired }
            ) {
                conflict("Exclusive adoption requires all existing resources to be present in the manifest")
            }
        }
        val nextGroups = mutableSetOf<UUID>()
        val nextFeatures = mutableSetOf<UUID>()
        val targetGroups = mutableMapOf<String, UUID>()
        request.spec.groups.forEach { definition ->
            var group = oldGroups.find { it.key == definition.key }
            if (group != null &&
                group.id !in ownedGroups &&
                !adopt
            ) {
                conflict("Group '${definition.key}' is manual; explicit adoption required")
            }
            if (group == null) {
                group =
                    groups.save(
                        FeatureGroup(
                            namespaceId = namespaceId,
                            key = definition.key,
                            displayName = definition.displayName,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                changed = true
            } else if (group.displayName != definition.displayName) {
                group = groups.save(group.copy(displayName = definition.displayName, updatedAt = now))
                changed = true
            }
            val id = checkNotNull(group.id)
            nextGroups += id
            targetGroups[definition.key] = id
        }
        desired.forEach { (identity, definition) ->
            val current = oldFeatures.find { (groupKeys[it.groupId] to it.key) == identity }
            if (current != null &&
                current.id !in ownedFeatures &&
                !adopt
            ) {
                conflict("Feature '${identity.second}' is manual; explicit adoption required")
            }
            val value = desiredValue(definition, current?.value, request.spec.management.valuePolicy)
            val targetGroup = identity.first?.let { targetGroups.getValue(it) }
            val saved =
                if (current == null) {
                    changed = true
                    features.save(
                        Feature(
                            namespaceId = namespaceId,
                            groupId = targetGroup,
                            key = definition.key,
                            value = value,
                            description = definition.description,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                } else if (current.value != value || current.description != definition.description) {
                    changed = true
                    features.save(current.copy(value = value, description = definition.description, updatedAt = now)).also { saved ->
                        if (current.valueAsString() != saved.valueAsString()) {
                            audit.save(
                                FeatureAuditLog(
                                    namespaceId = namespaceId,
                                    featureId = checkNotNull(saved.id),
                                    namespaceKey = key,
                                    featureGroup = identity.first,
                                    featureKey = saved.key,
                                    operation = AuditOperation.VALUE_CHANGED,
                                    oldValue = current.valueAsString(),
                                    newValue = saved.valueAsString(),
                                    changedBy = actor.currentUsername(),
                                    changedAt = now,
                                ),
                            )
                        }
                    }
                } else {
                    current
                }
            nextFeatures += checkNotNull(saved.id)
        }
        oldFeatures.filter { it.id in ownedFeatures && it.id !in nextFeatures }.forEach {
            features.delete(it)
            changed = true
        }
        removedGroups.forEach {
            groups.delete(it)
            changed = true
        }
        val binding =
            ManifestBinding(
                ownerId(request.owner),
                key,
                namespaceId,
                request.owner,
                principal,
                request.generation,
                request.spec,
                nextGroups,
                nextFeatures,
            )
        if (previous != binding || changed) {
            manifests.save(binding)
            manifests.audit(binding, "APPLY")
        }
        return ManifestApplyResponse(request.generation, changed)
    }

    @Transactional
    fun release(
        key: String,
        request: ReleaseNamespaceManifestRequest,
        principal: String,
    ) {
        val previous = lockOwner(key, request.owner, request.generation, principal)
        if (previous?.released == true) return
        if (previous != null && request.deletionPolicy == DeletionPolicy.Delete) {
            val namespace = namespaces.findByKey(key)
            if (namespace != null && namespace.id == previous.namespaceId) {
                val currentGroups = groups.findAllByNamespaceIdOrderByKey(checkNotNull(namespace.id))
                val currentFeatures = features.findAllByNamespaceId(namespace.id, Pageable.unpaged()).content
                if (currentGroups.any { group ->
                        group.id in previous.groups &&
                            currentFeatures.any { it.groupId == group.id && it.id !in previous.features }
                    }
                ) {
                    conflict("A managed group contains manual features; move them or use Retain")
                }
                if (previous.spec?.management?.ownershipPolicy == OwnershipPolicy.Exclusive) {
                    if (currentGroups.any { it.id !in previous.groups } || currentFeatures.any { it.id !in previous.features }) {
                        conflict("Namespace contains unmanaged resources; use Retain")
                    }
                    namespaces.delete(namespace)
                } else {
                    currentFeatures.filter { it.id in previous.features }.forEach(features::delete)
                    currentGroups.filter { it.id in previous.groups }.forEach(groups::delete)
                }
            }
        }
        val released =
            (previous ?: ManifestBinding(ownerId(request.owner), key, null, request.owner, principal, request.generation, null))
                .copy(generation = request.generation, released = true, groups = emptySet(), features = emptySet(), namespaceId = null)
        manifests.save(released)
        manifests.audit(released, "RELEASE_${request.deletionPolicy}")
    }

    private fun lockOwner(
        key: String,
        owner: ManifestOwner,
        generation: Long,
        principal: String,
    ): ManifestBinding? {
        if (key == "default" ||
            !Regex(ValidationPatterns.NAMESPACE_KEY).matches(key) ||
            key.length > 255
        ) {
            invalid("key", "invalid operator namespace")
        }
        if (generation < 1) invalid("generation", "must be positive")
        listOf(owner.clusterId, owner.uid, owner.kubernetesNamespace, owner.name).forEach {
            if (!Regex("[a-zA-Z0-9][a-zA-Z0-9.-]{0,252}").matches(it)) invalid("owner", "invalid owner identity")
        }
        val id = ownerId(owner)
        manifests.lock("operator-owner:$id")
        manifests.lock(key)
        val previous = manifests.byId(id) ?: return null
        if (previous.namespaceKey != key ||
            previous.owner != owner ||
            previous.principal != principal
        ) {
            conflict("CR ownership identity is immutable")
        }
        if (generation < previous.generation) conflict("Stale CR generation")
        return previous
    }

    internal fun validate(spec: NamespaceManifest) {
        val violations = validator.validate(CreateNamespaceRequest(spec.key, spec.displayName))
        if (violations.isNotEmpty()) invalid("spec", violations.first().message)
        if (spec.groups.size > 100 ||
            spec.features.size + spec.groups.sumOf { it.features.size } > 1000
        ) {
            invalid("spec", "limit is 100 groups and 1000 features")
        }
        if (spec.groups
                .map { it.key }
                .distinct()
                .size != spec.groups.size
        ) {
            invalid("groups", "duplicate group key")
        }
        spec.groups.forEach {
            if (!Regex(ValidationPatterns.FEATURE_GROUP).matches(it.key) || it.key.length > 255) invalid("groups.key", "invalid group key")
            if (it.displayName.isBlank() || it.displayName.length > 255) invalid("groups.displayName", "must contain 1-255 characters")
        }
        (listOf(spec.features) + spec.groups.map { it.features }).forEach { scope ->
            if (scope.map { it.key }.distinct().size != scope.size) invalid("features", "duplicate key within a group")
            scope.forEach {
                if (it.group != null) invalid("features.group", "use nesting to select the group")
                val errors = validator.validate(it)
                if (errors.isNotEmpty()) invalid("features.${it.key}", errors.first().message)
                featureService.validateCreation(it, it.type ?: invalid("type", "required"))
            }
        }
    }

    private fun definitions(spec: NamespaceManifest): Map<Pair<String?, String>, CreateFeatureRequest> =
        (
            spec.features.map { (null to it.key) to it } +
                spec.groups.flatMap { group ->
                    group.features.map { (group.key to it.key) to it }
                }
        ).toMap()

    companion object {
        internal fun ownerId(owner: ManifestOwner): UUID =
            UUID.nameUUIDFromBytes("${owner.clusterId}\n${owner.uid}".toByteArray(Charsets.UTF_8))

        internal fun desiredValue(
            definition: CreateFeatureRequest,
            current: FeatureValue?,
            policy: ValuePolicy,
        ): FeatureValue {
            if (current != null && current.type != definition.type) conflict("Feature type is immutable")
            val initialOnly = policy == ValuePolicy.InitialOnly
            return when (definition.type) {
                FeatureType.BOOLEAN -> if (initialOnly && current != null) current else BooleanValue(checkNotNull(definition.booleanValue))
                FeatureType.ENUM -> {
                    val selected = if (initialOnly && current is EnumValue) current.selected else checkNotNull(definition.enumValue)
                    if (selected !in definition.enumOptions) conflict("Current ENUM value was removed from enumOptions")
                    EnumValue(selected, definition.enumOptions)
                }
                FeatureType.VECTOR ->
                    VectorValue(
                        definition.vectorValues.mapValues { (key, initial) ->
                            if (initialOnly && current is VectorValue) current.elements[key] ?: initial else initial
                        },
                    )
                null -> invalid("type", "required")
            }
        }

        private fun invalid(
            field: String,
            message: String,
        ): Nothing =
            throw DomainValidationException(
                "Invalid manifest",
                listOf(
                    field to message,
                ),
            )

        private fun conflict(message: String): Nothing = throw ConflictException(message)
    }
}
