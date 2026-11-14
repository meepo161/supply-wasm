package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val login: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val user: UserDto
)

@Serializable
data class MeResponse(
    val user: UserDto
)

@Serializable
data class BootstrapRequest(
    val login: String,
    val password: String,
    val fullName: String
)

@Serializable
data class ChangePasswordRequest(
    val currentPassword: String,
    val newPassword: String
)
