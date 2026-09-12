package ru.a1pha1337.featurify.dto

object ValidationPatterns {
    // ASCII letters support camelCase and PascalCase; '-' and '.' are internal
    // separators for kebab-case and dotted names. Digits are allowed after the
    // first letter. Separators cannot start/end a key or occur consecutively.
    const val FEATURE_KEY = "^[A-Za-z](?:[A-Za-z0-9]|[.-][A-Za-z0-9])*$"
    const val FEATURE_GROUP = FEATURE_KEY
    const val NAMESPACE_KEY = FEATURE_KEY
}
