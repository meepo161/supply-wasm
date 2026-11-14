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
import org.example.project.dto.CancelProductionOrderRequest
import org.example.project.dto.CompleteProductionOrderRequest
import org.example.project.dto.CreateProductionOrderRequest
import org.example.project.dto.ProductInstanceDto
import org.example.project.dto.ProductionOrderActionRequest
import org.example.project.dto.ProductionOrderDto
import org.example.project.dto.ProductionOrderStatus
import org.example.project.dto.ProductionStageActionRequest

class ProductionApiClient(private val client: HttpClient) : ProductionRepository {

    override suspend fun listOrders(
        projectId: Long?,
        productModelId: Long?,
        status: ProductionOrderStatus?
    ): ApiResult<List<ProductionOrderDto>> {
        val params = buildMap {
            projectId?.let { put("projectId", it.toString()) }
            productModelId?.let { put("productModelId", it.toString()) }
            status?.let { put("status", it.name) }
        }
        return getWithParams("/api/production/orders", params)
    }

    override suspend fun getOrder(id: Long): ApiResult<ProductionOrderDto> =
        get("/api/production/orders/$id")

    override suspend fun createOrder(request: CreateProductionOrderRequest): ApiResult<ProductionOrderDto> =
        post("/api/production/orders", request)

    override suspend fun planOrder(
        id: Long,
        request: ProductionOrderActionRequest
    ): ApiResult<ProductionOrderDto> = post("/api/production/orders/$id/plan", request)

    override suspend fun startOrder(
        id: Long,
        request: ProductionOrderActionRequest
    ): ApiResult<ProductionOrderDto> = post("/api/production/orders/$id/start", request)

    override suspend fun holdOrder(
        id: Long,
        request: ProductionOrderActionRequest
    ): ApiResult<ProductionOrderDto> = post("/api/production/orders/$id/hold", request)

    override suspend fun resumeOrder(
        id: Long,
        request: ProductionOrderActionRequest
    ): ApiResult<ProductionOrderDto> = post("/api/production/orders/$id/resume", request)

    override suspend fun completeOrder(
        id: Long,
        request: CompleteProductionOrderRequest
    ): ApiResult<ProductionOrderDto> = post("/api/production/orders/$id/complete", request)

    override suspend fun closeOrder(
        id: Long,
        request: ProductionOrderActionRequest
    ): ApiResult<ProductionOrderDto> = post("/api/production/orders/$id/close", request)

    override suspend fun cancelOrder(
        id: Long,
        request: CancelProductionOrderRequest
    ): ApiResult<ProductionOrderDto> = post("/api/production/orders/$id/cancel", request)

    override suspend fun startStage(
        orderId: Long,
        stageId: Long,
        request: ProductionStageActionRequest
    ): ApiResult<ProductionOrderDto> =
        post("/api/production/orders/$orderId/stages/$stageId/start", request)

    override suspend fun completeStage(
        orderId: Long,
        stageId: Long,
        request: ProductionStageActionRequest
    ): ApiResult<ProductionOrderDto> =
        post("/api/production/orders/$orderId/stages/$stageId/complete", request)

    override suspend fun skipStage(
        orderId: Long,
        stageId: Long,
        request: ProductionStageActionRequest
    ): ApiResult<ProductionOrderDto> =
        post("/api/production/orders/$orderId/stages/$stageId/skip", request)

    override suspend fun listInstances(orderId: Long): ApiResult<List<ProductInstanceDto>> =
        get("/api/production/orders/$orderId/instances")

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        execute { client.get(path) }

    private suspend inline fun <reified T> getWithParams(
        path: String,
        params: Map<String, String>
    ): ApiResult<T> = execute {
        client.get(path) {
            url {
                params.forEach { (key, value) -> parameters.append(key, value) }
            }
        }
    }

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
