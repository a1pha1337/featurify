package ru.a1pha1337.featurify.controller

import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.a1pha1337.featurify.dto.ApplyNamespaceManifestRequest
import ru.a1pha1337.featurify.dto.ManifestApplyResponse
import ru.a1pha1337.featurify.dto.ReleaseNamespaceManifestRequest
import ru.a1pha1337.featurify.service.NamespaceManifestService

@RestController
@RequestMapping("/api/v1/operator/namespaces")
class NamespaceManifestController(
    private val service: NamespaceManifestService,
) {
    @PutMapping("/{key}")
    fun apply(
        @PathVariable key: String,
        @RequestBody request: ApplyNamespaceManifestRequest,
        authentication: Authentication,
    ): ManifestApplyResponse = service.apply(key, request, operatorPrincipal(authentication))

    @PostMapping("/{key}/cleanup")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun release(
        @PathVariable key: String,
        @RequestBody request: ReleaseNamespaceManifestRequest,
        authentication: Authentication,
    ) = service.release(key, request, operatorPrincipal(authentication))

    private fun operatorPrincipal(authentication: Authentication): String {
        val jwt = authentication.principal as? Jwt ?: throw AccessDeniedException("Operator JWT required")
        return jwt.subject ?: throw AccessDeniedException("JWT subject required")
    }
}
