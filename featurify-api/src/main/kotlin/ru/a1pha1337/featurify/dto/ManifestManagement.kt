package ru.a1pha1337.featurify.dto

data class ManifestManagement(
    val ownershipPolicy: OwnershipPolicy = OwnershipPolicy.Exclusive,
    val valuePolicy: ValuePolicy = ValuePolicy.Managed,
    val deletionPolicy: DeletionPolicy = DeletionPolicy.Retain,
    val adoptExisting: Boolean = false,
)
