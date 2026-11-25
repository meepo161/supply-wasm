package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
enum class ErpEventDomain {
    TASK,
    PROJECT,
    WMS,
    DEMAND,
    SUPPLIER,
    PRODUCTION
}

@Serializable
data class ErpEventDto(
    val domain: ErpEventDomain,
    val action: String,
    val entityId: Long? = null
)
