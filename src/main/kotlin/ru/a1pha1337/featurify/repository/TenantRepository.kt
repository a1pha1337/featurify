package ru.a1pha1337.featurify.repository

import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.repository.CrudRepository
import ru.a1pha1337.featurify.domain.Tenant
import java.time.Instant
import java.util.UUID

interface TenantRepository : CrudRepository<Tenant, UUID> {
    fun findByKey(key: String): Tenant?
    fun findByDefaultTenantTrue(): Tenant?
    fun findAllByOrderByKey(): List<Tenant>

    @Modifying
    @Query("UPDATE tenant SET default_tenant = FALSE, updated_at = :updatedAt WHERE default_tenant = TRUE")
    fun clearDefault(@Param("updatedAt") updatedAt: Instant): Int
}
