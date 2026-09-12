package ru.a1pha1337.featurify.repository

import org.springframework.data.repository.CrudRepository
import ru.a1pha1337.featurify.domain.Namespace
import java.util.UUID

interface NamespaceRepository : CrudRepository<Namespace, UUID> {
    fun findByKey(key: String): Namespace?

    fun findByDefaultNamespaceTrue(): Namespace?

    fun findAllByOrderByKey(): List<Namespace>
}
