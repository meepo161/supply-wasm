package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import org.example.project.dto.AnalyticsSummaryDto
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.CalendarEventDto
import org.example.project.dto.DocumentTemplateDto

class AnalyticsApiClient(private val client: HttpClient) : AnalyticsRepository {

    override suspend fun getSummary(): ApiResult<AnalyticsSummaryDto> = get("/api/analytics/summary")

    override suspend fun getCalendar(): ApiResult<List<CalendarEventDto>> = get("/api/analytics/calendar")

    override suspend fun listDocumentTemplates(): ApiResult<List<DocumentTemplateDto>> = get("/api/document-templates")

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        try {
            val response = client.get(path)
            val body = response.body<ApiResponse<T>>()
            val data = body.data
            val error = body.error
            when {
                body.success && data != null -> ApiResult.Success(data)
                error != null -> ApiResult.Error(error, response.status.value)
                else -> ApiResult.Error(ApiError("UNKNOWN_ERROR", "Unexpected server response"), response.status.value)
            }
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Error(ApiError("NETWORK_ERROR", e.message ?: "Request timeout"))
        } catch (e: ClientRequestException) {
            ApiResult.Error(ApiError("NETWORK_ERROR", e.message))
        } catch (e: Exception) {
            ApiResult.Error(ApiError("NETWORK_ERROR", e.message ?: "Unexpected error"))
        }
}
