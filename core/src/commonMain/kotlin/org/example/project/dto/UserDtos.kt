package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
data class UserDto(
    val id: Long,
    val login: String,
    val fullName: String,
    val email: String? = null,
    val departmentId: Long? = null,
    val active: Boolean = true,
    val createdAt: String? = null,
    val version: Long = 0,
    val permissions: List<String> = emptyList()
)

@Serializable
data class UserOptionDto(
    val id: Long,
    val fullName: String
)

@Serializable
data class CreateUserRequest(
    val login: String,
    val password: String,
    val fullName: String,
    val email: String? = null,
    val departmentId: Long? = null,
    val roleIds: List<Long> = emptyList()
)

@Serializable
data class UpdateUserRequest(
    val fullName: String? = null,
    val email: String? = null,
    val departmentId: Long? = null,
    val roleIds: List<Long>? = null,
    val active: Boolean? = null,
    val version: Long
)

fun UserDto.hasPermission(resource: String, action: String): Boolean =
    permissions.any { it == "*:*" || it == "$resource:*" || it == "$resource:$action" }
