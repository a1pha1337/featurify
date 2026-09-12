package ru.a1pha1337.featurify.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.a1pha1337.featurify.domain.Feature
import ru.a1pha1337.featurify.dto.ValidationPatterns
import ru.a1pha1337.featurify.repository.FeatureRepository
import ru.a1pha1337.featurify.repository.FeatureGroupRepository
import java.util.UUID

@Service
class BackendFeatureService(private val features: FeatureRepository, private val groups: FeatureGroupRepository) {
    @Transactional(readOnly = true)
    fun get(namespaceId: UUID, group: String, key: String): Feature {
        if (key.length !in 1..255 || !Regex(ValidationPatterns.FEATURE_KEY).matches(key)) {
            throw DomainValidationException("Invalid feature key", listOf("key" to "must be a valid feature key"))
        }
        if (group.isNotEmpty() && (group.length > 255 || !Regex(ValidationPatterns.FEATURE_GROUP).matches(group))) {
            throw DomainValidationException("Invalid group key", listOf("group" to "must be a valid group key"))
        }
        val feature = if (group.isEmpty()) features.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, key) else {
            val scope = groups.findByNamespaceIdAndKey(namespaceId, group) ?: throw NotFoundException("Group was not found")
            features.findByNamespaceIdAndGroupIdAndKey(namespaceId, scope.id!!, key)
        }
        return feature ?: throw NotFoundException("Feature was not found")
    }
}
