package org.example.project.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.AuthRepository
import org.example.project.dto.BootstrapRequest
import org.example.project.dto.LoginRequest

class AuthViewModel(private val repository: AuthRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState(isLoading = true))
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        checkSession()
    }

    fun checkSession() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val result = repository.me()
            _uiState.value = when (result) {
                is ApiResult.Success -> AuthUiState(
                    currentUser = result.data.user,
                    permissions = result.data.user.permissions.toSet(),
                    isLoading = false,
                    error = null
                )
                is ApiResult.Error -> AuthUiState(
                    currentUser = null,
                    permissions = emptySet(),
                    isLoading = false,
                    error = if (result.statusCode == 401) null else result.error
                )
            }
        }
    }

    fun login(login: String, password: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val result = repository.login(LoginRequest(login, password))
            _uiState.value = when (result) {
                is ApiResult.Success -> AuthUiState(
                    currentUser = result.data.user,
                    permissions = result.data.user.permissions.toSet(),
                    isLoading = false,
                    error = null
                )
                is ApiResult.Error -> _uiState.value.copy(
                    isLoading = false,
                    error = result.error
                )
            }
        }
    }

    fun logout() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            repository.logout()
            _uiState.value = AuthUiState(currentUser = null, permissions = emptySet(), isLoading = false, error = null)
        }
    }

    fun bootstrap(login: String, password: String, fullName: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            val result = repository.bootstrap(
                BootstrapRequest(login, password, fullName)
            )
            _uiState.value = when (result) {
                is ApiResult.Success -> AuthUiState(
                    currentUser = result.data.user,
                    permissions = result.data.user.permissions.toSet(),
                    isLoading = false,
                    error = null
                )
                is ApiResult.Error -> _uiState.value.copy(
                    isLoading = false,
                    error = result.error
                )
            }
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
