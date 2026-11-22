package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.BootstrapRequest
import org.example.project.dto.ChangePasswordRequest
import org.example.project.dto.LoginRequest
import org.example.project.dto.LoginResponse
import org.example.project.dto.MeResponse
import org.example.project.dto.UserDto

class AuthApiClient(private val client: HttpClient) : AuthRepository {

    override suspend fun login(request: LoginRequest): ApiResult<LoginResponse> =
        post("/api/auth/login", request)

    override suspend fun logout(): ApiResult<Unit> =
        post("/api/auth/logout")

    override suspend fun me(): ApiResult<MeResponse> =
        get("/api/auth/me")

    override suspend fun bootstrap(request: BootstrapRequest): ApiResult<LoginResponse> =
        post("/api/admin/bootstrap", request)

    override suspend fun bootstrapStatus(): ApiResult<org.example.project.dto.BootstrapStatusDto> =
        get("/api/admin/bootstrap-status")

    override suspend fun changePassword(request: ChangePasswordRequest): ApiResult<UserDto> =
        post("/api/auth/password", request)

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        try {
            val response = client.get(path)
            parseResponse(response.status.value, response.body())
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Error(networkError(e.message ?: "Request timeout"))
        } catch (e: ClientRequestException) {
            ApiResult.Error(networkError(e.message))
        } catch (e: Exception) {
            ApiResult.Error(networkError(e.message ?: "Unexpected error"))
        }

    private suspend inline fun <reified T> post(path: String): ApiResult<T> =
        try {
            val response = client.post(path)
            parseResponse(response.status.value, response.body())
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Error(networkError(e.message ?: "Request timeout"))
        } catch (e: ClientRequestException) {
            ApiResult.Error(networkError(e.message))
        } catch (e: Exception) {
            ApiResult.Error(networkError(e.message ?: "Unexpected error"))
        }

    private suspend inline fun <reified T> post(path: String, body: Any): ApiResult<T> =
        try {
            val response = client.post(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            parseResponse(response.status.value, response.body())
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Error(networkError(e.message ?: "Request timeout"))
        } catch (e: ClientRequestException) {
            ApiResult.Error(networkError(e.message))
        } catch (e: Exception) {
            ApiResult.Error(networkError(e.message ?: "Unexpected error"))
        }

    private inline fun <reified T> parseResponse(statusCode: Int, body: ApiResponse<T>): ApiResult<T> {
        val data = body.data
        val error = body.error
        return when {
            body.success && data != null -> ApiResult.Success(data)
            error != null -> ApiResult.Error(error, statusCode)
            statusCode in 200..299 -> ApiResult.Error(
                ApiError("EMPTY_RESPONSE", "Empty successful response"),
                statusCode
            )
            else -> ApiResult.Error(
                ApiError("UNKNOWN_ERROR", "Unexpected server response"),
                statusCode
            )
        }
    }

    private fun networkError(message: String): ApiError =
        ApiError("NETWORK_ERROR", message)
}
