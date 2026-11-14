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
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.ApproveBomRevisionRequest
import org.example.project.dto.BomExplodeReportDto
import org.example.project.dto.BomExplodeRequest
import org.example.project.dto.BomLineDto
import org.example.project.dto.BomRevisionDto
import org.example.project.dto.BomRevisionSummaryDto
import org.example.project.dto.CopyBomRevisionRequest
import org.example.project.dto.CreateBomLineRequest
import org.example.project.dto.CreateBomRevisionRequest
import org.example.project.dto.CreateProductModelRequest
import org.example.project.dto.ProductModelDto
import org.example.project.dto.TransitionBomRevisionRequest
import org.example.project.dto.UpdateBomLineRequest
import org.example.project.dto.UpdateProductModelRequest

class BomApiClient(private val client: HttpClient) : BomRepository {

    override suspend fun listModels(): ApiResult<List<ProductModelDto>> = get("/api/bom/models")

    override suspend fun createModel(request: CreateProductModelRequest): ApiResult<ProductModelDto> =
        post("/api/bom/models", request)

    override suspend fun getModel(id: Long): ApiResult<ProductModelDto> = get("/api/bom/models/$id")

    override suspend fun updateModel(id: Long, request: UpdateProductModelRequest): ApiResult<ProductModelDto> =
        put("/api/bom/models/$id", request)

    override suspend fun listRevisions(modelId: Long): ApiResult<List<BomRevisionSummaryDto>> =
        get("/api/bom/models/$modelId/revisions")

    override suspend fun createRevision(modelId: Long, request: CreateBomRevisionRequest): ApiResult<BomRevisionDto> =
        post("/api/bom/models/$modelId/revisions", request)

    override suspend fun getRevision(id: Long): ApiResult<BomRevisionDto> = get("/api/bom/revisions/$id")

    override suspend fun copyRevision(id: Long, request: CopyBomRevisionRequest): ApiResult<BomRevisionDto> =
        post("/api/bom/revisions/$id/copy", request)

    override suspend fun approveRevision(id: Long, request: ApproveBomRevisionRequest): ApiResult<BomRevisionDto> =
        post("/api/bom/revisions/$id/approve", request)

    override suspend fun activateRevision(id: Long, request: TransitionBomRevisionRequest): ApiResult<BomRevisionDto> =
        post("/api/bom/revisions/$id/activate", request)

    override suspend fun replaceRevision(id: Long, request: TransitionBomRevisionRequest): ApiResult<BomRevisionDto> =
        post("/api/bom/revisions/$id/replace", request)

    override suspend fun cancelRevision(id: Long, request: TransitionBomRevisionRequest): ApiResult<BomRevisionDto> =
        post("/api/bom/revisions/$id/cancel", request)

    override suspend fun listLines(revisionId: Long): ApiResult<List<BomLineDto>> =
        get("/api/bom/revisions/$revisionId/lines")

    override suspend fun createLine(revisionId: Long, request: CreateBomLineRequest): ApiResult<BomLineDto> =
        post("/api/bom/revisions/$revisionId/lines", request)

    override suspend fun updateLine(
        revisionId: Long,
        lineId: Long,
        request: UpdateBomLineRequest
    ): ApiResult<BomLineDto> = put("/api/bom/revisions/$revisionId/lines/$lineId", request)

    override suspend fun deleteLine(revisionId: Long, lineId: Long): ApiResult<Unit> =
        deleteNoContent("/api/bom/revisions/$revisionId/lines/$lineId")

    override suspend fun explodeRevision(id: Long, request: BomExplodeRequest): ApiResult<BomExplodeReportDto> =
        post("/api/bom/revisions/$id/explode", request)

    private suspend fun deleteNoContent(path: String): ApiResult<Unit> =
        executeUnit { client.delete(path) }

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

    private suspend fun executeUnit(
        block: suspend () -> io.ktor.client.statement.HttpResponse
    ): ApiResult<Unit> =
        try {
            val response = block()
            parseUnitResponse(response.status.value, response.body())
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

    private fun parseUnitResponse(statusCode: Int, body: ApiResponse<Unit>): ApiResult<Unit> {
        val error = body.error
        return when {
            body.success -> ApiResult.Success(Unit)
            error != null -> ApiResult.Error(error, statusCode)
            statusCode in 200..299 -> ApiResult.Success(Unit)
            else -> ApiResult.Error(
                ApiError("UNKNOWN_ERROR", "Unexpected server response"),
                statusCode
            )
        }
    }

    private fun networkError(message: String): ApiError =
        ApiError("NETWORK_ERROR", message)
}
