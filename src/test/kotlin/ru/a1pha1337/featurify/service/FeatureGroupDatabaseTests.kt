package ru.a1pha1337.featurify.service

import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowableOfType
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageRequest
import ru.a1pha1337.featurify.config.TimeConfiguration
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.dto.AdminFeatureResponse
import ru.a1pha1337.featurify.dto.CreateAccessTokenRequest
import ru.a1pha1337.featurify.dto.CreateFeatureGroupRequest
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreateNamespaceRequest
import ru.a1pha1337.featurify.dto.MoveFeatureRequest
import ru.a1pha1337.featurify.dto.PatchFeatureRequest
import ru.a1pha1337.featurify.security.ActorProvider
import java.util.UUID

/** Opt-in PostgreSQL test. Spring rolls back all data created by this test. */
@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FeatureToggleService::class, AccessTokenService::class, TimeConfiguration::class)
@EnabledIfEnvironmentVariable(named = "FEATURIFY_DB_TESTS", matches = "true")
class FeatureGroupDatabaseTests {
    @Autowired
    private lateinit var service: FeatureToggleService

    @Autowired
    private lateinit var jdbc: org.springframework.jdbc.core.JdbcTemplate

    @Autowired
    private lateinit var accessTokens: AccessTokenService

    @MockkBean(relaxed = true)
    private lateinit var actor: ActorProvider

    @Test
    fun `persist empty groups move between groups and global and filter paginated data`() {
        val namespace = service.createNamespace(CreateNamespaceRequest("test-${UUID.randomUUID()}", "Database test"))
        val source = service.createGroup(namespace.key, CreateFeatureGroupRequest("source", "Source"))
        val target = service.createGroup(namespace.key, CreateFeatureGroupRequest("target", "Target"))
        assertThat(service.listGroups(namespace.key).size).isEqualTo(2)
        val feature =
            service.createFeature(
                namespace.key,
                CreateFeatureRequest(
                    key = "checkout.enabled",
                    type = FeatureType.BOOLEAN,
                    group = source.key,
                    booleanValue = true,
                ),
            )
        val page = PageRequest.of(0, 20)
        assertThat(service.listForAdmin(namespace.key, page, null, source.key).totalElements).isEqualTo(1)
        assertThat(service.listForAdmin(namespace.key, page, null, target.key).totalElements).isEqualTo(0)

        val moved =
            service.moveFeature(namespace.key, feature.key, source.key, MoveFeatureRequest(feature.version, target.key))
        assertThat(moved.group).isEqualTo(target.key)
        assertThat(moved.version > feature.version).isTrue()
        assertThat(service.listForAdmin(namespace.key, page, null, source.key).totalElements).isEqualTo(0)
        assertThat(service.listForAdmin(namespace.key, page, "checkout", target.key).totalElements).isEqualTo(1)
        assertThat(service.listForAdmin(namespace.key, page, null, globalOnly = true).totalElements).isEqualTo(0)

        val global =
            service.moveFeature(namespace.key, feature.key, target.key, MoveFeatureRequest(moved.version, null))
        assertThat(global.group).isNull()
        assertThat(global.version > moved.version).isTrue()
        assertThat(service.listForAdmin(namespace.key, page, null, globalOnly = true).totalElements).isEqualTo(1)
        assertThat(service.listForAdmin(namespace.key, page, "checkout", globalOnly = true).totalElements).isEqualTo(1)
        assertThat(service.listForAdmin(namespace.key, page, null, target.key).totalElements).isEqualTo(0)
    }

    @Test
    fun `hard deletes cascade through features options and history and allow key reuse`() {
        every { actor.currentUsername() } returns "database-test"
        val namespace = service.createNamespace(CreateNamespaceRequest("test-${UUID.randomUUID()}", "Delete test"))
        val other = service.createNamespace(CreateNamespaceRequest("test-${UUID.randomUUID()}", "Other namespace"))
        val group = service.createGroup(namespace.key, CreateFeatureGroupRequest("group", "Group"))
        val otherGroup = service.createGroup(other.key, CreateFeatureGroupRequest("group", "Group"))

        fun create(
            key: String,
            scope: String?,
            namespaceKey: String = namespace.key,
        ): AdminFeatureResponse {
            val feature =
                service.createFeature(
                    namespaceKey,
                    CreateFeatureRequest(
                        key = key,
                        type = FeatureType.ENUM,
                        group = scope,
                        enumValue = "a",
                        enumOptions = listOf("a", "b"),
                    ),
                )
            return service.patchFeature(namespaceKey, key, scope, PatchFeatureRequest(feature.version, enumValue = "b"))
        }

        fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM $table WHERE namespace_id = ?", Long::class.java, namespace.id)

        fun featureId(feature: AdminFeatureResponse) =
            jdbc.queryForObject(
                "SELECT id FROM feature WHERE namespace_id = ? AND key = ?",
                UUID::class.java,
                namespace.id,
                feature.key,
            )!!

        fun assertDependentsGone(id: UUID) {
            for (table in listOf("feature_enum_option", "feature_audit_log")) {
                assertThat(jdbc.queryForObject("SELECT count(*) FROM $table WHERE feature_id = ?", Long::class.java, id)).isEqualTo(0L)
            }
        }

        val single = create("single", null)
        val singleId = featureId(single)
        assertThat(service.history(namespace.key, single.key, null).size).isEqualTo(1)
        service.deleteFeature(namespace.key, single.key, null, single.version)
        assertDependentsGone(singleId)
        catchThrowableOfType(NotFoundException::class.java) {
            service.getFeature(namespace.key, single.key, null)
        }.also { assertThat(it).isNotNull() }
        val recreated =
            service.createFeature(
                namespace.key,
                CreateFeatureRequest(key = single.key, type = FeatureType.BOOLEAN, booleanValue = true),
            )
        assertThat(service.history(namespace.key, recreated.key, null).isEmpty()).isTrue()
        val grouped = create("grouped", group.key)
        val groupedId = featureId(grouped)
        create("grouped", otherGroup.key, other.key)
        service.deleteGroup(namespace.key, group.key, group.version)
        assertThat(count("feature_group")).isEqualTo(0L)
        assertDependentsGone(groupedId)
        assertThat(count("feature")).isEqualTo(1L)
        assertThat(service.getFeature(other.key, "grouped", otherGroup.key).value).isEqualTo("b")
        val replacementGroup = service.createGroup(namespace.key, CreateFeatureGroupRequest(group.key, "Replacement"))
        val nestedId = featureId(create("nested", replacementGroup.key))
        val globalId = featureId(create("global", null))
        service.deleteNamespace(namespace.key)
        assertThat(count("feature_group")).isEqualTo(0L)
        assertThat(count("feature")).isEqualTo(0L)
        assertThat(count("feature_audit_log")).isEqualTo(0L)
        assertDependentsGone(nestedId)
        assertDependentsGone(globalId)
        catchThrowableOfType(NotFoundException::class.java) { service.listGroups(namespace.key) }.also { assertThat(it).isNotNull() }
        assertThat(service.listGroups(other.key).size).isEqualTo(1)
        assertThat(service.listNamespaces().any { it.key == "default" && it.defaultNamespace }).isTrue()
    }

    @Test
    fun `namespace tokens persist hashed allow multiple and cascade on namespace deletion`() {
        val namespace = service.createNamespace(CreateNamespaceRequest("test-${UUID.randomUUID()}", "Tokens"))
        val first = accessTokens.create(namespace.key, CreateAccessTokenRequest("one"))
        val second = accessTokens.create(namespace.key, CreateAccessTokenRequest("two"))
        assertThat(accessTokens.list(namespace.key).size).isEqualTo(2)
        assertThat(accessTokens.authenticate(first.token)).isEqualTo(namespace.id)
        assertThat(accessTokens.authenticate(second.token)).isEqualTo(namespace.id)
        val hash =
            jdbc.queryForObject(
                "SELECT token_hash FROM namespace_access_token WHERE id = ?",
                String::class.java,
                first.id,
            )!!
        assertThat(hash).isNotEqualTo(first.token)
        assertThat(hash.length).isEqualTo(60)
        accessTokens.revoke(namespace.key, first.id)
        assertThat(accessTokens.authenticate(first.token)).isNull()
        assertThat(accessTokens.authenticate(second.token)).isEqualTo(namespace.id)
        service.deleteNamespace(namespace.key)
        assertThat(accessTokens.authenticate(second.token)).isNull()
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM namespace_access_token WHERE namespace_id = ?",
                Long::class.java,
                namespace.id,
            ),
        ).isEqualTo(0L)
    }

    @Test
    fun `database rejects deleting default namespace directly`() {
        catchThrowableOfType(org.springframework.dao.DataIntegrityViolationException::class.java) {
            jdbc.update("DELETE FROM namespace WHERE key = 'default'")
        }.also { assertThat(it).isNotNull() }
    }

    @Test
    fun `database rejects removing default protection`() {
        catchThrowableOfType(org.springframework.dao.DataIntegrityViolationException::class.java) {
            jdbc.update("UPDATE namespace SET default_namespace = false, key = 'renamed' WHERE key = 'default'")
        }.also { assertThat(it).isNotNull() }
    }

    @Test
    fun `database rejects truncating namespaces`() {
        catchThrowableOfType(org.springframework.dao.DataIntegrityViolationException::class.java) {
            jdbc.execute("TRUNCATE namespace CASCADE")
        }.also { assertThat(it).isNotNull() }
    }
}
