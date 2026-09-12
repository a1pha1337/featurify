package ru.a1pha1337.featurify.dto

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValidationPatternsTests {
    @Test
    fun `namespace group and feature keys accept supported casing`() {
        val accepted = listOf("camelCase", "PascalCase", "kebab-case", "release2", "V2-api", "checkout.payment-provider")
        accepted.forEach { key ->
            assertTrue(Regex(ValidationPatterns.NAMESPACE_KEY).matches(key), "namespace: $key")
            assertTrue(Regex(ValidationPatterns.FEATURE_GROUP).matches(key), "group: $key")
            assertTrue(Regex(ValidationPatterns.FEATURE_KEY).matches(key), "feature: $key")
        }
    }

    @Test
    fun `keys reject invalid starts separators and punctuation`() {
        val rejected = listOf("2release", "snake_case", ".leading", "trailing.", "with..double", "with.-mixed", "with space", "with--double", "-leading", "trailing-", "ümlaut", "slash/value")
        rejected.forEach { key ->
            assertFalse(Regex(ValidationPatterns.NAMESPACE_KEY).matches(key), "namespace: $key")
            assertFalse(Regex(ValidationPatterns.FEATURE_GROUP).matches(key), "group: $key")
            assertFalse(Regex(ValidationPatterns.FEATURE_KEY).matches(key), "feature: $key")
        }
    }
}
