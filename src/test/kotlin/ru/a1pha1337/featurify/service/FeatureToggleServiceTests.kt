package ru.a1pha1337.featurify.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import ru.a1pha1337.featurify.domain.Feature
import ru.a1pha1337.featurify.domain.FeatureEnumOption
import ru.a1pha1337.featurify.domain.FeatureGroup
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.domain.Namespace
import ru.a1pha1337.featurify.dto.CreateFeatureGroupRequest
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreateNamespaceRequest
import ru.a1pha1337.featurify.dto.MoveFeatureRequest
import ru.a1pha1337.featurify.dto.PatchFeatureRequest
import ru.a1pha1337.featurify.repository.FeatureAuditLogRepository
import ru.a1pha1337.featurify.repository.FeatureEnumOptionRepository
import ru.a1pha1337.featurify.repository.FeatureGroupRepository
import ru.a1pha1337.featurify.repository.FeatureRepository
import ru.a1pha1337.featurify.repository.NamespaceRepository
import ru.a1pha1337.featurify.security.ActorProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Optional
import java.util.UUID

class FeatureToggleServiceTests {
    private val namespaceRepository = mockk<NamespaceRepository>(relaxed = true)
    private val featureRepository = mockk<FeatureRepository>(relaxed = true)
    private val groupRepository = mockk<FeatureGroupRepository>(relaxed = true)
    private val optionRepository = mockk<FeatureEnumOptionRepository>(relaxed = true)
    private val auditRepository = mockk<FeatureAuditLogRepository>(relaxed = true)
    private val actorProvider = mockk<ActorProvider>(relaxed = true)
    private val now = Instant.parse("2026-09-11T12:00:00Z")
    private val namespaceId = UUID.randomUUID()
    private val featureId = UUID.randomUUID()
    private lateinit var service: FeatureToggleService

    @BeforeEach
    fun setUp() {
        every { namespaceRepository.findByKey(any()) } returns null
        every { groupRepository.findByNamespaceIdAndKey(any(), any()) } returns null
        every { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(any(), any()) } returns null
        every { featureRepository.findByNamespaceIdAndGroupIdAndKey(any(), any(), any()) } returns null
        every { actorProvider.currentUsername() } returns "test-user"
        service =
            FeatureToggleService(
                namespaceRepository,
                featureRepository,
                groupRepository,
                optionRepository,
                auditRepository,
                actorProvider,
                Clock.fixed(now, ZoneOffset.UTC),
            )
        every { namespaceRepository.findByKey("blue") } returns Namespace(namespaceId, "blue", "Blue", true, now, now)
    }

    @Test
    fun `enum current value must be one of its options`() {
        val exception =
            catchThrowableOfType(DomainValidationException::class.java) {
                service.createFeature(
                    "blue",
                    CreateFeatureRequest(
                        key = "checkout.pet",
                        type = FeatureType.ENUM,
                        enumValue = "DOG",
                        enumOptions = listOf("CAT", "MONKEY"),
                    ),
                )
            }.also { assertThat(it).isNotNull() }

        assertThat(exception.violations.single().first).isEqualTo("enumValue")
        verify(exactly = 0) { featureRepository.save(any()) }
    }

    @Test
    fun `feature creation accepts camel Pascal kebab dotted and digits after first letter`() {
        every { featureRepository.save(any<Feature>()) } answers { arg<Feature>(0).copy(id = featureId, version = 0) }
        listOf("camelCase", "PascalCase", "kebab-case", "checkout.payment-provider", "release2").forEach { key ->
            val created =
                service.createFeature("blue", CreateFeatureRequest(key, FeatureType.BOOLEAN, booleanValue = true))
            assertThat(created.key).isEqualTo(key)
        }
    }

    @Test
    fun `feature creation rejects underscore special characters and digit start`() {
        listOf(
            "snake_case",
            "with space",
            "-leading",
            "trailing-",
            "with..double",
            "with.-mixed",
            "with--double",
            "2release",
        ).forEach { key ->
            catchThrowableOfType(DomainValidationException::class.java) {
                service.createFeature("blue", CreateFeatureRequest(key, FeatureType.BOOLEAN, booleanValue = true))
            }.also { assertThat(it).isNotNull() }
        }
        verify(exactly = 0) { featureRepository.save(any()) }
    }

    @Test
    fun `stale version is rejected before update`() {
        val feature = booleanFeature(version = 5)
        every { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, feature.key) } returns feature

        catchThrowableOfType(ConflictException::class.java) {
            service.patchFeature("blue", feature.key, null, PatchFeatureRequest(version = 4, booleanValue = true))
        }.also { assertThat(it).isNotNull() }

        verify(exactly = 0) { featureRepository.save(any()) }
    }

    @Test
    fun `grouped feature lookup uses namespace group and key`() {
        val groupId = UUID.randomUUID()
        val group = FeatureGroup(groupId, namespaceId, "checkout", "Checkout", createdAt = now, updatedAt = now)
        val feature = booleanFeature(version = 2).copy(groupId = groupId)
        every { groupRepository.findByNamespaceIdAndKey(namespaceId, "checkout") } returns group
        every { groupRepository.findById(groupId) } returns Optional.of(group)
        every { featureRepository.findByNamespaceIdAndGroupIdAndKey(namespaceId, groupId, feature.key) } returns feature

        val result = service.getFeature("blue", feature.key, "checkout")

        assertThat(result.group).isEqualTo("checkout")
        assertThat(result.key).isEqualTo(feature.key)
        verify(exactly = 1) { featureRepository.findByNamespaceIdAndGroupIdAndKey(namespaceId, groupId, feature.key) }
        verify(exactly = 0) { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, feature.key) }
    }

    @Test
    fun `feature without group uses global lookup`() {
        val feature = booleanFeature(version = 2)
        every { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, feature.key) } returns feature

        val result = service.getFeature("blue", feature.key, null)

        assertThat(result.group).isNull()
        verify(exactly = 1) { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, feature.key) }
    }

    @Test
    fun `moving feature updates its group`() {
        val sourceId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        val source = FeatureGroup(sourceId, namespaceId, "old", "Old", createdAt = now, updatedAt = now)
        val target = FeatureGroup(targetId, namespaceId, "new", "New", createdAt = now, updatedAt = now)
        val feature = booleanFeature(version = 2).copy(groupId = sourceId)
        every { groupRepository.findByNamespaceIdAndKey(namespaceId, "old") } returns source
        every { groupRepository.findByNamespaceIdAndKey(namespaceId, "new") } returns target
        every { featureRepository.findByNamespaceIdAndGroupIdAndKey(namespaceId, sourceId, feature.key) } returns feature
        every { featureRepository.save(any<Feature>()) } answers { firstArg() }
        every { groupRepository.findById(targetId) } returns Optional.of(target)

        val result = service.moveFeature("blue", feature.key, "old", MoveFeatureRequest(2, "new"))

        assertThat(result.group).isEqualTo("new")
        val captor = slot<Feature>()
        verify(exactly = 1) { featureRepository.save(capture(captor)) }
        assertThat(captor.captured.groupId).isEqualTo(targetId)
    }

    @Test
    fun `public feature list keeps spring pagination metadata`() {
        val pageable = PageRequest.of(1, 10, Sort.by("key"))
        val feature = booleanFeature(version = 2)
        every { featureRepository.findAllByNamespaceId(namespaceId, pageable) } returns PageImpl(listOf(feature), pageable, 11)

        val result = service.listFeatures("blue", pageable, null)

        assertThat(result.number).isEqualTo(1)
        assertThat(result.size).isEqualTo(10)
        assertThat(result.totalElements).isEqualTo(11)
        assertThat(result.content.single().key).isEqualTo(feature.key)
    }

    @Test
    fun `feature search normalizes key and keeps pagination`() {
        val pageable = PageRequest.of(0, 10, Sort.by("key"))
        val feature = booleanFeature(version = 2)
        every {
            featureRepository.findAllByNamespaceIdAndKeyContaining(
                namespaceId,
                "checkout",
                pageable,
            )
        } returns PageImpl(listOf(feature), pageable, 1)

        val result = service.listFeatures("blue", pageable, " CHECKOUT ")

        assertThat(result.content.single().key).isEqualTo(feature.key)
        verify(exactly = 1) {
            featureRepository.findAllByNamespaceIdAndKeyContaining(
                namespaceId,
                "checkout",
                pageable,
            )
        }
    }

    @Test
    fun `feature search requires at least three characters`() {
        val pageable = PageRequest.of(0, 10)

        val exception =
            catchThrowableOfType(DomainValidationException::class.java) {
                service.listFeatures("blue", pageable, "ab")
            }.also { assertThat(it).isNotNull() }

        assertThat(exception.violations.single().first).isEqualTo("query")
    }

    @Test
    fun `feature without namespace is created in default namespace`() {
        val defaultNamespaceId = UUID.randomUUID()
        every { namespaceRepository.findByDefaultNamespaceTrue() } returns
            Namespace(defaultNamespaceId, "default", "Default", true, now, now, true)
        every { featureRepository.save(any<Feature>()) } answers { firstArg() }

        service.createFeature(
            null,
            CreateFeatureRequest(
                key = "checkout.default",
                type = FeatureType.BOOLEAN,
                booleanValue = true,
            ),
        )

        val featureCaptor = slot<Feature>()
        verify(exactly = 1) { featureRepository.save(capture(featureCaptor)) }
        assertThat(featureCaptor.captured.namespaceId).isEqualTo(defaultNamespaceId)
    }

    @Test
    fun `enum patch validates against options`() {
        val feature =
            Feature(
                id = featureId,
                namespaceId = namespaceId,
                key = "checkout.pet",
                type = FeatureType.ENUM,
                enumValue = "CAT",
                version = 1,
                createdAt = now,
                updatedAt = now,
            )
        every { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, feature.key) } returns feature
        every { optionRepository.findAllByFeatureIdOrderBySortOrder(featureId) } returns
            listOf(FeatureEnumOption(UUID.randomUUID(), featureId, "CAT", 0))

        catchThrowableOfType(DomainValidationException::class.java) {
            service.patchFeature("blue", feature.key, null, PatchFeatureRequest(version = 1, enumValue = "DOG"))
        }.also { assertThat(it).isNotNull() }
    }

    @Test
    fun `group creation normalizes input and saves an empty group`() {
        every { groupRepository.save(any<FeatureGroup>()) } answers {
            arg<FeatureGroup>(0).copy(id = UUID.randomUUID(), version = 0)
        }

        val result = service.createGroup("blue", CreateFeatureGroupRequest(" checkout ", " Checkout "))

        assertThat(result.key).isEqualTo("checkout")
        assertThat(result.displayName).isEqualTo("Checkout")
        verify(exactly = 0) { featureRepository.save(any()) }
    }

    @Test
    fun `group creation rejects blank name and duplicate key`() {
        catchThrowableOfType(DomainValidationException::class.java) {
            service.createGroup("blue", CreateFeatureGroupRequest("checkout", " "))
        }.also { assertThat(it).isNotNull() }
        every { groupRepository.findByNamespaceIdAndKey(namespaceId, "checkout") } returns
            FeatureGroup(UUID.randomUUID(), namespaceId, "checkout", "Checkout", createdAt = now, updatedAt = now)
        catchThrowableOfType(ConflictException::class.java) {
            service.createGroup("blue", CreateFeatureGroupRequest("checkout", "Checkout"))
        }.also { assertThat(it).isNotNull() }
        verify(exactly = 0) { groupRepository.save(any()) }
    }

    @Test
    fun `moving grouped feature to global clears group id`() {
        val groupId = UUID.randomUUID()
        val group = FeatureGroup(groupId, namespaceId, "checkout", "Checkout", createdAt = now, updatedAt = now)
        val feature = booleanFeature(2).copy(groupId = groupId)
        every { groupRepository.findByNamespaceIdAndKey(namespaceId, "checkout") } returns group
        every { featureRepository.findByNamespaceIdAndGroupIdAndKey(namespaceId, groupId, feature.key) } returns feature
        every { featureRepository.save(any<Feature>()) } answers { arg<Feature>(0) }

        val result = service.moveFeature("blue", feature.key, "checkout", MoveFeatureRequest(2, null))

        assertThat(result.group).isNull()
        val saved = slot<Feature>()
        verify(exactly = 1) { featureRepository.save(capture(saved)) }
        assertThat(saved.captured.groupId).isNull()
    }

    @Test
    fun `move rejects occupied destination`() {
        val groupId = UUID.randomUUID()
        val group = FeatureGroup(groupId, namespaceId, "checkout", "Checkout", createdAt = now, updatedAt = now)
        val feature = booleanFeature(2)
        every { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, feature.key) } returns feature
        every { groupRepository.findByNamespaceIdAndKey(namespaceId, "checkout") } returns group
        every { featureRepository.findByNamespaceIdAndGroupIdAndKey(namespaceId, groupId, feature.key) } returns
            feature.copy(id = UUID.randomUUID(), groupId = groupId)

        catchThrowableOfType(ConflictException::class.java) {
            service.moveFeature("blue", feature.key, null, MoveFeatureRequest(2, "checkout"))
        }.also { assertThat(it).isNotNull() }
        verify(exactly = 0) { featureRepository.save(any()) }
    }

    @Test
    fun `group filter is applied before pagination and combined with search`() {
        val groupId = UUID.randomUUID()
        val group = FeatureGroup(groupId, namespaceId, "checkout", "Checkout", createdAt = now, updatedAt = now)
        val page = PageRequest.of(1, 20)
        every { groupRepository.findByNamespaceIdAndKey(namespaceId, "checkout") } returns group
        every {
            featureRepository.findAllByNamespaceIdAndGroupIdAndKeyContaining(
                namespaceId,
                groupId,
                "checkout",
                page,
            )
        } returns PageImpl(emptyList(), page, 21)

        val result = service.listForAdmin("blue", page, " CHECKOUT ", "checkout")

        assertThat(result.totalElements).isEqualTo(21)
        assertThat(result.number).isEqualTo(1)
        verify(exactly = 1) {
            featureRepository.findAllByNamespaceIdAndGroupIdAndKeyContaining(
                namespaceId,
                groupId,
                "checkout",
                page,
            )
        }
    }

    @Test
    fun `global filter does not include grouped features`() {
        val page = PageRequest.of(0, 20)
        every { featureRepository.findAllByNamespaceIdAndGroupIdIsNull(namespaceId, page) } returns
            PageImpl(listOf(booleanFeature(1)), page, 1)

        val result = service.listForAdmin("blue", page, null, globalOnly = true)

        assertThat(result.content.single().group).isNull()
        verify(exactly = 1) { featureRepository.findAllByNamespaceIdAndGroupIdIsNull(namespaceId, page) }
    }

    @Test
    fun `new namespace is never default`() {
        every { namespaceRepository.save(any<Namespace>()) } answers { arg<Namespace>(0).copy(id = UUID.randomUUID()) }

        val created = service.createNamespace(CreateNamespaceRequest("green", "Green"))

        assertThat(created.defaultNamespace).isEqualTo(false)
        val saved = slot<Namespace>()
        verify(exactly = 1) { namespaceRepository.save(capture(saved)) }
        assertThat(saved.captured.defaultNamespace).isEqualTo(false)
    }

    @Test
    fun `system default namespace cannot be created through service`() {
        val error =
            catchThrowableOfType(DomainValidationException::class.java) {
                service.createNamespace(CreateNamespaceRequest("default", "Replacement"))
            }.also { assertThat(it).isNotNull() }

        assertThat(error.violations.single().first).isEqualTo("key")
        verify(exactly = 0) { namespaceRepository.save(any()) }
    }

    @Test
    fun `delete feature requires current version`() {
        val feature = booleanFeature(3)
        every { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, feature.key) } returns feature
        catchThrowableOfType(ConflictException::class.java) {
            service.deleteFeature("blue", feature.key, null, 2)
        }.also { assertThat(it).isNotNull() }
        catchThrowableOfType(DomainValidationException::class.java) {
            service.deleteFeature("blue", feature.key, null, null)
        }.also { assertThat(it).isNotNull() }
        verify(exactly = 0) { featureRepository.delete(feature) }
        service.deleteFeature("blue", feature.key, null, 3)
        verify(exactly = 1) { featureRepository.delete(feature) }
    }

    @Test
    fun `delete group requires current version`() {
        val group =
            FeatureGroup(
                UUID.randomUUID(),
                namespaceId,
                "checkout",
                "Checkout",
                version = 3,
                createdAt = now,
                updatedAt = now,
            )
        every { groupRepository.findByNamespaceIdAndKey(namespaceId, group.key) } returns group
        catchThrowableOfType(
            ConflictException::class.java,
        ) { service.deleteGroup("blue", group.key, 2) }.also { assertThat(it).isNotNull() }
        catchThrowableOfType(DomainValidationException::class.java) {
            service.deleteGroup("blue", group.key, null)
        }.also { assertThat(it).isNotNull() }
        verify(exactly = 0) { groupRepository.delete(group) }
        service.deleteGroup("blue", group.key, 3)
        verify(exactly = 1) { groupRepository.delete(group) }
    }

    @Test
    fun `delete namespace rejects default and unknown namespace`() {
        val default = Namespace(UUID.randomUUID(), "default", "Default", true, now, now, true)
        every { namespaceRepository.findByKey("default") } returns default
        catchThrowableOfType(ConflictException::class.java) { service.deleteNamespace("default") }.also { assertThat(it).isNotNull() }
        catchThrowableOfType(NotFoundException::class.java) { service.deleteNamespace("missing") }.also { assertThat(it).isNotNull() }
        verify(exactly = 0) { namespaceRepository.delete(default) }
        service.deleteNamespace("blue")
        val blue = Namespace(namespaceId, "blue", "Blue", true, now, now)
        verify(exactly = 1) { namespaceRepository.delete(blue) }
    }

    private fun booleanFeature(version: Long) =
        Feature(
            id = featureId,
            namespaceId = namespaceId,
            key = "checkout.enabled",
            type = FeatureType.BOOLEAN,
            booleanValue = false,
            version = version,
            createdAt = now,
            updatedAt = now,
        )
}
