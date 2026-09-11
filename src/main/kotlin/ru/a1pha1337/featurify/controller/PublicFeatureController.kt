package ru.a1pha1337.featurify.controller

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.a1pha1337.featurify.dto.FeatureResponse
import ru.a1pha1337.featurify.dto.ResolveResponse
import ru.a1pha1337.featurify.service.FeatureToggleService

@RestController
@RequestMapping("/api/v1")
class PublicFeatureController(private val service: FeatureToggleService) {
    @GetMapping("/features")
    fun list(
        @RequestParam(required = false) tenant: String?,
        @PageableDefault(size = 20, sort = ["key"]) pageable: Pageable,
        @RequestParam(required = false) query: String?,
    ): Page<FeatureResponse> = service.listActive(tenant, pageable, query)

    @GetMapping("/features/{key}")
    fun get(
        @RequestParam(required = false) tenant: String?,
        @RequestParam(required = false) group: String?,
        @PathVariable key: String,
    ): FeatureResponse = service.getActive(tenant, key, group)

    @GetMapping("/features:resolve")
    fun resolve(
        @RequestParam(required = false) tenant: String?,
        @RequestParam(required = false) group: String?,
        @RequestParam keys: String,
    ): ResolveResponse = service.resolve(tenant, keys.split(',').map { it.trim() }, group)
}
