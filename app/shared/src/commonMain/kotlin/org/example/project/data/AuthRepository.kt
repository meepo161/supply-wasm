package org.example.project.data

import org.example.project.dto.BootstrapRequest
import org.example.project.dto.LoginRequest
import org.example.project.dto.LoginResponse
import org.example.project.dto.MeResponse

interface AuthRepository {
    suspend fun login(request: LoginRequest): ApiResult<LoginResponse>
    suspend fun logout(): ApiResult<Unit>
    suspend fun me(): ApiResult<MeResponse>
    suspend fun bootstrap(request: BootstrapRequest): ApiResult<LoginResponse>
}
