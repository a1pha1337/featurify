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
import ru.a1pha1337.featurify.dto.CreateFeatureGroupRequest
import ru.a1pha1337.featurify.dto.MoveFeatureRequest
import ru.a1pha1337.featurify.dto.PatchFeatureRequest
import ru.a1pha1337.featurify.domain.Feature
import ru.a1pha1337.featurify.domain.FeatureEnumOption
import ru.a1pha1337.featurify.domain.FeatureGroup
import ru.a1pha1337.featurify.domain.FeatureStatus
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.domain.Tenant
import ru.a1pha1337.featurify.repository.FeatureAuditLogRepository
import ru.a1pha1337.featurify.repository.FeatureEnumOptionRepository
import ru.a1pha1337.featurify.repository.FeatureGroupRepository
import ru.a1pha1337.featurify.repository.FeatureRepository
import ru.a1pha1337.featurify.repository.TenantRepository
import ru.a1pha1337.featurify.security.ActorProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.Optional

class FeatureToggleServiceTests {
    private val tenantRepository = mock(TenantRepository::class.java)
    private val featureRepository = mock(FeatureRepository::class.java)
    private val groupRepository = mock(FeatureGroupRepository::class.java)
    private val optionRepository = mock(FeatureEnumOptionRepository::class.java)
    private val auditRepository = mock(FeatureAuditLogRepository::class.java)
    private val actorProvider = mock(ActorProvider::class.java)
    private val now = Instant.parse("2026-09-11T12:00:00Z")
    private val tenantId = UUID.randomUUID()
    private val featureId = UUID.randomUUID()
    private lateinit var service: FeatureToggleService

    @BeforeEach
    fun setUp() {
        `when`(actorProvider.currentUsername()).thenReturn("test-user")
        service = FeatureToggleService(
            tenantRepository,
            featureRepository,
            groupRepository,
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
        `when`(featureRepository.findByTenantIdAndGroupIdIsNullAndKey(tenantId, feature.key)).thenReturn(feature)

        assertThrows(ConflictException::class.java) {
            service.patchFeature("blue", feature.key, null, PatchFeatureRequest(version = 4, booleanValue = true))
        }

        verify(featureRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `archived feature is hidden from public read`() {
        val feature = booleanFeature(version = 2).copy(status = FeatureStatus.ARCHIVED)
        `when`(featureRepository.findByTenantIdAndGroupIdIsNullAndKey(tenantId, feature.key)).thenReturn(feature)

        assertThrows(NotFoundException::class.java) {
            service.getActive("blue", feature.key, null)
        }
    }

    @Test
    fun `grouped feature lookup uses tenant group and key`() {
        val groupId = UUID.randomUUID()
        val group = FeatureGroup(groupId, tenantId, "checkout", "Checkout", createdAt = now, updatedAt = now)
        val feature = booleanFeature(version = 2).copy(groupId = groupId)
        `when`(groupRepository.findByTenantIdAndKey(tenantId, "checkout")).thenReturn(group)
        `when`(groupRepository.findById(groupId)).thenReturn(Optional.of(group))
        `when`(
            featureRepository.findByTenantIdAndGroupIdAndKey(tenantId, groupId, feature.key),
        ).thenReturn(feature)

        val result = service.getActive("blue", feature.key, "checkout")

        assertEquals("checkout", result.group)
        assertEquals(feature.key, result.key)
        verify(featureRepository).findByTenantIdAndGroupIdAndKey(tenantId, groupId, feature.key)
        verify(featureRepository, never()).findByTenantIdAndGroupIdIsNullAndKey(tenantId, feature.key)
    }

    @Test
    fun `feature without group uses global lookup`() {
        val feature = booleanFeature(version = 2)
        `when`(featureRepository.findByTenantIdAndGroupIdIsNullAndKey(tenantId, feature.key)).thenReturn(feature)

        val result = service.getActive("blue", feature.key, null)

        assertEquals(null, result.group)
        verify(featureRepository).findByTenantIdAndGroupIdIsNullAndKey(tenantId, feature.key)
    }

    @Test
    fun `archiving group archives all active features in that group`() {
        val groupId = UUID.randomUUID()
        val group = FeatureGroup(groupId, tenantId, "checkout", "Checkout", version = 3, createdAt = now, updatedAt = now)
        val first = booleanFeature(version = 1).copy(groupId = groupId)
        val second = booleanFeature(version = 2).copy(id = UUID.randomUUID(), key = "checkout.payment", groupId = groupId)
        `when`(groupRepository.findByTenantIdAndKey(tenantId, group.key)).thenReturn(group)
        `when`(groupRepository.save(org.mockito.ArgumentMatchers.any(FeatureGroup::class.java)))
            .thenAnswer { invocation -> invocation.getArgument(0) }
        `when`(featureRepository.findAllByTenantIdAndGroupIdAndStatus(tenantId, groupId, FeatureStatus.ACTIVE))
            .thenReturn(listOf(first, second))
        `when`(featureRepository.save(org.mockito.ArgumentMatchers.any(Feature::class.java)))
            .thenAnswer { invocation -> invocation.getArgument(0) }
        `when`(groupRepository.findById(groupId)).thenReturn(Optional.of(group))

        service.archiveGroup("blue", group.key, group.version)

        val featureCaptor = ArgumentCaptor.forClass(Feature::class.java)
        verify(featureRepository, org.mockito.Mockito.times(2)).save(featureCaptor.capture())
        assertEquals(setOf(FeatureStatus.ARCHIVED), featureCaptor.allValues.map { it.status }.toSet())
        verify(auditRepository, org.mockito.Mockito.times(2)).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `moving feature updates its group`() {
        val sourceId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val source = FeatureGroup(sourceId, tenantId, "old", "Old", createdAt = now, updatedAt = now)
        val target = FeatureGroup(targetId, tenantId, "new", "New", createdAt = now, updatedAt = now)
        val feature = booleanFeature(version = 2).copy(groupId = sourceId)
        `when`(groupRepository.findByTenantIdAndKey(tenantId, "old")).thenReturn(source)
        `when`(groupRepository.findByTenantIdAndKey(tenantId, "new")).thenReturn(target)
        `when`(featureRepository.findByTenantIdAndGroupIdAndKey(tenantId, sourceId, feature.key)).thenReturn(feature)
        `when`(featureRepository.save(org.mockito.ArgumentMatchers.any(Feature::class.java)))
            .thenAnswer { invocation -> invocation.getArgument(0) }
        `when`(groupRepository.findById(targetId)).thenReturn(Optional.of(target))

        val result = service.moveFeature("blue", feature.key, "old", MoveFeatureRequest(2, "new"))

        assertEquals("new", result.group)
        val captor = ArgumentCaptor.forClass(Feature::class.java)
        verify(featureRepository).save(captor.capture())
        assertEquals(targetId, captor.value.groupId)
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
        `when`(featureRepository.findByTenantIdAndGroupIdIsNullAndKey(tenantId, feature.key)).thenReturn(feature)
        `when`(optionRepository.findAllByFeatureIdOrderBySortOrder(featureId)).thenReturn(
            listOf(FeatureEnumOption(UUID.randomUUID(), featureId, "CAT", 0)),
        )

        assertThrows(DomainValidationException::class.java) {
            service.patchFeature("blue", feature.key, null, PatchFeatureRequest(version = 1, enumValue = "DOG"))
        }
    }

    @Test
    fun `group creation normalizes input and saves an empty group`() {
        `when`(groupRepository.save(org.mockito.ArgumentMatchers.any(FeatureGroup::class.java)))
            .thenAnswer { invocation -> invocation.getArgument<FeatureGroup>(0).copy(id = UUID.randomUUID(), version = 0) }

        val result = service.createGroup("blue", CreateFeatureGroupRequest(" checkout ", " Checkout "))

        assertEquals("checkout", result.key)
        assertEquals("Checkout", result.displayName)
        verify(featureRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `group creation rejects blank name and duplicate key`() {
        assertThrows(DomainValidationException::class.java) {
            service.createGroup("blue", CreateFeatureGroupRequest("checkout", " "))
        }
        `when`(groupRepository.findByTenantIdAndKey(tenantId, "checkout")).thenReturn(
            FeatureGroup(UUID.randomUUID(), tenantId, "checkout", "Checkout", createdAt = now, updatedAt = now),
        )
        assertThrows(ConflictException::class.java) {
            service.createGroup("blue", CreateFeatureGroupRequest("checkout", "Checkout"))
        }
        verify(groupRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `moving grouped feature to global clears group id`() {
        val groupId = UUID.randomUUID()
        val group = FeatureGroup(groupId, tenantId, "checkout", "Checkout", createdAt = now, updatedAt = now)
        val feature = booleanFeature(2).copy(groupId = groupId)
        `when`(groupRepository.findByTenantIdAndKey(tenantId, "checkout")).thenReturn(group)
        `when`(featureRepository.findByTenantIdAndGroupIdAndKey(tenantId, groupId, feature.key)).thenReturn(feature)
        `when`(featureRepository.save(org.mockito.ArgumentMatchers.any(Feature::class.java)))
            .thenAnswer { it.getArgument<Feature>(0) }

        val result = service.moveFeature("blue", feature.key, "checkout", MoveFeatureRequest(2, null))

        assertEquals(null, result.group)
        val saved = ArgumentCaptor.forClass(Feature::class.java)
        verify(featureRepository).save(saved.capture())
        assertEquals(null, saved.value.groupId)
    }

    @Test
    fun `move rejects occupied destination including archived features`() {
        val groupId = UUID.randomUUID()
        val group = FeatureGroup(groupId, tenantId, "checkout", "Checkout", createdAt = now, updatedAt = now)
        val feature = booleanFeature(2)
        `when`(featureRepository.findByTenantIdAndGroupIdIsNullAndKey(tenantId, feature.key)).thenReturn(feature)
        `when`(groupRepository.findByTenantIdAndKey(tenantId, "checkout")).thenReturn(group)
        `when`(featureRepository.findByTenantIdAndGroupIdAndKey(tenantId, groupId, feature.key))
            .thenReturn(feature.copy(id = UUID.randomUUID(), groupId = groupId, status = FeatureStatus.ARCHIVED))

        assertThrows(ConflictException::class.java) {
            service.moveFeature("blue", feature.key, null, MoveFeatureRequest(2, "checkout"))
        }
        verify(featureRepository, never()).save(org.mockito.ArgumentMatchers.any())
    }

    @Test
    fun `group filter is applied before pagination and combined with search`() {
        val groupId = UUID.randomUUID()
        val group = FeatureGroup(groupId, tenantId, "checkout", "Checkout", createdAt = now, updatedAt = now)
        val page = PageRequest.of(1, 20)
        val statuses = setOf(FeatureStatus.ACTIVE)
        `when`(groupRepository.findByTenantIdAndKey(tenantId, "checkout")).thenReturn(group)
        `when`(featureRepository.findAllByTenantIdAndGroupIdAndStatusInAndKeyContaining(
            tenantId, groupId, statuses, "checkout", page,
        )).thenReturn(PageImpl(emptyList(), page, 21))

        val result = service.listForAdmin("blue", page, statuses, " CHECKOUT ", "checkout")

        assertEquals(21, result.totalElements)
        assertEquals(1, result.number)
        verify(featureRepository).findAllByTenantIdAndGroupIdAndStatusInAndKeyContaining(
            tenantId, groupId, statuses, "checkout", page,
        )
    }

    @Test
    fun `global filter does not include grouped features`() {
        val page = PageRequest.of(0, 20)
        val statuses = setOf(FeatureStatus.ACTIVE)
        `when`(featureRepository.findAllByTenantIdAndGroupIdIsNullAndStatusIn(tenantId, statuses, page))
            .thenReturn(PageImpl(listOf(booleanFeature(1)), page, 1))

        val result = service.listForAdmin("blue", page, statuses, null, globalOnly = true)

        assertEquals(null, result.content.single().group)
        verify(featureRepository).findAllByTenantIdAndGroupIdIsNullAndStatusIn(tenantId, statuses, page)
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
