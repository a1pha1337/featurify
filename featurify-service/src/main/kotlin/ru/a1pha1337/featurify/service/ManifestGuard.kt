package ru.a1pha1337.featurify.service

import org.springframework.stereotype.Service
import ru.a1pha1337.featurify.domain.Feature
import ru.a1pha1337.featurify.dto.OwnershipPolicy
import ru.a1pha1337.featurify.dto.PatchFeatureRequest
import ru.a1pha1337.featurify.dto.ValuePolicy
import ru.a1pha1337.featurify.repository.ManifestBinding
import ru.a1pha1337.featurify.repository.ManifestRepository
import java.util.UUID

@Service
class ManifestGuard(
    private val manifests: ManifestRepository,
) {
    fun lock(namespaceKey: String?) = manifests.lock(namespaceKey ?: "default")

    fun binding(namespaceId: UUID): ManifestBinding? = manifests.byNamespace(namespaceId)

    fun create(namespaceId: UUID) {
        if (binding(namespaceId)?.spec?.management?.ownershipPolicy == OwnershipPolicy.Exclusive) denied()
    }

    fun deleteNamespace(namespaceId: UUID) {
        if (binding(namespaceId) != null) denied()
    }

    fun deleteGroup(
        namespaceId: UUID,
        groupId: UUID,
    ) {
        if (binding(namespaceId)?.groups?.contains(groupId) == true) denied()
    }

    fun structure(feature: Feature) {
        if (binding(feature.namespaceId)?.features?.contains(feature.id) == true) denied()
    }

    fun patch(
        feature: Feature,
        request: PatchFeatureRequest,
    ) {
        val binding = binding(feature.namespaceId) ?: return
        if (feature.id !in binding.features) return
        if (request.description != null && request.description != feature.description) denied()
        if (binding.spec?.management?.valuePolicy == ValuePolicy.Managed) denied()
    }

    private fun denied(): Nothing = throw ConflictException("Managed by Kubernetes; change the FeaturifyNamespace manifest")
}
