package org.example.project.ui.auth

import org.example.project.dto.ApiError
import org.example.project.dto.UserDto

data class AuthUiState(
    val currentUser: UserDto? = null,
    val permissions: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: ApiError? = null,
    val passwordChangeLoading: Boolean = false,
    val passwordChangeError: ApiError? = null,
    val passwordChangeSuccess: Boolean = false,
    /** В базе нет пользователей — показать создание первого администратора (UX-07). */
    val bootstrapRequired: Boolean = false,
)
