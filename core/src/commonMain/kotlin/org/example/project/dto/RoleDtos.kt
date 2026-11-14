package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
data class RoleDto(
    val id: Long,
    val code: String,
    val name: String,
    val description: String? = null
)

@Serializable
data class CreateRoleRequest(
    val code: String,
    val name: String,
    val description: String? = null,
    val permissions: List<CreatePermissionRequest> = emptyList()
)
