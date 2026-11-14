package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.CreateDepartmentRequest
import org.example.project.dto.CreateRoleRequest
import org.example.project.dto.CreateUserRequest
import org.example.project.dto.DepartmentDto
import org.example.project.dto.PermissionDto
import org.example.project.dto.RoleDto
import org.example.project.dto.UpdateDepartmentRequest
import org.example.project.dto.UpdateUserRequest
import org.example.project.dto.UserDto

class AdminApiClient(private val client: HttpClient) : AdminRepository {

    override suspend fun listUsers(): ApiResult<List<UserDto>> = get("/api/users")

    override suspend fun createUser(request: CreateUserRequest): ApiResult<UserDto> =
        post("/api/users", request)

    override suspend fun updateUser(id: Long, request: UpdateUserRequest): ApiResult<UserDto> =
        put("/api/users/$id", request)

    override suspend fun setUserActive(id: Long, active: Boolean, version: Long): ApiResult<UserDto> =
        put("/api/users/$id/active", SetActiveBody(active, version))

    override suspend fun listDepartments(): ApiResult<List<DepartmentDto>> = get("/api/departments")

    override suspend fun createDepartment(request: CreateDepartmentRequest): ApiResult<DepartmentDto> =
        post("/api/departments", request)

    override suspend fun updateDepartment(
        id: Long,
        request: UpdateDepartmentRequest
    ): ApiResult<DepartmentDto> =
        put("/api/departments/$id", request)

    override suspend fun listRoles(): ApiResult<List<RoleDto>> = get("/api/roles")

    override suspend fun createRole(request: CreateRoleRequest): ApiResult<RoleDto> =
        post("/api/roles", request)

    override suspend fun listPermissions(): ApiResult<List<PermissionDto>> = get("/api/permissions")

    override suspend fun createPermission(
        roleId: Long,
        resource: String,
        action: String,
        scope: String?
    ): ApiResult<PermissionDto> =
        post("/api/permissions", CreatePermissionWithRoleBody(roleId, resource, action, scope))

    override suspend fun deletePermission(id: Long): ApiResult<Unit> =
        try {
            val response = client.delete("/api/permissions/$id")
            if (response.status.value in 200..299) {
                ApiResult.Success(Unit)
            } else {
                val body = runCatching { response.body<ApiResponse<Unit>>() }.getOrNull()
                ApiResult.Error(
                    body?.error ?: ApiError("UNKNOWN_ERROR", "Unexpected server response"),
                    response.status.value
                )
            }
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Error(networkError(e.message ?: "Request timeout"))
        } catch (e: ClientRequestException) {
            ApiResult.Error(networkError(e.message))
        } catch (e: Exception) {
            ApiResult.Error(networkError(e.message ?: "Unexpected error"))
        }

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        execute { client.get(path) }

    private suspend inline fun <reified T> post(path: String, body: Any): ApiResult<T> =
        execute {
            client.post(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend inline fun <reified T> put(path: String, body: Any): ApiResult<T> =
        execute {
            client.put(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend inline fun <reified T> execute(
        block: () -> io.ktor.client.statement.HttpResponse
    ): ApiResult<T> =
        try {
            val response = block()
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

@Serializable
private data class SetActiveBody(val active: Boolean, val version: Long)

@Serializable
private data class CreatePermissionWithRoleBody(
    val roleId: Long,
    val resource: String,
    val action: String,
    val scope: String? = null
)
