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

    @Autowired
    private lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

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
        assertEquals(1, service.listForAdmin(tenant.key, page, null, source.key).totalElements)
        assertEquals(0, service.listForAdmin(tenant.key, page, null, target.key).totalElements)

        val moved = service.moveFeature(tenant.key, feature.key, source.key, MoveFeatureRequest(feature.version, target.key))
        assertEquals(target.key, moved.group)
        assertTrue(moved.version > feature.version)
        assertEquals(0, service.listForAdmin(tenant.key, page, null, source.key).totalElements)
        assertEquals(1, service.listForAdmin(tenant.key, page, "checkout", target.key).totalElements)
        assertEquals(0, service.listForAdmin(tenant.key, page, null, globalOnly = true).totalElements)

        val global = service.moveFeature(tenant.key, feature.key, target.key, MoveFeatureRequest(moved.version, null))
        assertNull(global.group)
        assertTrue(global.version > moved.version)
        assertEquals(1, service.listForAdmin(tenant.key, page, null, globalOnly = true).totalElements)
        assertEquals(1, service.listForAdmin(tenant.key, page, "checkout", globalOnly = true).totalElements)
        assertEquals(0, service.listForAdmin(tenant.key, page, null, target.key).totalElements)
    }

    @Test
    fun `hard deletes cascade through features options and history and allow key reuse`() {
        org.mockito.Mockito.`when`(actor.currentUsername()).thenReturn("database-test")
        val tenant = service.createTenant(CreateTenantRequest("test-${UUID.randomUUID()}", "Delete test"))
        val other = service.createTenant(CreateTenantRequest("test-${UUID.randomUUID()}", "Other tenant"))
        val group = service.createGroup(tenant.key, CreateFeatureGroupRequest("group", "Group"))
        val otherGroup = service.createGroup(other.key, CreateFeatureGroupRequest("group", "Group"))
        fun create(key: String, scope: String?, tenantKey: String = tenant.key): AdminFeatureResponse {
            val feature = service.createFeature(tenantKey, CreateFeatureRequest(
                key = key, type = FeatureType.ENUM, group = scope, enumValue = "a", enumOptions = listOf("a", "b"),
            ))
            return service.patchFeature(tenantKey, key, scope, PatchFeatureRequest(feature.version, enumValue = "b"))
        }
        fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table WHERE tenant_id = ?", Long::class.java, tenant.id)
        fun featureId(feature: AdminFeatureResponse) = jdbc.queryForObject(
            "SELECT id FROM feature WHERE tenant_id = ? AND key = ?", UUID::class.java, tenant.id, feature.key,
        )!!
        fun assertDependentsGone(id: UUID) {
            for (table in listOf("feature_enum_option", "feature_audit_log")) {
                assertEquals(0L, jdbc.queryForObject("SELECT count(*) FROM $table WHERE feature_id = ?", Long::class.java, id))
            }
        }
        val single = create("single", null)
        val singleId = featureId(single)
        assertEquals(1, service.history(tenant.key, single.key, null).size)
        service.deleteFeature(tenant.key, single.key, null, single.version)
        assertDependentsGone(singleId)
        assertThrows(NotFoundException::class.java) { service.getFeature(tenant.key, single.key, null) }
        val recreated = service.createFeature(tenant.key, CreateFeatureRequest(key = single.key, type = FeatureType.BOOLEAN, booleanValue = true))
        assertTrue(service.history(tenant.key, recreated.key, null).isEmpty())
        val grouped = create("grouped", group.key)
        val groupedId = featureId(grouped)
        create("grouped", otherGroup.key, other.key)
        service.deleteGroup(tenant.key, group.key, group.version)
        assertEquals(0L, count("feature_group"))
        assertDependentsGone(groupedId)
        assertEquals(1L, count("feature"))
        assertEquals("b", service.getFeature(other.key, "grouped", otherGroup.key).value)
        val replacementGroup = service.createGroup(tenant.key, CreateFeatureGroupRequest(group.key, "Replacement"))
        val nestedId = featureId(create("nested", replacementGroup.key))
        val globalId = featureId(create("global", null))
        service.deleteTenant(tenant.key)
        assertEquals(0L, count("feature_group"))
        assertEquals(0L, count("feature"))
        assertEquals(0L, count("feature_audit_log"))
        assertDependentsGone(nestedId)
        assertDependentsGone(globalId)
        assertThrows(NotFoundException::class.java) { service.listGroups(tenant.key) }
        assertEquals(1, service.listGroups(other.key).size)
        assertTrue(service.listTenants().any { it.key == "default" && it.defaultTenant })
    }

    @Test
    fun `database rejects deleting default tenant directly`() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            jdbc.update("DELETE FROM tenant WHERE key = 'default'")
        }
    }

    @Test
    fun `database rejects removing default protection`() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            jdbc.update("UPDATE tenant SET default_tenant = false, key = 'renamed' WHERE key = 'default'")
        }
    }

    @Test
    fun `database rejects truncating tenants`() {
        assertThrows(org.springframework.dao.DataIntegrityViolationException::class.java) {
            jdbc.execute("TRUNCATE tenant CASCADE")
        }
    }
}
