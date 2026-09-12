package ru.a1pha1337.featurify.dto

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ValidationPatternsTests {
    @Test
    fun `namespace group and feature keys accept supported casing`() {
        val accepted =
            listOf("camelCase", "PascalCase", "kebab-case", "release2", "V2-api", "checkout.payment-provider")
        accepted.forEach { key ->
            assertThat(Regex(ValidationPatterns.NAMESPACE_KEY).matches(key)).withFailMessage("namespace: $key").isTrue()
            assertThat(Regex(ValidationPatterns.FEATURE_GROUP).matches(key)).withFailMessage("group: $key").isTrue()
            assertThat(Regex(ValidationPatterns.FEATURE_KEY).matches(key)).withFailMessage("feature: $key").isTrue()
        }
    }

    @Test
    fun `keys reject invalid starts separators and punctuation`() {
        val rejected =
            listOf(
                "2release",
                "snake_case",
                ".leading",
                "trailing.",
                "with..double",
                "with.-mixed",
                "with space",
                "with--double",
                "-leading",
                "trailing-",
                "ümlaut",
                "slash/value",
            )
        rejected.forEach { key ->
            assertThat(Regex(ValidationPatterns.NAMESPACE_KEY).matches(key)).withFailMessage("namespace: $key").isFalse()
            assertThat(Regex(ValidationPatterns.FEATURE_GROUP).matches(key)).withFailMessage("group: $key").isFalse()
            assertThat(Regex(ValidationPatterns.FEATURE_KEY).matches(key)).withFailMessage("feature: $key").isFalse()
        }
    }
}
