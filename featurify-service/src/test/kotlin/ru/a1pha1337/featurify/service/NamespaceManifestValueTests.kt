package ru.a1pha1337.featurify.service

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import ru.a1pha1337.featurify.domain.BooleanValue
import ru.a1pha1337.featurify.domain.EnumValue
import ru.a1pha1337.featurify.domain.FeatureType
import ru.a1pha1337.featurify.domain.PayloadValue
import ru.a1pha1337.featurify.domain.VectorValue
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.ValuePolicy

class NamespaceManifestValueTests {
    @Test
    fun `payload managed replaces document and initial only retains runtime edits`() {
        val definition = CreateFeatureRequest("config", FeatureType.PAYLOAD, payloadValue = """{"timeout":30}""")
        val current = PayloadValue("""{"custom":true}""")
        assertThat(NamespaceManifestService.desiredValue(definition, current, ValuePolicy.InitialOnly)).isEqualTo(current)
        assertThat(
            NamespaceManifestService.desiredValue(definition, current, ValuePolicy.Managed),
        ).isEqualTo(PayloadValue(definition.payloadValue!!))
        assertThat(
            NamespaceManifestService.desiredValue(definition, null, ValuePolicy.InitialOnly),
        ).isEqualTo(PayloadValue(definition.payloadValue!!))
        assertThatThrownBy { NamespaceManifestService.desiredValue(definition, BooleanValue(true), ValuePolicy.Managed) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `managed restores value while initial only preserves runtime choice`() {
        val definition = CreateFeatureRequest("enabled", FeatureType.BOOLEAN, booleanValue = false)
        assertThat(
            NamespaceManifestService.desiredValue(definition, BooleanValue(true), ValuePolicy.Managed),
        ).isEqualTo(BooleanValue(false))
        assertThat(
            NamespaceManifestService.desiredValue(definition, BooleanValue(true), ValuePolicy.InitialOnly),
        ).isEqualTo(BooleanValue(true))
        assertThat(NamespaceManifestService.desiredValue(definition, null, ValuePolicy.InitialOnly)).isEqualTo(BooleanValue(false))
    }

    @Test
    fun `vector preserves existing states initializes new elements and prunes removed elements`() {
        val definition = CreateFeatureRequest("methods", FeatureType.VECTOR, vectorValues = mapOf("CARD" to false, "WIRE" to true))
        val current = VectorValue(mapOf("CARD" to true, "CASH" to false))
        assertThat(NamespaceManifestService.desiredValue(definition, current, ValuePolicy.InitialOnly))
            .isEqualTo(VectorValue(mapOf("CARD" to true, "WIRE" to true)))
        assertThat(NamespaceManifestService.desiredValue(definition, current, ValuePolicy.Managed))
            .isEqualTo(VectorValue(definition.vectorValues))
    }

    @Test
    fun `enum rejects removal of runtime selection instead of silently changing it`() {
        val definition = CreateFeatureRequest("provider", FeatureType.ENUM, enumValue = "stripe", enumOptions = listOf("stripe"))
        val current = EnumValue("paypal", listOf("stripe", "paypal"))
        assertThatThrownBy { NamespaceManifestService.desiredValue(definition, current, ValuePolicy.InitialOnly) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(
            NamespaceManifestService.desiredValue(definition, current, ValuePolicy.Managed),
        ).isEqualTo(EnumValue("stripe", listOf("stripe")))
    }

    @Test
    fun `existing type cannot be replaced`() {
        val definition = CreateFeatureRequest("enabled", FeatureType.BOOLEAN, booleanValue = false)
        assertThatThrownBy { NamespaceManifestService.desiredValue(definition, EnumValue("a", listOf("a")), ValuePolicy.Managed) }
            .isInstanceOf(ConflictException::class.java)
    }
}
