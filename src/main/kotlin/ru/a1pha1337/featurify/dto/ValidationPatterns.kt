package ru.a1pha1337.featurify.dto

object ValidationPatterns {
    const val FEATURE_KEY = "^[a-z0-9.-]+$"
    const val FEATURE_GROUP = "^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$"
    const val TENANT_KEY = "^[a-z0-9]([a-z0-9.-]*[a-z0-9])?$"
}
