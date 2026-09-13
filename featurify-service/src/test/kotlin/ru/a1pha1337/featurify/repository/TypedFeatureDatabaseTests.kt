package ru.a1pha1337.featurify.repository

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import ru.a1pha1337.featurify.domain.BooleanValue
import ru.a1pha1337.featurify.domain.EnumValue
import ru.a1pha1337.featurify.domain.Feature
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.domain.FeatureValue
import ru.a1pha1337.featurify.domain.VectorValue
import java.time.Instant
import java.util.UUID

@DataJdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(FeatureRepository::class)
@EnabledIfEnvironmentVariable(named = "FEATURIFY_DB_TESTS", matches = "true")
class TypedFeatureDatabaseTests {
    @Autowired private lateinit var features: FeatureRepository

    @Autowired private lateinit var jdbc: JdbcTemplate

    private fun namespaceId(): UUID = jdbc.queryForObject("select id from namespace where key = 'default'", UUID::class.java)!!

    private fun value(type: FeatureType): FeatureValue =
        when (type) {
            FeatureType.BOOLEAN -> BooleanValue(false)
            FeatureType.ENUM -> EnumValue("DOG", listOf("CAT", "DOG"))
            FeatureType.VECTOR -> VectorValue(mapOf("CAT" to true, "DOG" to false))
        }

    private fun feature(type: FeatureType): Feature =
        Feature(
            namespaceId = namespaceId(),
            key = "typed-${UUID.randomUUID()}",
            value = value(type),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )

    private fun rawFeature(type: FeatureType): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "insert into feature (id, namespace_id, key, type, created_at, updated_at) values (?, ?, ?, ?, now(), now())",
            id,
            namespaceId(),
            "raw-$id",
            type.name,
        )
        return id
    }

    private fun table(type: FeatureType): String =
        when (type) {
            FeatureType.BOOLEAN -> "feature_boolean_value"
            FeatureType.ENUM -> "feature_enum_value"
            FeatureType.VECTOR -> "feature_vector_element"
        }

    @ParameterizedTest
    @EnumSource(FeatureType::class)
    fun `all value types round trip with only matching child rows`(type: FeatureType) {
        val saved = features.save(feature(type))
        val loaded = features.findByNamespaceIdAndGroupIdIsNullAndKey(saved.namespaceId, saved.key)!!
        assertThat(loaded.value).isEqualTo(saved.value)
        assertThat(loaded.type).isEqualTo(type)
        FeatureType.entries.forEach { candidate ->
            val count = jdbc.queryForObject("select count(*) from ${table(candidate)} where feature_id = ?", Long::class.java, saved.id)!!
            assertThat(count).isEqualTo(
                if (candidate != type) {
                    0L
                } else if (type == FeatureType.VECTOR) {
                    2L
                } else {
                    1L
                },
            )
        }
        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE")
    }

    @ParameterizedTest
    @EnumSource(FeatureType::class)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `stale aggregate save rolls back child changes and preserves committed value`(type: FeatureType) {
        val original = features.save(feature(type))
        try {
            val next =
                when (val current = original.value) {
                    is BooleanValue -> current.copy(enabled = true)
                    is EnumValue -> current.copy(selected = "CAT")
                    is VectorValue -> current.copy(elements = current.elements + ("DOG" to true))
                }
            val updated = features.save(original.copy(value = next))
            assertThat(updated.version).isGreaterThan(original.version!!)
            assertThatThrownBy { features.save(original) }.isInstanceOf(OptimisticLockingFailureException::class.java)
            val loaded = features.findByNamespaceIdAndGroupIdIsNullAndKey(original.namespaceId, original.key)!!
            assertThat(loaded.value).isEqualTo(next)
            assertThat(loaded.version).isEqualTo(updated.version)
        } finally {
            features.findByNamespaceIdAndGroupIdIsNullAndKey(original.namespaceId, original.key)?.let(features::delete)
        }
    }

    @Test
    fun `feature table contains no typed value columns`() {
        val columns =
            jdbc.queryForList(
                "select column_name from information_schema.columns where table_schema = 'public' and table_name = 'feature'",
                String::class.java,
            )
        assertThat(columns).doesNotContain("boolean_value", "enum_value", "vector_value")
    }

    @ParameterizedTest
    @EnumSource(FeatureType::class)
    fun `database rejects feature without required typed value`(type: FeatureType) {
        rawFeature(type)
        assertThatThrownBy { jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE") }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @ParameterizedTest
    @EnumSource(FeatureType::class)
    fun `database rejects deleting required child value`(type: FeatureType) {
        val saved = features.save(feature(type))
        jdbc.update("delete from ${table(type)} where feature_id = ?", saved.id)
        assertThatThrownBy { jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE") }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @ParameterizedTest
    @EnumSource(FeatureType::class)
    fun `database rejects child value of another type`(type: FeatureType) {
        val saved = features.save(feature(type))
        assertThatThrownBy {
            if (type == FeatureType.BOOLEAN) {
                jdbc.update("insert into feature_enum_value (feature_id, value) values (?, 'DOG')", saved.id)
            } else {
                jdbc.update("insert into feature_boolean_value (feature_id, enabled) values (?, true)", saved.id)
            }
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `database requires selected enum value to reference an allowed option`() {
        val saved = features.save(feature(FeatureType.ENUM))
        jdbc.update("update feature_enum_value set value = 'SHIP' where feature_id = ?", saved.id)
        assertThatThrownBy { jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE") }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `database rejects removal of selected enum option`() {
        val saved = features.save(feature(FeatureType.ENUM))
        jdbc.update("delete from feature_enum_option where feature_id = ? and value = 'DOG'", saved.id)
        assertThatThrownBy { jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE") }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `database rejects more than one hundred vector elements`() {
        val id = rawFeature(FeatureType.VECTOR)
        jdbc.update(
            "insert into feature_vector_element (feature_id, element, enabled) select ?, 'element-' || n, false from generate_series(1, 101) n",
            id,
        )
        assertThatThrownBy { jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE") }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @ParameterizedTest
    @EnumSource(FeatureType::class)
    fun `database keeps feature type immutable`(type: FeatureType) {
        val saved = features.save(feature(type))
        val other = FeatureType.entries.first { it != type }
        assertThatThrownBy {
            jdbc.update("update feature set type = ? where id = ?", other.name, saved.id)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }
}
