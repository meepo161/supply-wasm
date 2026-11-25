package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import org.example.project.dto.CancelTransferRequest
import org.example.project.dto.CreateIssueOrderRequest
import org.example.project.dto.CreateReturnOrderRequest
import org.example.project.dto.CreateTransferRequest
import org.example.project.dto.InspectReturnLineRequest
import org.example.project.dto.IssueActionRequest
import org.example.project.dto.IssueOrderDto
import org.example.project.dto.IssueStatus
import org.example.project.dto.MarkTransferInTransitRequest
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.PickIssueRequest
import org.example.project.dto.ReceiveTransferRequest
import org.example.project.dto.RefuseIssueRequest
import org.example.project.dto.ReturnActionRequest
import org.example.project.dto.ReturnOrderDto
import org.example.project.dto.ReturnStatus
import org.example.project.dto.ShipTransferRequest
import org.example.project.dto.TransferDto
import org.example.project.dto.TransferStatus
import org.example.project.dto.CancelWriteOffRequest
import org.example.project.dto.CreateWriteOffRequest
import org.example.project.dto.SetWriteOffLimitRequest
import org.example.project.dto.WriteOffActionRequest
import org.example.project.dto.WriteOffDto
import org.example.project.dto.WriteOffLimitDto
import org.example.project.dto.WriteOffStatus
import org.example.project.dto.CancelInventoryRequest
import org.example.project.dto.CreateInventoryRequest
import org.example.project.dto.InventoryActionRequest
import org.example.project.dto.InventoryDto
import org.example.project.dto.InventoryStatus
import org.example.project.dto.SubmitInventoryCountsRequest
import org.example.project.dto.ExpectedReceiptLineDto
import org.example.project.dto.LinkReceiptRequest
import org.example.project.dto.PostReceiptRequest
import org.example.project.dto.QuickReceiveOrderRequest
import org.example.project.dto.ReceiveOrderItemsRequest
import org.example.project.dto.ReceiptStatus
import org.example.project.dto.ReceiptDto
import org.example.project.dto.ReleaseReservationRequest
import org.example.project.dto.ReservationDto
import org.example.project.dto.ReservationStatus
import org.example.project.dto.SerialItemDto
import org.example.project.dto.StockBalanceDto
import org.example.project.dto.StorageLocationDto
import org.example.project.dto.SuggestLocationDto
import org.example.project.dto.UpdateSerialCalibrationRequest
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

    override suspend fun getNomenclatureDetails(id: Long): ApiResult<org.example.project.dto.NomenclatureDetailsDto> =
        get("/api/wms/nomenclature/$id/details")

    override suspend fun createNomenclature(request: CreateNomenclatureRequest): ApiResult<NomenclatureDto> =
        post("/api/wms/nomenclature", request)

    override suspend fun archiveNomenclature(id: Long): ApiResult<NomenclatureDto> =
        post("/api/wms/nomenclature/$id/archive")

    override suspend fun updateNomenclatureThresholds(
        id: Long,
        request: org.example.project.dto.UpdateNomenclatureThresholdsRequest
    ): ApiResult<NomenclatureDto> =
        execute {
            client.put("/api/wms/nomenclature/$id/thresholds") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun findByBarcode(code: String): ApiResult<NomenclatureDto> =
        get("/api/wms/nomenclature/by-barcode/$code")

    override suspend fun addBarcode(
        id: Long,
        request: org.example.project.dto.CreateNomenclatureBarcodeRequest
    ): ApiResult<NomenclatureDto> =
        post("/api/wms/nomenclature/$id/barcodes", request)

    override suspend fun listAnalogs(id: Long): ApiResult<List<org.example.project.dto.NomenclatureAnalogDto>> =
        get("/api/wms/nomenclature/$id/analogs")

    override suspend fun createAnalog(
        id: Long,
        request: org.example.project.dto.CreateNomenclatureAnalogRequest
    ): ApiResult<List<org.example.project.dto.NomenclatureAnalogDto>> =
        post("/api/wms/nomenclature/$id/analogs", request)

    override suspend fun getStock(warehouseId: Long?, nomenclatureId: Long?): ApiResult<List<StockBalanceDto>> =
        getWithParams(
            path = "/api/wms/stock",
            params = buildMap {
                warehouseId?.let { put("warehouseId", it.toString()) }
                nomenclatureId?.let { put("nomenclatureId", it.toString()) }
            }
        )

    override suspend fun listReceipts(
        warehouseId: Long?,
        purchaseOrderId: Long?,
        status: ReceiptStatus?
    ): ApiResult<List<ReceiptDto>> = getWithParams(
        path = "/api/wms/receipts",
        params = buildMap {
            warehouseId?.let { put("warehouseId", it.toString()) }
            purchaseOrderId?.let { put("purchaseOrderId", it.toString()) }
            status?.let { put("status", it.name) }
        }
    )

    override suspend fun createReceipt(request: CreateReceiptRequest): ApiResult<ReceiptDto> =
        post("/api/wms/receipts", request)

    override suspend fun getReceipt(id: Long): ApiResult<ReceiptDto> = get("/api/wms/receipts/$id")

    override suspend fun acceptReceiptLines(
        id: Long,
        request: org.example.project.dto.AcceptReceiptLinesRequest
    ): ApiResult<ReceiptDto> = put("/api/wms/receipts/$id/acceptance", request)

    override suspend fun postReceipt(id: Long, request: PostReceiptRequest): ApiResult<ReceiptDto> =
        post("/api/wms/receipts/$id/post", request)

    override suspend fun getReceiptQc(receiptId: Long): ApiResult<org.example.project.dto.ReceiptQcDto> =
        get("/api/wms/receipts/$receiptId/qc")

    override suspend fun inspectReceiptQc(
        receiptId: Long,
        request: org.example.project.dto.InspectReceiptQcRequest
    ): ApiResult<org.example.project.dto.ReceiptQcDto> =
        post("/api/wms/receipts/$receiptId/qc", request)

    override suspend fun listMovements(
        warehouseId: Long?,
        nomenclatureId: Long?,
        type: String?,
        limit: Int
    ): ApiResult<List<org.example.project.dto.StockMovementDto>> {
        val params = buildMap {
            warehouseId?.let { put("warehouseId", it.toString()) }
            nomenclatureId?.let { put("nomenclatureId", it.toString()) }
            type?.takeIf { it.isNotBlank() }?.let { put("type", it) }
            put("limit", limit.toString())
        }
        return getWithParams("/api/wms/movements", params)
    }

    override suspend fun listLowStock(): ApiResult<List<org.example.project.dto.LowStockItemDto>> =
        get("/api/wms/reports/low-stock")

    override suspend fun listLots(nomenclatureId: Long?): ApiResult<List<org.example.project.dto.LotDto>> =
        if (nomenclatureId != null) getWithParams("/api/wms/lots", mapOf("nomenclatureId" to nomenclatureId.toString()))
        else get("/api/wms/lots")

    override suspend fun listSerials(nomenclatureId: Long?): ApiResult<List<org.example.project.dto.SerialItemDto>> =
        if (nomenclatureId != null) getWithParams("/api/wms/serials", mapOf("nomenclatureId" to nomenclatureId.toString()))
        else get("/api/wms/serials")

    override suspend fun linkReceipt(id: Long, request: LinkReceiptRequest): ApiResult<ReceiptDto> =
        post("/api/wms/receipts/$id/links", request)

    override suspend fun listExpectedReceipts(
        purchaseOrderId: Long?
    ): ApiResult<List<ExpectedReceiptLineDto>> {
        val params = buildMap { purchaseOrderId?.let { put("purchaseOrderId", it.toString()) } }
        return getWithParams("/api/wms/expected-receipts", params)
    }

    override suspend fun quickReceiveOrder(request: QuickReceiveOrderRequest): ApiResult<ReceiptDto> =
        post("/api/wms/receipts/quick-receive-order", request)

    override suspend fun receiveOrderItems(request: ReceiveOrderItemsRequest): ApiResult<ReceiptDto> =
        post("/api/wms/receipts/receive-order", request)

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

    // ---- Перемещения ----

    override suspend fun createTransfer(request: CreateTransferRequest): ApiResult<TransferDto> =
        post("/api/wms/transfers", request)

    override suspend fun listTransfers(
        warehouseId: Long?,
        nomenclatureId: Long?,
        status: TransferStatus?
    ): ApiResult<List<TransferDto>> =
        getWithParams(
            path = "/api/wms/transfers",
            params = buildMap {
                warehouseId?.let { put("warehouseId", it.toString()) }
                nomenclatureId?.let { put("nomenclatureId", it.toString()) }
                status?.let { put("status", it.name) }
            }
        )

    override suspend fun getTransfer(id: Long): ApiResult<TransferDto> = get("/api/wms/transfers/$id")

    override suspend fun shipTransfer(id: Long, request: ShipTransferRequest): ApiResult<TransferDto> =
        post("/api/wms/transfers/$id/ship", request)

    override suspend fun markTransferInTransit(
        id: Long,
        request: MarkTransferInTransitRequest
    ): ApiResult<TransferDto> = post("/api/wms/transfers/$id/in-transit", request)

    override suspend fun receiveTransfer(
        id: Long,
        request: ReceiveTransferRequest
    ): ApiResult<TransferDto> = post("/api/wms/transfers/$id/receive", request)

    override suspend fun cancelTransfer(
        id: Long,
        request: CancelTransferRequest
    ): ApiResult<TransferDto> = post("/api/wms/transfers/$id/cancel", request)

    // ---- Выдача ----

    override suspend fun createIssue(request: CreateIssueOrderRequest): ApiResult<IssueOrderDto> =
        post("/api/wms/issues", request)

    override suspend fun listIssues(
        warehouseId: Long?,
        status: IssueStatus?
    ): ApiResult<List<IssueOrderDto>> =
        getWithParams(
            path = "/api/wms/issues",
            params = buildMap {
                warehouseId?.let { put("warehouseId", it.toString()) }
                status?.let { put("status", it.name) }
            }
        )

    override suspend fun getIssue(id: Long): ApiResult<IssueOrderDto> = get("/api/wms/issues/$id")

    override suspend fun pickIssue(id: Long, request: PickIssueRequest): ApiResult<IssueOrderDto> =
        post("/api/wms/issues/$id/picks", request)

    override suspend fun issueOrder(id: Long, request: IssueActionRequest): ApiResult<IssueOrderDto> =
        post("/api/wms/issues/$id/issue", request)

    override suspend fun confirmIssue(id: Long, request: IssueActionRequest): ApiResult<IssueOrderDto> =
        post("/api/wms/issues/$id/confirm", request)

    override suspend fun refuseIssue(id: Long, request: RefuseIssueRequest): ApiResult<IssueOrderDto> =
        post("/api/wms/issues/$id/refuse", request)

    override suspend fun cancelIssue(id: Long, request: IssueActionRequest): ApiResult<IssueOrderDto> =
        post("/api/wms/issues/$id/cancel", request)

    // ---- Возвраты ----

    override suspend fun createReturn(request: CreateReturnOrderRequest): ApiResult<ReturnOrderDto> =
        post("/api/wms/returns", request)

    override suspend fun listReturns(
        warehouseId: Long?,
        status: ReturnStatus?
    ): ApiResult<List<ReturnOrderDto>> =
        getWithParams(
            path = "/api/wms/returns",
            params = buildMap {
                warehouseId?.let { put("warehouseId", it.toString()) }
                status?.let { put("status", it.name) }
            }
        )

    override suspend fun getReturn(id: Long): ApiResult<ReturnOrderDto> = get("/api/wms/returns/$id")

    override suspend fun receiveReturn(id: Long, request: ReturnActionRequest): ApiResult<ReturnOrderDto> =
        post("/api/wms/returns/$id/receive", request)

    override suspend fun inspectReturnLine(
        id: Long,
        lineId: Long,
        request: InspectReturnLineRequest
    ): ApiResult<ReturnOrderDto> = post("/api/wms/returns/$id/lines/$lineId/inspect", request)

    // ---- Списание ----

    override suspend fun createWriteOff(request: CreateWriteOffRequest): ApiResult<WriteOffDto> =
        post("/api/wms/write-offs", request)

    override suspend fun listWriteOffs(
        warehouseId: Long?,
        status: WriteOffStatus?
    ): ApiResult<List<WriteOffDto>> =
        getWithParams(
            path = "/api/wms/write-offs",
            params = buildMap {
                warehouseId?.let { put("warehouseId", it.toString()) }
                status?.let { put("status", it.name) }
            }
        )

    override suspend fun getWriteOff(id: Long): ApiResult<WriteOffDto> = get("/api/wms/write-offs/$id")

    override suspend fun postWriteOff(id: Long, request: WriteOffActionRequest): ApiResult<WriteOffDto> =
        post("/api/wms/write-offs/$id/post", request)

    override suspend fun cancelWriteOff(id: Long, request: CancelWriteOffRequest): ApiResult<WriteOffDto> =
        post("/api/wms/write-offs/$id/cancel", request)

    override suspend fun getWriteOffLimit(warehouseId: Long): ApiResult<WriteOffLimitDto> =
        get("/api/wms/write-off-limit/$warehouseId")

    override suspend fun setWriteOffLimit(
        warehouseId: Long,
        request: SetWriteOffLimitRequest
    ): ApiResult<WriteOffLimitDto> = put("/api/wms/write-off-limit/$warehouseId", request)

    // ---- Инвентаризация ----

    override suspend fun createInventory(request: CreateInventoryRequest): ApiResult<InventoryDto> =
        post("/api/wms/inventories", request)

    override suspend fun listInventories(
        warehouseId: Long?,
        status: InventoryStatus?
    ): ApiResult<List<InventoryDto>> =
        getWithParams(
            path = "/api/wms/inventories",
            params = buildMap {
                warehouseId?.let { put("warehouseId", it.toString()) }
                status?.let { put("status", it.name) }
            }
        )

    override suspend fun getInventory(id: Long): ApiResult<InventoryDto> = get("/api/wms/inventories/$id")

    override suspend fun bookInventory(id: Long, request: InventoryActionRequest): ApiResult<InventoryDto> =
        post("/api/wms/inventories/$id/book", request)

    override suspend fun submitInventoryCounts(
        id: Long,
        request: SubmitInventoryCountsRequest
    ): ApiResult<InventoryDto> = post("/api/wms/inventories/$id/counts", request)

    override suspend fun approveInventory(id: Long, request: InventoryActionRequest): ApiResult<InventoryDto> =
        post("/api/wms/inventories/$id/approve", request)

    override suspend fun postInventory(id: Long, request: InventoryActionRequest): ApiResult<InventoryDto> =
        post("/api/wms/inventories/$id/post", request)

    override suspend fun pauseInventory(id: Long, request: InventoryActionRequest): ApiResult<InventoryDto> =
        post("/api/wms/inventories/$id/pause", request)

    override suspend fun resumeInventory(id: Long, request: InventoryActionRequest): ApiResult<InventoryDto> =
        post("/api/wms/inventories/$id/resume", request)

    override suspend fun cancelInventory(id: Long, request: CancelInventoryRequest): ApiResult<InventoryDto> =
        post("/api/wms/inventories/$id/cancel", request)

    override suspend fun suggestLocation(warehouseId: Long, nomenclatureId: Long): ApiResult<SuggestLocationDto> =
        getWithParams(
            "/api/wms/suggest-location",
            mapOf("warehouseId" to warehouseId.toString(), "nomenclatureId" to nomenclatureId.toString())
        )

    override suspend fun updateSerialCalibration(
        serialId: Long,
        request: UpdateSerialCalibrationRequest
    ): ApiResult<SerialItemDto> =
        execute {
            client.patch("/api/wms/serials/$serialId/calibration") {
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    private suspend inline fun <reified T> put(path: String, body: Any): ApiResult<T> =
        execute {
            client.put(path) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

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
