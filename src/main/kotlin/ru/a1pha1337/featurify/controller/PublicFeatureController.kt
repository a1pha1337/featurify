package ru.a1pha1337.featurify.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.a1pha1337.featurify.dto.FeatureResponse
import ru.a1pha1337.featurify.dto.ResolveResponse
import ru.a1pha1337.featurify.service.FeatureToggleService

@RestController
@RequestMapping("/api/v1/tenants/{tenantKey}")
class PublicFeatureController(private val service: FeatureToggleService) {
    @GetMapping("/features")
    fun list(@PathVariable tenantKey: String): List<FeatureResponse> = service.listActive(tenantKey)

    @GetMapping("/features/{key}")
    fun get(@PathVariable tenantKey: String, @PathVariable key: String): FeatureResponse =
        service.getActive(tenantKey, key)

    @GetMapping("/features:resolve")
    fun resolve(
        @PathVariable tenantKey: String,
        @RequestParam keys: String,
    ): ResolveResponse = service.resolve(tenantKey, keys.split(',').map { it.trim() })
}
