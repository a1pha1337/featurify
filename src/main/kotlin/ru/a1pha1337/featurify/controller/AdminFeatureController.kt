package ru.a1pha1337.featurify.controller

import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
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
import ru.a1pha1337.featurify.dto.CreateFeatureGroupRequest
import ru.a1pha1337.featurify.dto.FeatureGroupResponse
import ru.a1pha1337.featurify.dto.CreateTenantRequest
import ru.a1pha1337.featurify.dto.MoveFeatureRequest
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

    @DeleteMapping("/tenants/{tenantKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteTenant(@PathVariable tenantKey: String) = service.deleteTenant(tenantKey)

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    fun createGroup(
        @RequestParam(required = false) tenant: String?,
        @Valid @RequestBody request: CreateFeatureGroupRequest,
    ): FeatureGroupResponse = service.createGroup(tenant, request)

    @GetMapping("/groups")
    fun listGroups(@RequestParam(required = false) tenant: String?): List<FeatureGroupResponse> =
        service.listGroups(tenant)

    @DeleteMapping("/groups/{groupKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteGroup(
        @RequestParam(required = false) tenant: String?,
        @PathVariable groupKey: String,
        @Valid @RequestBody request: VersionRequest,
    ) = service.deleteGroup(tenant, groupKey, request.version)

    @PostMapping("/features")
    @ResponseStatus(HttpStatus.CREATED)
    fun createFeature(
        @RequestParam(required = false) tenant: String?,
        @Valid @RequestBody request: CreateFeatureRequest,
    ): AdminFeatureResponse = service.createFeature(tenant, request)

    @PatchMapping("/features/{key}")
    fun patchFeature(
        @RequestParam(required = false) tenant: String?,
        @RequestParam(required = false) group: String?,
        @PathVariable key: String,
        @Valid @RequestBody request: PatchFeatureRequest,
    ): AdminFeatureResponse = service.patchFeature(tenant, key, group, request)

    @PatchMapping("/features/{key}/group")
    fun moveFeature(
        @RequestParam(required = false) tenant: String?,
        @RequestParam(required = false) group: String?,
        @PathVariable key: String,
        @Valid @RequestBody request: MoveFeatureRequest,
    ): AdminFeatureResponse = service.moveFeature(tenant, key, group, request)

    @DeleteMapping("/features/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteFeature(
        @RequestParam(required = false) tenant: String?,
        @RequestParam(required = false) group: String?,
        @PathVariable key: String,
        @Valid @RequestBody request: VersionRequest,
    ) = service.deleteFeature(tenant, key, group, request.version)

    @GetMapping("/features/{key}/history")
    fun history(
        @RequestParam(required = false) tenant: String?,
        @RequestParam(required = false) group: String?,
        @PathVariable key: String,
    ): List<AuditLogResponse> = service.history(tenant, key, group)
}
