package ru.a1pha1337.featurify.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.PatchFeatureRequest
import ru.a1pha1337.featurify.domain.Feature
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
import java.time.ZoneOffset
import java.util.UUID

class FeatureToggleServiceTests {
    private val tenantRepository = mock(TenantRepository::class.java)
    private val featureRepository = mock(FeatureRepository::class.java)
    private val optionRepository = mock(FeatureEnumOptionRepository::class.java)
    private val auditRepository = mock(FeatureAuditLogRepository::class.java)
    private val actorProvider = mock(ActorProvider::class.java)
    private val now = Instant.parse("2026-09-11T12:00:00Z")
    private val tenantId = UUID.randomUUID()
    private val featureId = UUID.randomUUID()
    private lateinit var service: FeatureToggleService

    @BeforeEach
    fun setUp() {
        service = FeatureToggleService(
            tenantRepository,
            featureRepository,
            optionRepository,
            auditRepository,
            actorProvider,
            Clock.fixed(now, ZoneOffset.UTC),
        )
        `when`(tenantRepository.findByKey("blue")).thenReturn(
            Tenant(tenantId, "blue", "Blue", true, now, now),
        )
    }

    @Test
    fun `enum current value must be one of its options`() {
        val exception = assertThrows(DomainValidationException::class.java) {
            service.createFeature(
                "blue",
                CreateFeatureRequest(
                    key = "checkout.pet",
                    type = FeatureType.ENUM,
                    enumValue = "DOG",
                    enumOptions = listOf("CAT", "MONKEY"),
                ),
            )
        }

        assertEquals("enumValue", exception.violations.single().first)
        verify(featureRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `stale version is rejected before update`() {
        val feature = booleanFeature(version = 5)
        `when`(featureRepository.findByTenantIdAndKey(tenantId, feature.key)).thenReturn(feature)

        assertThrows(ConflictException::class.java) {
            service.patchFeature("blue", feature.key, PatchFeatureRequest(version = 4, booleanValue = true))
        }

        verify(featureRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `archived feature is hidden from public read`() {
        val feature = booleanFeature(version = 2).copy(status = FeatureStatus.ARCHIVED)
        `when`(featureRepository.findByTenantIdAndKey(tenantId, feature.key)).thenReturn(feature)

        assertThrows(NotFoundException::class.java) {
            service.getActive("blue", feature.key)
        }
    }

    @Test
    fun `enum patch validates against options`() {
        val feature = Feature(
            id = featureId,
            tenantId = tenantId,
            key = "checkout.pet",
            type = FeatureType.ENUM,
            enumValue = "CAT",
            version = 1,
            createdAt = now,
            updatedAt = now,
        )
        `when`(featureRepository.findByTenantIdAndKey(tenantId, feature.key)).thenReturn(feature)
        `when`(optionRepository.findAllByFeatureIdOrderBySortOrder(featureId)).thenReturn(
            listOf(FeatureEnumOption(UUID.randomUUID(), featureId, "CAT", 0)),
        )

        assertThrows(DomainValidationException::class.java) {
            service.patchFeature("blue", feature.key, PatchFeatureRequest(version = 1, enumValue = "DOG"))
        }
    }

    private fun booleanFeature(version: Long) = Feature(
        id = featureId,
        tenantId = tenantId,
        key = "checkout.enabled",
        type = FeatureType.BOOLEAN,
        booleanValue = false,
        version = version,
        createdAt = now,
        updatedAt = now,
    )
}
