package ru.a1pha1337.featurify.repository

import org.springframework.data.repository.CrudRepository
import ru.a1pha1337.featurify.domain.FeatureEnumOption
import java.util.UUID

interface FeatureEnumOptionRepository : CrudRepository<FeatureEnumOption, UUID> {
    fun findAllByFeatureIdOrderBySortOrder(featureId: UUID): List<FeatureEnumOption>
}
