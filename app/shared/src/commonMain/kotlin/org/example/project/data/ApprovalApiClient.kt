package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.ApprovalRouteDto
import org.example.project.dto.ApprovalTemplateDto
import org.example.project.dto.CancelApprovalRouteRequest
import org.example.project.dto.StartApprovalRouteRequest
import org.example.project.dto.SubmitApprovalDecisionRequest

class ApprovalApiClient(private val client: HttpClient) : ApprovalRepository {

    override suspend fun listTemplates(targetType: String): ApiResult<List<ApprovalTemplateDto>> =
        execute {
            client.get("/api/approvals/templates") {
                parameter("targetType", targetType)
            }
        }

    override suspend fun listRoutes(targetType: String, targetId: Long): ApiResult<List<ApprovalRouteDto>> =
        execute {
            client.get("/api/approvals/routes") {
                parameter("targetType", targetType)
                parameter("targetId", targetId)
            }
        }

    override suspend fun getRoute(id: Long): ApiResult<ApprovalRouteDto> =
        execute { client.get("/api/approvals/routes/$id") }

    override suspend fun startRoute(request: StartApprovalRouteRequest): ApiResult<ApprovalRouteDto> =
        post("/api/approvals/routes", request)

    override suspend fun submitDecision(
        routeId: Long,
        request: SubmitApprovalDecisionRequest
    ): ApiResult<ApprovalRouteDto> =
        post("/api/approvals/routes/$routeId/decisions", request)

    override suspend fun cancelRoute(
        routeId: Long,
        request: CancelApprovalRouteRequest
    ): ApiResult<ApprovalRouteDto> =
        post("/api/approvals/routes/$routeId/cancel", request)

    private suspend inline fun <reified T> post(path: String, body: Any): ApiResult<T> =
        execute {
            client.post(path) {
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
