package ru.a1pha1337.featurify.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.a1pha1337.featurify.dto.ManifestOwner
import ru.a1pha1337.featurify.dto.NamespaceManifest
import tools.jackson.databind.ObjectMapper
import java.util.UUID

@Repository
class ManifestRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
) {
    // The same transaction lock is taken by every administrative configuration writer.
    // Hash collisions only serialize unrelated namespaces; they cannot mix their data.
    fun lock(namespaceKey: String) {
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", { rs, _ -> rs.getObject(1) }, namespaceKey)
    }

    fun byId(id: UUID): ManifestBinding? = find("id = ?", id)

    fun byNamespace(id: UUID): ManifestBinding? = find("namespace_id = ? and not released", id)

    private fun find(
        condition: String,
        argument: UUID,
    ): ManifestBinding? =
        jdbc
            .query(
                "select * from namespace_manifest where $condition",
                { rs, _ ->
                    ManifestBinding(
                        id = rs.getObject("id", UUID::class.java),
                        namespaceKey = rs.getString("namespace_key"),
                        namespaceId = rs.getObject("namespace_id", UUID::class.java),
                        owner = mapper.readValue(rs.getString("owner"), ManifestOwner::class.java),
                        principal = rs.getString("principal"),
                        generation = rs.getLong("generation"),
                        spec = rs.getString("spec")?.let { mapper.readValue(it, NamespaceManifest::class.java) },
                        groups = mapper.readValue(rs.getString("managed_groups"), Array<UUID>::class.java).toSet(),
                        features = mapper.readValue(rs.getString("managed_features"), Array<UUID>::class.java).toSet(),
                        released = rs.getBoolean("released"),
                    )
                },
                argument,
            ).singleOrNull()

    fun save(binding: ManifestBinding) {
        jdbc.update(
            """
            insert into namespace_manifest
                (id, namespace_key, namespace_id, owner, principal, generation, spec, managed_groups, managed_features, released)
            values (?, ?, ?, ?::jsonb, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
            on conflict (id) do update set namespace_id = excluded.namespace_id, generation = excluded.generation,
                spec = excluded.spec, managed_groups = excluded.managed_groups,
                managed_features = excluded.managed_features, released = excluded.released
            """.trimIndent(),
            binding.id,
            binding.namespaceKey,
            binding.namespaceId,
            mapper.writeValueAsString(binding.owner),
            binding.principal,
            binding.generation,
            binding.spec?.let { mapper.writeValueAsString(it) },
            mapper.writeValueAsString(binding.groups),
            mapper.writeValueAsString(binding.features),
            binding.released,
        )
    }

    fun audit(
        binding: ManifestBinding,
        action: String,
    ) {
        jdbc.update(
            "insert into namespace_manifest_audit (binding_id, generation, action, principal) values (?, ?, ?, ?)",
            binding.id,
            binding.generation,
            action,
            binding.principal,
        )
    }
}
