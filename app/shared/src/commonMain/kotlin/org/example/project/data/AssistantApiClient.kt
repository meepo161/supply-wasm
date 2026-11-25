package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.AssistantChatRequestDto
import org.example.project.dto.AssistantChatResponseDto
import org.example.project.dto.AssistantConfirmDraftRequestDto
import org.example.project.dto.AssistantConfirmDraftResponseDto
import org.example.project.dto.AssistantConversationDetailDto
import org.example.project.dto.AssistantConversationSummaryDto
import org.example.project.dto.AssistantStatusDto

class AssistantApiClient(private val client: HttpClient) : AssistantRepository {

    override suspend fun status(): ApiResult<AssistantStatusDto> =
        try {
            val response = client.get("/api/assistant/status")
            parseResponse(response.status.value, response.body())
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Error(networkError(e.message ?: "Request timeout"))
        } catch (e: ClientRequestException) {
            ApiResult.Error(networkError(e.message))
        } catch (e: Exception) {
            ApiResult.Error(networkError(e.message ?: "Unexpected error"))
        }

    override suspend fun listConversations(): ApiResult<List<AssistantConversationSummaryDto>> =
        try {
            val response = client.get("/api/assistant/conversations")
            parseResponse(response.status.value, response.body())
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Error(networkError(e.message ?: "Request timeout"))
        } catch (e: ClientRequestException) {
            ApiResult.Error(networkError(e.message))
        } catch (e: Exception) {
            ApiResult.Error(networkError(e.message ?: "Unexpected error"))
        }

    override suspend fun getConversation(id: Long): ApiResult<AssistantConversationDetailDto> =
        try {
            val response = client.get("/api/assistant/conversations/$id")
            parseResponse(response.status.value, response.body())
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Error(networkError(e.message ?: "Request timeout"))
        } catch (e: ClientRequestException) {
            ApiResult.Error(networkError(e.message))
        } catch (e: Exception) {
            ApiResult.Error(networkError(e.message ?: "Unexpected error"))
        }

    override suspend fun deleteConversation(id: Long): ApiResult<Unit> =
        try {
            val response = client.delete("/api/assistant/conversations/$id")
            if (response.status.value in 200..299) ApiResult.Success(Unit)
            else parseResponse(response.status.value, response.body())
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Error(networkError(e.message ?: "Request timeout"))
        } catch (e: ClientRequestException) {
            ApiResult.Error(networkError(e.message))
        } catch (e: Exception) {
            ApiResult.Error(networkError(e.message ?: "Unexpected error"))
        }

    override suspend fun chat(request: AssistantChatRequestDto): ApiResult<AssistantChatResponseDto> =
        try {
            val response = client.post("/api/assistant/chat") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
            parseResponse(response.status.value, response.body())
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Error(networkError(e.message ?: "Request timeout"))
        } catch (e: ClientRequestException) {
            ApiResult.Error(networkError(e.message))
        } catch (e: Exception) {
            ApiResult.Error(networkError(e.message ?: "Unexpected error"))
        }

    override suspend fun confirmDraft(request: AssistantConfirmDraftRequestDto): ApiResult<AssistantConfirmDraftResponseDto> =
        try {
            val response = client.post("/api/assistant/confirm-draft") {
                contentType(ContentType.Application.Json)
                setBody(request)
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

    private fun networkError(message: String): ApiError = ApiError("NETWORK_ERROR", message)
}
