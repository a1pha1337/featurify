package ru.a1pha1337.featurify.service

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.a1pha1337.featurify.domain.Namespace
import ru.a1pha1337.featurify.domain.NamespaceAccessToken
import ru.a1pha1337.featurify.dto.AccessTokenResponse
import ru.a1pha1337.featurify.dto.CreateAccessTokenRequest
import ru.a1pha1337.featurify.dto.CreatedAccessTokenResponse
import ru.a1pha1337.featurify.repository.NamespaceAccessTokenRepository
import ru.a1pha1337.featurify.repository.NamespaceRepository
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import java.util.UUID

@Service
class AccessTokenService(
    private val tokens: NamespaceAccessTokenRepository,
    private val namespaces: NamespaceRepository,
    private val clock: Clock,
) {
    private val random = SecureRandom()
    private val encoder = BCryptPasswordEncoder(12)
    private val tokenPattern =
        Regex("^ft_([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.([A-Za-z0-9_-]{43})$")

    @Transactional
    fun create(
        namespaceKey: String,
        request: CreateAccessTokenRequest,
    ): CreatedAccessTokenResponse {
        val namespace = requireNamespace(namespaceKey)
        val name = request.name.trim()
        if (name.isEmpty() || name.length > 255) {
            throw DomainValidationException("Invalid token name", listOf("name" to "must contain 1-255 characters"))
        }
        // 256 random bits, encoded as 43 ASCII characters: below bcrypt's 72-byte limit.
        val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        val saved =
            tokens.save(
                NamespaceAccessToken(
                    namespaceId = namespace.id!!,
                    name = name,
                    tokenHash = encoder.encode(secret)!!,
                    createdAt = clock.instant(),
                ),
            )
        return CreatedAccessTokenResponse(saved.id!!, saved.name, saved.createdAt, "ft_${saved.id}.$secret")
    }

    @Transactional(readOnly = true)
    fun list(namespaceKey: String): List<AccessTokenResponse> =
        tokens
            .findAllByNamespaceIdOrderByCreatedAtDesc(requireNamespace(namespaceKey).id!!)
            .map { AccessTokenResponse(it.id!!, it.name, it.createdAt) }

    @Transactional
    fun revoke(
        namespaceKey: String,
        id: UUID,
    ) {
        val token =
            tokens.findByIdAndNamespaceId(id, requireNamespace(namespaceKey).id!!)
                ?: throw NotFoundException("Access token was not found")
        tokens.delete(token)
    }

    @Transactional(readOnly = true)
    fun authenticate(rawToken: String): UUID? = authenticateNamespace(rawToken)?.id

    @Transactional(readOnly = true)
    fun authenticateNamespace(rawToken: String): Namespace? {
        val match = tokenPattern.matchEntire(rawToken) ?: return null
        val token = tokens.findById(UUID.fromString(match.groupValues[1])).orElse(null) ?: return null
        if (!encoder.matches(match.groupValues[2], token.tokenHash)) return null
        val namespace = namespaces.findById(token.namespaceId).orElse(null) ?: return null
        return namespace.takeIf { it.active }
    }

    private fun requireNamespace(key: String) =
        namespaces.findByKey(key)
            ?: throw NotFoundException("Namespace '$key' was not found")
}
