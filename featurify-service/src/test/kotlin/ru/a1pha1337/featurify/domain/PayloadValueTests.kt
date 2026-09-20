package ru.a1pha1337.featurify.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class PayloadValueTests {
    @ParameterizedTest
    @ValueSource(strings = ["{}", "[]", "null", "true", "42", "\"hello\"", "[false,null,{\"a\":1}]"])
    fun `supports every JSON value including explicit null`(json: String) {
        val value = PayloadValue(json)
        assertThat(value.json).isEqualTo(json)
        assertThat(value.publicValue().toString()).isEqualTo(json)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " ", "{", "undefined", "{} []", "{\"a\":1,\"a\":2}", "[1,]", "NaN", "/* comment */ {}"])
    fun `rejects malformed ambiguous and multiple documents`(json: String) {
        assertThatThrownBy { PayloadValue(json) }.isInstanceOf(RuntimeException::class.java)
    }

    @Test
    fun `canonicalizes nested object order without rounding numbers or changing arrays`() {
        val first = PayloadValue("""{ "z": [{"b": 123456789012345678901234567890, "a": 0.123456789012345678901}], "a": null }""")
        val second = PayloadValue("""{"a":null,"z":[{"a":0.123456789012345678901,"b":123456789012345678901234567890}]}""")
        assertThat(first).isEqualTo(second)
        assertThat(first.json).contains("0.123456789012345678901", "123456789012345678901234567890")
        assertThat(PayloadValue("[1,2]")).isNotEqualTo(PayloadValue("[2,1]"))
    }

    @Test
    fun `enforces document size at boundary`() {
        assertThat(PayloadValue("\"" + "a".repeat(65534) + "\"").json).hasSize(65536)
        assertThatThrownBy { PayloadValue("\"" + "a".repeat(65535) + "\"") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
