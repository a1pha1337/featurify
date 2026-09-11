package ru.a1pha1337.featurify.controller

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.a1pha1337.featurify.dto.AdminFeatureResponse
import ru.a1pha1337.featurify.dto.AuditLogResponse
import ru.a1pha1337.featurify.dto.CreateFeatureRequest
import ru.a1pha1337.featurify.dto.CreateTenantRequest
import ru.a1pha1337.featurify.dto.PatchFeatureRequest
import ru.a1pha1337.featurify.dto.TenantResponse
import ru.a1pha1337.featurify.dto.VersionRequest
import ru.a1pha1337.featurify.service.FeatureToggleService

@RestController
@RequestMapping("/api/v1")
class AdminFeatureController(private val service: FeatureToggleService) {
    @PostMapping("/tenants")
    @ResponseStatus(HttpStatus.CREATED)
    fun createTenant(@Valid @RequestBody request: CreateTenantRequest): TenantResponse =
        service.createTenant(request)

    @GetMapping("/tenants")
    fun listTenants(): List<TenantResponse> = service.listTenants()

    @PostMapping("/features")
    @ResponseStatus(HttpStatus.CREATED)
    fun createFeature(
        @RequestParam(required = false) tenant: String?,
        @Valid @RequestBody request: CreateFeatureRequest,
    ): AdminFeatureResponse = service.createFeature(tenant, request)

    @PatchMapping("/features/{key}")
    fun patchFeature(
        @RequestParam(required = false) tenant: String?,
        @PathVariable key: String,
        @Valid @RequestBody request: PatchFeatureRequest,
    ): AdminFeatureResponse = service.patchFeature(tenant, key, request)

    @PostMapping("/features/{key}/archive")
    fun archive(
        @RequestParam(required = false) tenant: String?,
        @PathVariable key: String,
        @Valid @RequestBody request: VersionRequest,
    ): AdminFeatureResponse = service.archive(tenant, key, request.version)

    @GetMapping("/features/{key}/history")
    fun history(
        @RequestParam(required = false) tenant: String?,
        @PathVariable key: String,
    ): List<AuditLogResponse> = service.history(tenant, key)
}
