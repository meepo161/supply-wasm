package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.CreateDocumentLinkRequest
import org.example.project.dto.CreateDocumentRequest
import org.example.project.dto.DocumentDto
import org.example.project.dto.DocumentLinkDto
import org.example.project.dto.DocumentVersionDto
import org.example.project.dto.UpdateDocumentRequest
import org.example.project.dto.VersionTransitionRequest

class DocumentApiClient(private val client: HttpClient) : DocumentRepository {

    override suspend fun list(): ApiResult<List<DocumentDto>> = get("/api/documents")

    override suspend fun get(id: Long): ApiResult<DocumentDto> = get("/api/documents/$id")

    override suspend fun create(request: CreateDocumentRequest): ApiResult<DocumentDto> =
        post("/api/documents", request)

    override suspend fun update(id: Long, request: UpdateDocumentRequest): ApiResult<DocumentDto> =
        post("/api/documents/$id/update", request)

    override suspend fun uploadVersion(
        documentId: Long,
        file: AttachmentFileData,
        changeReason: String?
    ): ApiResult<DocumentVersionDto> =
        execute {
            client.submitFormWithBinaryData(
                url = "/api/documents/$documentId/versions",
                formData = formData {
                    changeReason?.takeIf { it.isNotBlank() }?.let {
                        append("changeReason", it)
                    }
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

    override suspend fun approveVersion(
        documentId: Long,
        versionId: Long,
        request: VersionTransitionRequest
    ): ApiResult<DocumentVersionDto> =
        post("/api/documents/$documentId/versions/$versionId/approve", request)

    override suspend fun activateVersion(
        documentId: Long,
        versionId: Long,
        request: VersionTransitionRequest
    ): ApiResult<DocumentVersionDto> =
        post("/api/documents/$documentId/versions/$versionId/activate", request)

    override suspend fun cancelVersion(
        documentId: Long,
        versionId: Long,
        request: VersionTransitionRequest
    ): ApiResult<DocumentVersionDto> =
        post("/api/documents/$documentId/versions/$versionId/cancel", request)

    override suspend fun addLink(
        documentId: Long,
        request: CreateDocumentLinkRequest
    ): ApiResult<DocumentLinkDto> =
        post("/api/documents/$documentId/links", request)

    override suspend fun removeLink(documentId: Long, linkId: Long): ApiResult<DocumentLinkDto> =
        execute { client.delete("/api/documents/$documentId/links/$linkId") }

    override suspend fun downloadVersion(versionId: Long): ApiResult<DownloadedAttachment> =
        try {
            val response = client.get("/api/document-versions/$versionId/download")
            if (response.status.value in 200..299) {
                ApiResult.Success(
                    DownloadedAttachment(
                        bytes = response.body(),
                        mimeType = response.contentType()?.toString()
                    )
                )
            } else {
                val body = runCatching { response.body<ApiResponse<DocumentVersionDto>>() }.getOrNull()
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

    private fun String.dispositionSafe(): String =
        map { ch -> if (ch == '"' || ch == '\\' || ch == '\r' || ch == '\n') '_' else ch }
            .joinToString("")

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        execute { client.get(path) }

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
