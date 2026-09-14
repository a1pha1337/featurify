package ru.a1pha1337.featurify.repository

import ru.a1pha1337.featurify.dto.ManifestOwner
import ru.a1pha1337.featurify.dto.NamespaceManifest
import java.util.UUID

data class ManifestBinding(
    val id: UUID,
    val namespaceKey: String,
    val namespaceId: UUID?,
    val owner: ManifestOwner,
    val principal: String,
    val generation: Long,
    val spec: NamespaceManifest?,
    val groups: Set<UUID> = emptySet(),
    val features: Set<UUID> = emptySet(),
    val released: Boolean = false,
)
