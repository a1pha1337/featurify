package ru.a1pha1337.featurify.controller

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.a1pha1337.featurify.dto.FeatureResponse
import ru.a1pha1337.featurify.dto.ResolveResponse
import ru.a1pha1337.featurify.dto.VectorElementResponse
import ru.a1pha1337.featurify.security.NamespacePrincipal
import ru.a1pha1337.featurify.service.FeatureToggleService

@RestController
@RequestMapping("/api/v1")
class PublicFeatureController(
    private val service: FeatureToggleService,
) {
    @GetMapping("/features")
    fun list(
        @AuthenticationPrincipal(errorOnInvalidType = true) principal: NamespacePrincipal,
        @RequestParam(required = false) namespace: String?,
        @PageableDefault(size = 20, sort = ["key"]) pageable: Pageable,
        @RequestParam(required = false) query: String?,
    ): Page<FeatureResponse> = service.listFeaturesInNamespace(principal.requireNamespace(namespace), pageable, query)

    @GetMapping("/features/{key}")
    fun get(
        @AuthenticationPrincipal(errorOnInvalidType = true) principal: NamespacePrincipal,
        @RequestParam(required = false) namespace: String?,
        @RequestParam(required = false) group: String?,
        @PathVariable key: String,
    ): FeatureResponse = service.getFeatureInNamespace(principal.requireNamespace(namespace), key, group)

    @GetMapping("/features/{key}/elements/{element}")
    fun getVectorElement(
        @AuthenticationPrincipal(errorOnInvalidType = true) principal: NamespacePrincipal,
        @RequestParam(required = false) namespace: String?,
        @RequestParam(required = false) group: String?,
        @PathVariable key: String,
        @PathVariable element: String,
    ): VectorElementResponse = service.getVectorElementInNamespace(principal.requireNamespace(namespace), key, group, element)

    @GetMapping("/features:resolve")
    fun resolve(
        @AuthenticationPrincipal(errorOnInvalidType = true) principal: NamespacePrincipal,
        @RequestParam(required = false) namespace: String?,
        @RequestParam(required = false) group: String?,
        @RequestParam keys: String,
    ): ResolveResponse = service.resolveInNamespace(principal.requireNamespace(namespace), keys.split(',').map { it.trim() }, group)
}
