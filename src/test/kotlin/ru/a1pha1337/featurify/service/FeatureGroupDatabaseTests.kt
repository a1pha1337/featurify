package ru.a1pha1337.featurify.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.bean.override.mockito.MockitoBean
import ru.a1pha1337.featurify.config.TimeConfiguration
import ru.a1pha1337.featurify.domain.FeatureStatus
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.dto.*
import ru.a1pha1337.featurify.security.ActorProvider
import java.util.UUID

/** Opt-in PostgreSQL test. Spring rolls back all data created by this test. */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FeatureToggleService::class, TimeConfiguration::class)
@EnabledIfEnvironmentVariable(named = "FEATURIFY_DB_TESTS", matches = "true")
class FeatureGroupDatabaseTests {
    @Autowired
    private lateinit var service: FeatureToggleService

    @MockitoBean
    private lateinit var actor: ActorProvider

    @Test
    fun `persist empty groups move between groups and global and filter paginated data`() {
        val tenant = service.createTenant(CreateTenantRequest("test-${UUID.randomUUID()}", "Database test"))
        val source = service.createGroup(tenant.key, CreateFeatureGroupRequest("source", "Source"))
        val target = service.createGroup(tenant.key, CreateFeatureGroupRequest("target", "Target"))
        assertEquals(2, service.listGroups(tenant.key).size)
        val feature = service.createFeature(tenant.key, CreateFeatureRequest(
            key = "checkout.enabled", type = FeatureType.BOOLEAN, group = source.key, booleanValue = true,
        ))
        val page = PageRequest.of(0, 20)
        val statuses = setOf(FeatureStatus.ACTIVE)
        assertEquals(1, service.listForAdmin(tenant.key, page, statuses, null, source.key).totalElements)
        assertEquals(0, service.listForAdmin(tenant.key, page, statuses, null, target.key).totalElements)

        val moved = service.moveFeature(tenant.key, feature.key, source.key, MoveFeatureRequest(feature.version, target.key))
        assertEquals(target.key, moved.group)
        assertTrue(moved.version > feature.version)
        assertEquals(0, service.listForAdmin(tenant.key, page, statuses, null, source.key).totalElements)
        assertEquals(1, service.listForAdmin(tenant.key, page, statuses, "checkout", target.key).totalElements)
        assertEquals(0, service.listForAdmin(tenant.key, page, statuses, null, globalOnly = true).totalElements)

        val global = service.moveFeature(tenant.key, feature.key, target.key, MoveFeatureRequest(moved.version, null))
        assertNull(global.group)
        assertTrue(global.version > moved.version)
        assertEquals(1, service.listForAdmin(tenant.key, page, statuses, null, globalOnly = true).totalElements)
        assertEquals(1, service.listForAdmin(tenant.key, page, statuses, "checkout", globalOnly = true).totalElements)
        assertEquals(0, service.listForAdmin(tenant.key, page, statuses, null, target.key).totalElements)
    }
}
