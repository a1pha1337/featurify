package ru.a1pha1337.featurify.repository

import org.springframework.data.repository.CrudRepository
import ru.a1pha1337.featurify.domain.Tenant
import java.util.UUID

interface TenantRepository : CrudRepository<Tenant, UUID> {
    fun findByKey(key: String): Tenant?
    fun findAllByOrderByKey(): List<Tenant>
}
