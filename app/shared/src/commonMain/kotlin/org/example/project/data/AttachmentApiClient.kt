package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.AttachmentDto
import org.example.project.dto.AttachmentEntityType

class AttachmentApiClient(private val client: HttpClient) : AttachmentRepository {

    override suspend fun list(
        entityType: AttachmentEntityType,
        entityId: Long
    ): ApiResult<List<AttachmentDto>> =
        execute { client.get("${basePath(entityType)}/$entityId/attachments") }

    override suspend fun upload(
        entityType: AttachmentEntityType,
        entityId: Long,
        file: AttachmentFileData
    ): ApiResult<AttachmentDto> =
        execute {
            client.submitFormWithBinaryData(
                url = "${basePath(entityType)}/$entityId/attachments",
                formData = formData {
                    append(
                        "file",
                        file.bytes,
                        Headers.build {
                            append(
                                HttpHeaders.ContentType,
                                file.mimeType ?: "application/octet-stream"
                            )
                            append(
                                HttpHeaders.ContentDisposition,
                                "filename=\"${file.fileName.dispositionSafe()}\""
                            )
                        }
                    )
                }
            )
        }

    override suspend fun download(id: Long): ApiResult<DownloadedAttachment> =
        try {
            val response = client.get("/api/attachments/$id")
            if (response.status.value in 200..299) {
                ApiResult.Success(
                    DownloadedAttachment(
                        bytes = response.body(),
                        mimeType = response.contentType()?.toString()
                    )
                )
            } else {
                val body = runCatching { response.body<ApiResponse<AttachmentDto>>() }.getOrNull()
                val error = body?.error
                    ?: ApiError("UNKNOWN_ERROR", "Unexpected server response")
                ApiResult.Error(error, response.status.value)
            }
        } catch (e: HttpRequestTimeoutException) {
            ApiResult.Error(networkError(e.message ?: "Request timeout"))
        } catch (e: ClientRequestException) {
            ApiResult.Error(networkError(e.message))
        } catch (e: Exception) {
            ApiResult.Error(networkError(e.message ?: "Unexpected error"))
        }

    override suspend fun delete(id: Long): ApiResult<AttachmentDto> =
        execute { client.delete("/api/attachments/$id") }

    private fun basePath(entityType: AttachmentEntityType): String = when (entityType) {
        AttachmentEntityType.TASK -> "/api/tasks"
        AttachmentEntityType.PROJECT -> "/api/projects"
        AttachmentEntityType.NOMENCLATURE -> "/api/wms/nomenclature"
    }

    private fun String.dispositionSafe(): String =
        map { ch -> if (ch == '"' || ch == '\\' || ch == '\r' || ch == '\n') '_' else ch }
            .joinToString("")

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
