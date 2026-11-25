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
import org.example.project.dto.ApproveDemandRequest
import org.example.project.dto.CancelDemandRequest
import org.example.project.dto.CreateDemandFromBomRequest
import org.example.project.dto.CreateDemandRequest
import org.example.project.dto.DemandCoverageDto
import org.example.project.dto.DemandDetailDto
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.MarkDemandPaidRequest
import org.example.project.dto.PlaceDemandOrderRequest
import org.example.project.dto.ReserveDemandStockRequest
import org.example.project.dto.SubmitDemandRequest

class DemandApiClient(private val client: HttpClient) : DemandRepository {

    override suspend fun listDemands(): ApiResult<List<DemandHeaderDto>> = get("/api/demands")

    override suspend fun createDemand(request: CreateDemandRequest): ApiResult<DemandDetailDto> =
        post("/api/demands", request)

    override suspend fun createDemandFromBom(request: CreateDemandFromBomRequest): ApiResult<DemandDetailDto> =
        post("/api/demands/from-bom", request)

    override suspend fun getDemand(id: Long): ApiResult<DemandDetailDto> = get("/api/demands/$id")

    override suspend fun submitDemand(id: Long, request: SubmitDemandRequest): ApiResult<DemandDetailDto> =
        post("/api/demands/$id/submit", request)

    override suspend fun approveDemand(id: Long, request: ApproveDemandRequest): ApiResult<DemandDetailDto> =
        post("/api/demands/$id/approve", request)

    override suspend fun cancelDemand(id: Long, request: CancelDemandRequest): ApiResult<DemandDetailDto> =
        post("/api/demands/$id/cancel", request)

    override suspend fun coveragePreview(id: Long): ApiResult<DemandCoverageDto> =
        get("/api/demands/$id/coverage")

    override suspend fun markDemandPaid(
        id: Long,
        request: MarkDemandPaidRequest
    ): ApiResult<DemandDetailDto> = post("/api/demands/$id/mark-paid", request)

    override suspend fun reserveDemandLineStock(
        demandId: Long,
        lineId: Long,
        request: ReserveDemandStockRequest
    ): ApiResult<DemandDetailDto> = post("/api/demands/$demandId/lines/$lineId/reserve-stock", request)

    override suspend fun placeDemandOrder(
        demandId: Long,
        request: PlaceDemandOrderRequest
    ): ApiResult<DemandDetailDto> = post("/api/demands/$demandId/place-order", request)

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
