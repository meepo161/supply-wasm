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
import org.example.project.dto.CreateNomenclatureRequest
import org.example.project.dto.CreateReceiptRequest
import org.example.project.dto.CreateReservationRequest
import org.example.project.dto.CreateStorageLocationRequest
import org.example.project.dto.CreateWarehouseRequest
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.PostReceiptRequest
import org.example.project.dto.ReceiptDto
import org.example.project.dto.ReleaseReservationRequest
import org.example.project.dto.ReservationDto
import org.example.project.dto.ReservationStatus
import org.example.project.dto.StockBalanceDto
import org.example.project.dto.StorageLocationDto
import org.example.project.dto.WarehouseDto

class WmsApiClient(private val client: HttpClient) : WmsRepository {

    override suspend fun listWarehouses(): ApiResult<List<WarehouseDto>> = get("/api/wms/warehouses")

    override suspend fun createWarehouse(request: CreateWarehouseRequest): ApiResult<WarehouseDto> =
        post("/api/wms/warehouses", request)

    override suspend fun listLocations(warehouseId: Long): ApiResult<List<StorageLocationDto>> =
        get("/api/wms/warehouses/$warehouseId/locations")

    override suspend fun createLocation(request: CreateStorageLocationRequest): ApiResult<StorageLocationDto> =
        post("/api/wms/locations", request)

    override suspend fun listNomenclature(): ApiResult<List<NomenclatureDto>> = get("/api/wms/nomenclature")

    override suspend fun createNomenclature(request: CreateNomenclatureRequest): ApiResult<NomenclatureDto> =
        post("/api/wms/nomenclature", request)

    override suspend fun archiveNomenclature(id: Long): ApiResult<NomenclatureDto> =
        post("/api/wms/nomenclature/$id/archive")

    override suspend fun getStock(warehouseId: Long?, nomenclatureId: Long?): ApiResult<List<StockBalanceDto>> =
        getWithParams(
            path = "/api/wms/stock",
            params = buildMap {
                warehouseId?.let { put("warehouseId", it.toString()) }
                nomenclatureId?.let { put("nomenclatureId", it.toString()) }
            }
        )

    override suspend fun createReceipt(request: CreateReceiptRequest): ApiResult<ReceiptDto> =
        post("/api/wms/receipts", request)

    override suspend fun getReceipt(id: Long): ApiResult<ReceiptDto> = get("/api/wms/receipts/$id")

    override suspend fun postReceipt(id: Long, request: PostReceiptRequest): ApiResult<ReceiptDto> =
        post("/api/wms/receipts/$id/post", request)

    override suspend fun createReservation(request: CreateReservationRequest): ApiResult<ReservationDto> =
        post("/api/wms/reservations", request)

    override suspend fun listReservations(
        warehouseId: Long?,
        nomenclatureId: Long?,
        status: ReservationStatus?
    ): ApiResult<List<ReservationDto>> =
        getWithParams(
            path = "/api/wms/reservations",
            params = buildMap {
                warehouseId?.let { put("warehouseId", it.toString()) }
                nomenclatureId?.let { put("nomenclatureId", it.toString()) }
                status?.let { put("status", it.name) }
            }
        )

    override suspend fun getReservation(id: Long): ApiResult<ReservationDto> = get("/api/wms/reservations/$id")

    override suspend fun releaseReservation(
        id: Long,
        request: ReleaseReservationRequest
    ): ApiResult<ReservationDto> = post("/api/wms/reservations/$id/release", request)

    private suspend inline fun <reified T> get(path: String): ApiResult<T> =
        execute { client.get(path) }

    private suspend inline fun <reified T> getWithParams(path: String, params: Map<String, String>): ApiResult<T> =
        execute {
            client.get(path) {
                url {
                    params.forEach { (key, value) ->
                        parameters.append(key, value)
                    }
                }
            }
        }

    private suspend inline fun <reified T> post(path: String): ApiResult<T> =
        execute { client.post(path) }

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
