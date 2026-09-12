package ru.a1pha1337.featurify.repository

import org.springframework.data.repository.CrudRepository
import ru.a1pha1337.featurify.domain.FeatureGroup
import java.util.UUID

interface FeatureGroupRepository : CrudRepository<FeatureGroup, UUID> {
    fun findByNamespaceIdAndKey(namespaceId: UUID, key: String): FeatureGroup?
    fun findAllByNamespaceIdOrderByKey(namespaceId: UUID): List<FeatureGroup>
}
