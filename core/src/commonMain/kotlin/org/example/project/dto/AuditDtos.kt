package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
data class AuditLogEntryDto(
    val id: Long,
    val userId: Long? = null,
    val userName: String? = null,
    val action: String,
    val entityType: String,
    val entityId: Long,
    val payloadJson: String = "{}",
    val createdAt: String
)

@Serializable
data class HealthDto(
    val status: String,
    val database: String,
    val timestamp: String
)
