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
import io.ktor.http.contentType
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.dto.CancelPurchaseDeliveryRequest
import org.example.project.dto.CancelPurchaseOrderRequest
import org.example.project.dto.CreatePurchaseDeliveryRequest
import org.example.project.dto.CreatePurchaseOrderRequest
import org.example.project.dto.PurchaseDeliveryDto
import org.example.project.dto.PurchaseOrderActionRequest
import org.example.project.dto.PurchaseOrderDto
import org.example.project.dto.PurchaseOrderHistoryDto
import org.example.project.dto.PurchaseOrderStatus
import org.example.project.dto.UpdatePurchaseOrderRequest

class PurchaseOrderApiClient(private val client: HttpClient) : PurchaseOrderRepository {

    override suspend fun listOrders(
        supplierId: Long?,
        status: PurchaseOrderStatus?,
        responsibleId: Long?
    ): ApiResult<List<PurchaseOrderDto>> {
        val params = buildMap {
            supplierId?.let { put("supplierId", it.toString()) }
            status?.let { put("status", it.name) }
            responsibleId?.let { put("responsibleId", it.toString()) }
        }
        return getWithParams("/api/purchase-orders", params)
    }

    override suspend fun getOrder(id: Long): ApiResult<PurchaseOrderDto> =
        get("/api/purchase-orders/$id")

    override suspend fun createOrder(request: CreatePurchaseOrderRequest): ApiResult<PurchaseOrderDto> =
        post("/api/purchase-orders", request)

    override suspend fun updateOrder(
        id: Long,
        request: UpdatePurchaseOrderRequest
    ): ApiResult<PurchaseOrderDto> = put("/api/purchase-orders/$id", request)

    override suspend fun submitOrder(
        id: Long,
        request: PurchaseOrderActionRequest
    ): ApiResult<PurchaseOrderDto> = post("/api/purchase-orders/$id/submit", request)

    override suspend fun approveOrder(
        id: Long,
        request: PurchaseOrderActionRequest
    ): ApiResult<PurchaseOrderDto> = post("/api/purchase-orders/$id/approve", request)

    override suspend fun sendOrder(
        id: Long,
        request: PurchaseOrderActionRequest
    ): ApiResult<PurchaseOrderDto> = post("/api/purchase-orders/$id/send", request)

    override suspend fun closeOrder(
        id: Long,
        request: PurchaseOrderActionRequest
    ): ApiResult<PurchaseOrderDto> = post("/api/purchase-orders/$id/close", request)

    override suspend fun cancelOrder(
        id: Long,
        request: CancelPurchaseOrderRequest
    ): ApiResult<PurchaseOrderDto> = post("/api/purchase-orders/$id/cancel", request)

    override suspend fun orderHistory(id: Long): ApiResult<List<PurchaseOrderHistoryDto>> =
        get("/api/purchase-orders/$id/history")

    override suspend fun listDeliveries(orderId: Long?): ApiResult<List<PurchaseDeliveryDto>> =
        if (orderId == null) {
            get("/api/purchase-deliveries")
        } else {
            get("/api/purchase-orders/$orderId/deliveries")
        }

    override suspend fun registerDelivery(
        orderId: Long,
        request: CreatePurchaseDeliveryRequest
    ): ApiResult<PurchaseDeliveryDto> = post("/api/purchase-orders/$orderId/deliveries", request)

    override suspend fun cancelDelivery(
        id: Long,
        request: CancelPurchaseDeliveryRequest
    ): ApiResult<PurchaseDeliveryDto> = post("/api/purchase-deliveries/$id/cancel", request)

    // --- служебное ---

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        execute { client.get(path) }

    private suspend inline fun <reified T> getWithParams(
        path: String,
        params: Map<String, String>
    ): ApiResult<T> = execute {
        client.get(path) {
            url { params.forEach { (key, value) -> parameters.append(key, value) } }
        }
    }

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

    private fun networkError(message: String): ApiError = ApiError("NETWORK_ERROR", message)
}
