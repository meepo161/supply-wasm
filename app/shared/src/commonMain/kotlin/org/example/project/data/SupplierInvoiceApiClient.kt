package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.CancelSupplierInvoiceRequest
import org.example.project.dto.ConfirmSupplierInvoiceCheckRequest
import org.example.project.dto.CreateReceiptFromInvoiceRequest
import org.example.project.dto.ReceiptDto
import org.example.project.dto.SupplierInvoiceActionRequest
import org.example.project.dto.SupplierInvoiceAutofillDto
import org.example.project.dto.SupplierInvoiceCheckDto
import org.example.project.dto.SupplierInvoiceDto
import org.example.project.dto.SupplierInvoiceHistoryDto
import org.example.project.dto.SupplierInvoiceInput
import org.example.project.dto.SupplierInvoicePreviewDto
import org.example.project.dto.SupplierInvoiceStatus
import org.example.project.dto.UpdateSupplierInvoiceRequest

class SupplierInvoiceApiClient(private val client: HttpClient) : SupplierInvoiceRepository {

    override suspend fun list(
        status: SupplierInvoiceStatus?,
        supplierId: Long?,
        purchaseOrderId: Long?
    ): ApiResult<List<SupplierInvoiceDto>> {
        val params = buildMap {
            status?.let { put("status", it.name) }
            supplierId?.let { put("supplierId", it.toString()) }
            purchaseOrderId?.let { put("purchaseOrderId", it.toString()) }
        }
        return execute {
            client.get(BASE) { url { params.forEach { (k, v) -> parameters.append(k, v) } } }
        }
    }

    override suspend fun get(id: Long): ApiResult<SupplierInvoiceDto> = execute { client.get("$BASE/$id") }

    override suspend fun create(input: SupplierInvoiceInput): ApiResult<SupplierInvoiceDto> = post(BASE, input)

    override suspend fun update(id: Long, request: UpdateSupplierInvoiceRequest): ApiResult<SupplierInvoiceDto> =
        execute {
            client.put("$BASE/$id") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun check(id: Long): ApiResult<SupplierInvoiceCheckDto> = execute { client.get("$BASE/$id/check") }

    override suspend fun confirmCheck(id: Long, request: ConfirmSupplierInvoiceCheckRequest): ApiResult<SupplierInvoiceDto> =
        post("$BASE/$id/confirm-check", request)

    override suspend fun reopen(id: Long, request: SupplierInvoiceActionRequest): ApiResult<SupplierInvoiceDto> =
        post("$BASE/$id/reopen", request)

    override suspend fun cancel(id: Long, request: CancelSupplierInvoiceRequest): ApiResult<SupplierInvoiceDto> =
        post("$BASE/$id/cancel", request)

    override suspend fun createReceipt(id: Long, request: CreateReceiptFromInvoiceRequest): ApiResult<ReceiptDto> =
        post("$BASE/$id/receipt", request)

    override suspend fun history(id: Long): ApiResult<List<SupplierInvoiceHistoryDto>> =
        execute { client.get("$BASE/$id/history") }

    override suspend fun parseDocumentPdf(documentId: Long): ApiResult<SupplierInvoiceAutofillDto> =
        execute { client.post("$BASE/parse-pdf?documentId=$documentId") }

    override suspend fun parsePdfBytes(fileName: String, bytes: ByteArray): ApiResult<SupplierInvoiceAutofillDto> =
        execute {
            client.post("$BASE/parse-pdf") {
                setBody(
                    io.ktor.client.request.forms.MultiPartFormDataContent(
                        io.ktor.client.request.forms.formData {
                            append(
                                key = "file",
                                value = bytes,
                                headers = Headers.build {
                                    append(HttpHeaders.ContentType, "application/pdf")
                                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                                }
                            )
                        }
                    )
                )
            }
        }

    override suspend fun previewHtml(id: Long): ApiResult<SupplierInvoicePreviewDto> =
        execute { client.get("$BASE/$id/preview") }

    override suspend fun downloadFile(id: Long): ApiResult<DownloadedAttachment> =
        try {
            val response = client.get("$BASE/$id/file")
            if (response.status.value in 200..299) {
                ApiResult.Success(DownloadedAttachment(bytes = response.body(), mimeType = response.contentType()?.toString()))
            } else {
                val error = runCatching { response.body<ApiResponse<SupplierInvoiceDto>>() }.getOrNull()?.error
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

    private suspend inline fun <reified T> post(path: String, body: Any): ApiResult<T> =
        execute {
            client.post(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private suspend inline fun <reified T> execute(block: () -> io.ktor.client.statement.HttpResponse): ApiResult<T> =
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
            statusCode in 200..299 -> ApiResult.Error(ApiError("EMPTY_RESPONSE", "Empty successful response"), statusCode)
            else -> ApiResult.Error(ApiError("UNKNOWN_ERROR", "Unexpected server response"), statusCode)
        }
    }

    private fun networkError(message: String): ApiError = ApiError("NETWORK_ERROR", message)

    private companion object {
        const val BASE = "/api/supplier-invoices"
    }
}
