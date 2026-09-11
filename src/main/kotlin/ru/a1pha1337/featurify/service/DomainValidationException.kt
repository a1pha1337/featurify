package ru.a1pha1337.featurify.service

class DomainValidationException(
    message: String,
    val violations: List<Pair<String, String>>,
) : RuntimeException(message)
