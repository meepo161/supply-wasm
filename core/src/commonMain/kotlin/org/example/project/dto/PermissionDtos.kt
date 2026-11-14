package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
data class PermissionDto(
    val id: Long,
    val roleId: Long,
    val resource: String,
    val action: String,
    val scope: String? = null
)

@Serializable
data class CreatePermissionRequest(
    val resource: String,
    val action: String,
    val scope: String? = null
)
