package ru.a1pha1337.featurify.demo

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.a1pha1337.featurify.client.FeaturifyClient
import ru.a1pha1337.featurify.client.autoconfigure.FeaturifyGrpcProperties
import ru.a1pha1337.featurify.client.service.BooleanFeatureService
import ru.a1pha1337.featurify.client.service.EnumFeatureService
import ru.a1pha1337.featurify.client.service.PayloadFeatureService
import ru.a1pha1337.featurify.client.service.VectorFeatureService

@RestController
@RequestMapping("/diagnostics")
class DiagnosticController(
    private val client: FeaturifyClient,
    private val booleans: BooleanFeatureService,
    private val enums: EnumFeatureService,
    private val vectors: VectorFeatureService,
    private val properties: FeaturifyGrpcProperties,
    private val payloads: PayloadFeatureService,
) {
    @GetMapping("/client")
    fun client(): ClientDiagnostic =
        ClientDiagnostic(
            host = properties.host,
            port = properties.port,
            tls = properties.isTls,
            timeout = properties.timeout.toString(),
            defaultGroup = effectiveGroup(null),
            cache = CacheDiagnostic(properties.cache.ttl.toString(), properties.cache.maximumSize),
        )

    @GetMapping("/features/boolean/{key}")
    fun booleanFeature(
        @PathVariable key: String,
        @RequestParam(required = false) group: String?,
        @RequestParam(defaultValue = "true") cached: Boolean,
    ): FeatureDiagnostic<Boolean> =
        diagnose("BOOLEAN", key, group, null, cached) { resolvedGroup ->
            val response =
                if (cached) booleans.getFeature(key, resolvedGroup) else client.getBooleanFeature(key, resolvedGroup)
            response.value to response.version
        }

    @GetMapping("/features/enum/{key}")
    fun enumFeature(
        @PathVariable key: String,
        @RequestParam(required = false) group: String?,
        @RequestParam(defaultValue = "true") cached: Boolean,
    ): FeatureDiagnostic<String> =
        diagnose("ENUM", key, group, null, cached) { resolvedGroup ->
            val response =
                if (cached) enums.getFeature(key, resolvedGroup) else client.getEnumFeature(key, resolvedGroup)
            response.value to response.version
        }

    @GetMapping("/features/vector/{key}/{element}")
    fun vectorFeature(
        @PathVariable key: String,
        @PathVariable element: String,
        @RequestParam(required = false) group: String?,
        @RequestParam(defaultValue = "true") cached: Boolean,
    ): FeatureDiagnostic<Boolean> =
        diagnose("VECTOR", key, group, element, cached) { resolvedGroup ->
            val response =
                if (cached) {
                    vectors.getFeature(key, element, resolvedGroup)
                } else {
                    client.getVectorFeature(key, element, resolvedGroup)
                }
            response.value to response.version
        }

    @GetMapping("/features/payload/{key}")
    fun payloadFeature(
        @PathVariable key: String,
        @RequestParam(required = false) group: String?,
        @RequestParam(defaultValue = "true") cached: Boolean,
    ): FeatureDiagnostic<String> =
        diagnose("PAYLOAD", key, group, null, cached) { resolvedGroup ->
            val response = if (cached) payloads.getFeature(key, resolvedGroup) else client.getPayloadFeature(key, resolvedGroup)
            response.value to response.version
        }

    private fun effectiveGroup(group: String?): String? = (group ?: properties.defaultGroup)?.takeUnless { it.isEmpty() }

    private fun <T> diagnose(
        type: String,
        key: String,
        group: String?,
        element: String?,
        cached: Boolean,
        read: (String?) -> Pair<T, Long>,
    ): FeatureDiagnostic<T> {
        val resolvedGroup = effectiveGroup(group)
        val started = System.nanoTime()
        val (value, version) = read(resolvedGroup)
        return FeatureDiagnostic(
            type = type,
            key = key,
            group = resolvedGroup,
            element = element,
            value = value,
            version = version,
            cacheAllowed = cached && !properties.cache.ttl.isZero,
            elapsedMicros = (System.nanoTime() - started) / 1000,
        )
    }
}
