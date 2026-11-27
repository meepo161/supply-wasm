package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
enum class ErpEventDomain {
    TASK,
    PROJECT,
    WMS,
    DEMAND,
    SUPPLIER,
    PRODUCTION,
    BOM,
    DOCUMENT,
    APPROVAL,
    ADMIN,
    PRESENCE
}

@Serializable
data class ErpEventDto(
    val domain: ErpEventDomain,
    val action: String,
    val entityId: Long? = null
)

@Serializable
data class PresenceSnapshotDto(
    val onlineUserIds: List<Long> = emptyList()
)
