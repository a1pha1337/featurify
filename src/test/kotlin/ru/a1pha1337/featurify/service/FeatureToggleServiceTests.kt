package ru.a1pha1337.featurify.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
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
    fun `public feature list keeps spring pagination metadata`() {
        val pageable = PageRequest.of(1, 10, Sort.by("key"))
        val feature = booleanFeature(version = 2)
        `when`(
            featureRepository.findAllByTenantIdAndStatus(tenantId, FeatureStatus.ACTIVE, pageable),
        ).thenReturn(PageImpl(listOf(feature), pageable, 11))

        val result = service.listActive("blue", pageable, null)

        assertEquals(1, result.number)
        assertEquals(10, result.size)
        assertEquals(11, result.totalElements)
        assertEquals(feature.key, result.content.single().key)
    }

    @Test
    fun `feature search normalizes key and keeps pagination`() {
        val pageable = PageRequest.of(0, 10, Sort.by("key"))
        val feature = booleanFeature(version = 2)
        `when`(
            featureRepository.findAllByTenantIdAndStatusAndKeyContaining(
                tenantId,
                FeatureStatus.ACTIVE,
                "checkout",
                pageable,
            ),
        ).thenReturn(PageImpl(listOf(feature), pageable, 1))

        val result = service.listActive("blue", pageable, " CHECKOUT ")

        assertEquals(feature.key, result.content.single().key)
        verify(featureRepository).findAllByTenantIdAndStatusAndKeyContaining(
            tenantId,
            FeatureStatus.ACTIVE,
            "checkout",
            pageable,
        )
    }

    @Test
    fun `feature search requires at least three characters`() {
        val pageable = PageRequest.of(0, 10)

        val exception = assertThrows(DomainValidationException::class.java) {
            service.listActive("blue", pageable, "ab")
        }

        assertEquals("query", exception.violations.single().first)
    }

    @Test
    fun `feature without tenant is created in default tenant`() {
        val defaultTenantId = UUID.randomUUID()
        `when`(tenantRepository.findByDefaultTenantTrue()).thenReturn(
            Tenant(defaultTenantId, "default", "Default", true, now, now, true),
        )
        `when`(featureRepository.save(org.mockito.ArgumentMatchers.any(Feature::class.java)))
            .thenAnswer { invocation -> invocation.getArgument(0) }

        service.createFeature(
            null,
            CreateFeatureRequest(
                key = "checkout.default",
                type = FeatureType.BOOLEAN,
                booleanValue = true,
            ),
        )

        val featureCaptor = ArgumentCaptor.forClass(Feature::class.java)
        verify(featureRepository).save(featureCaptor.capture())
        assertEquals(defaultTenantId, featureCaptor.value.tenantId)
    }

    @Test
    fun `admin feature list filters by selected statuses`() {
        val pageable = PageRequest.of(0, 20, Sort.by("key"))
        val archived = booleanFeature(version = 2).copy(status = FeatureStatus.ARCHIVED)
        val statuses = setOf(FeatureStatus.ACTIVE, FeatureStatus.ARCHIVED)
        `when`(featureRepository.findAllByTenantIdAndStatusIn(tenantId, statuses, pageable))
            .thenReturn(PageImpl(listOf(archived), pageable, 1))

        val result = service.listForAdmin("blue", pageable, statuses, null)

        assertEquals(FeatureStatus.ARCHIVED, result.content.single().status)
        verify(featureRepository).findAllByTenantIdAndStatusIn(tenantId, statuses, pageable)
    }

    @Test
    fun `admin feature list is empty when no statuses are selected`() {
        val pageable = PageRequest.of(0, 20, Sort.by("key"))

        val result = service.listForAdmin("blue", pageable, emptySet(), null)

        assertEquals(0, result.totalElements)
        verify(featureRepository, never())
            .findAllByTenantIdAndStatusIn(tenantId, emptySet(), pageable)
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
