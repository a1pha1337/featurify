package ru.a1pha1337.featurify.controller

import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.a1pha1337.featurify.dto.AccessTokenResponse
import ru.a1pha1337.featurify.dto.CreateAccessTokenRequest
import ru.a1pha1337.featurify.dto.CreatedAccessTokenResponse
import ru.a1pha1337.featurify.service.AccessTokenService
import java.util.UUID

@RestController
@RequestMapping("/api/v1/namespaces/{namespaceKey}/tokens")
class AccessTokenController(
    private val service: AccessTokenService,
) {
    @PostMapping
    fun create(
        @PathVariable namespaceKey: String,
        @Valid @RequestBody request: CreateAccessTokenRequest,
    ): ResponseEntity<CreatedAccessTokenResponse> =
        ResponseEntity
            .status(HttpStatus.CREATED)
            .cacheControl(CacheControl.noStore())
            .body(service.create(namespaceKey, request))

    @GetMapping
    fun list(
        @PathVariable namespaceKey: String,
    ): List<AccessTokenResponse> = service.list(namespaceKey)

    @DeleteMapping("/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(
        @PathVariable namespaceKey: String,
        @PathVariable tokenId: UUID,
    ) = service.revoke(namespaceKey, tokenId)
}
