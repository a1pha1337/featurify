package ru.a1pha1337.featurify.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import ru.a1pha1337.featurify.domain.BooleanValue
import ru.a1pha1337.featurify.domain.EnumValue
import ru.a1pha1337.featurify.domain.Feature
import ru.a1pha1337.featurify.domain.FeatureGroup
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.domain.Namespace
import ru.a1pha1337.featurify.domain.PayloadValue
import ru.a1pha1337.featurify.domain.VectorValue
import ru.a1pha1337.featurify.dto.CreateFeatureGroupRequest
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreateNamespaceRequest
import ru.a1pha1337.featurify.dto.MoveFeatureRequest
import ru.a1pha1337.featurify.dto.PatchFeatureRequest
import ru.a1pha1337.featurify.repository.FeatureAuditLogRepository
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
                auditRepository,
                actorProvider,
                Clock.fixed(now, ZoneOffset.UTC),
                mockk<ManifestGuard>(relaxed = true),
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
    fun `payload creates structured public value and replaces the whole document with audit`() {
        every { auditRepository.save(any()) } answers { firstArg() }
        every { featureRepository.save(any()) } answers { firstArg<Feature>().copy(id = featureId, version = 0) }
        val created = service.createFeature("blue", CreateFeatureRequest("config", FeatureType.PAYLOAD, payloadValue = """{"old":1}"""))
        assertThat(created.value.toString()).isEqualTo("""{"old":1}""")
        val current = booleanFeature(version = 0).copy(key = "config", value = PayloadValue("""{"old":1}"""))
        every { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, "config") } returns current
        every { featureRepository.save(any()) } answers { firstArg<Feature>().copy(version = 1) }
        val changed = service.patchFeature("blue", "config", null, PatchFeatureRequest(0, payloadValue = "[true,null]"))
        assertThat(changed.value.toString()).isEqualTo("[true,null]")
        assertThat(changed.version).isEqualTo(1)
        verify { auditRepository.save(match { it.oldValue == """{"old":1}""" && it.newValue == "[true,null]" }) }
        assertThatThrownBy { service.patchFeature("blue", "config", null, PatchFeatureRequest(9, payloadValue = "{}")) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `payload validates creation and patch and rejects values on other types`() {
        listOf(null, "", "{", "{} []", "{\"a\":1,\"a\":2}", "x".repeat(65537)).forEach { json ->
            assertThatThrownBy { service.createFeature("blue", CreateFeatureRequest("config", FeatureType.PAYLOAD, payloadValue = json)) }
                .isInstanceOf(DomainValidationException::class.java)
        }
        assertThatThrownBy {
            service.createFeature("blue", CreateFeatureRequest("config", FeatureType.PAYLOAD, payloadValue = "{}", booleanValue = false))
        }.isInstanceOf(DomainValidationException::class.java)
        assertThatThrownBy {
            service.createFeature("blue", CreateFeatureRequest("config", FeatureType.BOOLEAN, payloadValue = "{}", booleanValue = false))
        }.isInstanceOf(DomainValidationException::class.java)
        val current = booleanFeature(version = 0)
        every { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, current.key) } returns current
        assertThatThrownBy { service.patchFeature("blue", current.key, null, PatchFeatureRequest(0, payloadValue = "{}")) }
            .isInstanceOf(DomainValidationException::class.java)
        every { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, current.key) } returns
            current.copy(value = PayloadValue("{}"))
        assertThatThrownBy { service.patchFeature("blue", current.key, null, PatchFeatureRequest(0, payloadValue = "broken")) }
            .isInstanceOf(DomainValidationException::class.java)
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
                value = EnumValue("CAT", listOf("CAT")),
                version = 1,
                createdAt = now,
                updatedAt = now,
            )
        every { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, feature.key) } returns feature

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
            value = BooleanValue(false),
            version = version,
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun `vector creation keeps independent element states`() {
        every { featureRepository.save(any<Feature>()) } answers { firstArg<Feature>().copy(id = featureId, version = 0) }
        val values = mapOf("CAT" to true, "DOG" to true, "SHIP" to false)
        val created = service.createFeature("blue", CreateFeatureRequest("feat", FeatureType.VECTOR, vectorValues = values))
        assertThat(created.type).isEqualTo(FeatureType.VECTOR)
        assertThat(created.value).isEqualTo(values)
        assertThat(created.enumOptions).isNull()
    }

    @Test
    fun `vector patch changes only selected elements and records old and new values`() {
        every { auditRepository.save(any<ru.a1pha1337.featurify.domain.FeatureAuditLog>()) } answers { firstArg() }
        val current =
            booleanFeature(3).copy(
                value =
                    VectorValue(
                        mapOf(
                            "CAT" to true,
                            "DOG" to false,
                            "SHIP" to false,
                        ),
                    ),
            )
        every { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, current.key) } returns current
        every { featureRepository.save(any<Feature>()) } answers { firstArg<Feature>().copy(version = 4) }
        val result = service.patchFeature("blue", current.key, null, PatchFeatureRequest(3, vectorValues = mapOf("DOG" to true)))
        assertThat(result.value).isEqualTo(mapOf("CAT" to true, "DOG" to true, "SHIP" to false))
        assertThat(result.version).isEqualTo(4)
        verify(exactly = 1) {
            auditRepository.save(
                match {
                    it.oldValue == """{"CAT":true,"DOG":false,"SHIP":false}""" &&
                        it.newValue == """{"CAT":true,"DOG":true,"SHIP":false}"""
                },
            )
        }
    }

    @Test
    fun `vector lookup distinguishes disabled missing and wrong type`() {
        val current =
            booleanFeature(3).copy(
                value = VectorValue(mapOf("DOG" to true, "SHIP" to false)),
            )
        every { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, current.key) } returns current
        assertThat(service.getVectorElementInNamespace(namespaceId, current.key, null, "DOG").value).isTrue()
        assertThat(service.getVectorElementInNamespace(namespaceId, current.key, null, "SHIP").value).isFalse()
        assertThatThrownBy {
            service.getVectorElementInNamespace(namespaceId, current.key, null, "dog")
        }.isInstanceOf(NotFoundException::class.java)
        assertThatThrownBy {
            service.getVectorElementInNamespace(namespaceId, current.key, null, " ")
        }.isInstanceOf(DomainValidationException::class.java)
        every { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, current.key) } returns booleanFeature(3)
        assertThatThrownBy {
            service.getVectorElementInNamespace(namespaceId, current.key, null, "DOG")
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `vector creation rejects empty invalid oversized and mixed type values`() {
        val valid = CreateFeatureRequest("feat", FeatureType.VECTOR, vectorValues = mapOf("DOG" to false))
        val invalid =
            listOf(
                valid.copy(vectorValues = emptyMap()),
                valid.copy(vectorValues = mapOf(" " to false)),
                valid.copy(vectorValues = mapOf(" DOG" to false)),
                valid.copy(vectorValues = mapOf("x".repeat(256) to false)),
                valid.copy(vectorValues = (1..101).associate { "E$it" to false }),
                valid.copy(booleanValue = true),
                valid.copy(enumValue = "DOG"),
                valid.copy(enumOptions = listOf("DOG")),
                valid.copy(type = FeatureType.BOOLEAN, booleanValue = true),
                valid.copy(type = FeatureType.ENUM, enumValue = "DOG", enumOptions = listOf("DOG")),
            )
        invalid.forEach { request ->
            assertThatThrownBy { service.createFeature("blue", request) }.isInstanceOf(DomainValidationException::class.java)
        }
        verify(exactly = 0) { featureRepository.save(any()) }
    }

    @Test
    fun `vector patch rejects unknown elements stale versions and incompatible values`() {
        val current =
            booleanFeature(3).copy(
                value = VectorValue(mapOf("DOG" to false)),
            )
        every { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, current.key) } returns current
        listOf(
            PatchFeatureRequest(3, vectorValues = mapOf("CAT" to true)),
            PatchFeatureRequest(3, vectorValues = emptyMap()),
            PatchFeatureRequest(3, booleanValue = true),
            PatchFeatureRequest(3, enumValue = "DOG"),
        ).forEach { request ->
            assertThatThrownBy {
                service.patchFeature(
                    "blue",
                    current.key,
                    null,
                    request,
                )
            }.isInstanceOf(DomainValidationException::class.java)
        }
        assertThatThrownBy { service.patchFeature("blue", current.key, null, PatchFeatureRequest(2, vectorValues = mapOf("DOG" to true))) }
            .isInstanceOf(ConflictException::class.java)
        every { featureRepository.findByNamespaceIdAndGroupIdIsNullAndKey(namespaceId, current.key) } returns booleanFeature(3)
        assertThatThrownBy { service.patchFeature("blue", current.key, null, PatchFeatureRequest(3, vectorValues = mapOf("DOG" to true))) }
            .isInstanceOf(DomainValidationException::class.java)
        verify(exactly = 0) { featureRepository.save(any()) }
    }
}
