package org.example.project.data

import org.example.project.dto.CreateNomenclatureRequest
import org.example.project.dto.CreateReceiptRequest
import org.example.project.dto.CreateReservationRequest
import org.example.project.dto.CreateStorageLocationRequest
import org.example.project.dto.CreateWarehouseRequest
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.PostReceiptRequest
import org.example.project.dto.ExpectedReceiptLineDto
import org.example.project.dto.LinkReceiptRequest
import org.example.project.dto.ReceiptDto
import org.example.project.dto.ReceiptStatus
import org.example.project.dto.ReleaseReservationRequest
import org.example.project.dto.ReservationDto
import org.example.project.dto.ReservationStatus
import org.example.project.dto.SerialItemDto
import org.example.project.dto.StockBalanceDto
import org.example.project.dto.StorageLocationDto
import org.example.project.dto.WarehouseDto
import org.example.project.dto.CancelTransferRequest
import org.example.project.dto.CreateIssueOrderRequest
import org.example.project.dto.CreateReturnOrderRequest
import org.example.project.dto.CreateTransferRequest
import org.example.project.dto.InspectReturnLineRequest
import org.example.project.dto.IssueActionRequest
import org.example.project.dto.IssueOrderDto
import org.example.project.dto.IssueStatus
import org.example.project.dto.MarkTransferInTransitRequest
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
import org.example.project.dto.SuggestLocationDto
import org.example.project.dto.UpdateSerialCalibrationRequest

interface WmsRepository {
    suspend fun listWarehouses(): ApiResult<List<WarehouseDto>>
    suspend fun createWarehouse(request: CreateWarehouseRequest): ApiResult<WarehouseDto>

    suspend fun listLocations(warehouseId: Long): ApiResult<List<StorageLocationDto>>
    suspend fun createLocation(request: CreateStorageLocationRequest): ApiResult<StorageLocationDto>

    suspend fun listNomenclature(): ApiResult<List<NomenclatureDto>>
    suspend fun getNomenclatureDetails(id: Long): ApiResult<org.example.project.dto.NomenclatureDetailsDto>
    suspend fun createNomenclature(request: CreateNomenclatureRequest): ApiResult<NomenclatureDto>
    suspend fun archiveNomenclature(id: Long): ApiResult<NomenclatureDto>
    suspend fun updateNomenclatureThresholds(
        id: Long,
        request: org.example.project.dto.UpdateNomenclatureThresholdsRequest
    ): ApiResult<NomenclatureDto> = ApiResult.Error(org.example.project.dto.ApiError("NOT_IMPLEMENTED", "Not implemented"))
    suspend fun findByBarcode(code: String): ApiResult<NomenclatureDto> =
        ApiResult.Error(org.example.project.dto.ApiError("NOT_IMPLEMENTED", "Not implemented"))
    suspend fun addBarcode(
        id: Long,
        request: org.example.project.dto.CreateNomenclatureBarcodeRequest
    ): ApiResult<NomenclatureDto> = ApiResult.Error(org.example.project.dto.ApiError("NOT_IMPLEMENTED", "Not implemented"))
    suspend fun listAnalogs(id: Long): ApiResult<List<org.example.project.dto.NomenclatureAnalogDto>> =
        ApiResult.Success(emptyList())
    suspend fun createAnalog(
        id: Long,
        request: org.example.project.dto.CreateNomenclatureAnalogRequest
    ): ApiResult<List<org.example.project.dto.NomenclatureAnalogDto>> =
        ApiResult.Error(org.example.project.dto.ApiError("NOT_IMPLEMENTED", "Not implemented"))

    suspend fun getStock(warehouseId: Long?, nomenclatureId: Long?): ApiResult<List<StockBalanceDto>>

    suspend fun listReceipts(
        warehouseId: Long? = null,
        purchaseOrderId: Long? = null,
        status: ReceiptStatus? = null
    ): ApiResult<List<ReceiptDto>>
    suspend fun createReceipt(request: CreateReceiptRequest): ApiResult<ReceiptDto>
    suspend fun getReceipt(id: Long): ApiResult<ReceiptDto>
    suspend fun postReceipt(id: Long, request: PostReceiptRequest): ApiResult<ReceiptDto>
    /** Склад вносит факт, ячейки и расхождения в поступление (D-61). */
    suspend fun acceptReceiptLines(
        id: Long,
        request: org.example.project.dto.AcceptReceiptLinesRequest
    ): ApiResult<ReceiptDto> =
        ApiResult.Error(org.example.project.dto.ApiError("NOT_IMPLEMENTED", "Not implemented"))
    suspend fun getReceiptQc(receiptId: Long): ApiResult<org.example.project.dto.ReceiptQcDto> =
        ApiResult.Error(org.example.project.dto.ApiError("NOT_IMPLEMENTED", "Not implemented"))
    suspend fun inspectReceiptQc(
        receiptId: Long,
        request: org.example.project.dto.InspectReceiptQcRequest
    ): ApiResult<org.example.project.dto.ReceiptQcDto> =
        ApiResult.Error(org.example.project.dto.ApiError("NOT_IMPLEMENTED", "Not implemented"))
    suspend fun listMovements(
        warehouseId: Long? = null,
        nomenclatureId: Long? = null,
        type: String? = null,
        limit: Int = 100
    ): ApiResult<List<org.example.project.dto.StockMovementDto>> = ApiResult.Success(emptyList())
    suspend fun listLowStock(): ApiResult<List<org.example.project.dto.LowStockItemDto>> = ApiResult.Success(emptyList())
    suspend fun listLots(nomenclatureId: Long? = null): ApiResult<List<org.example.project.dto.LotDto>> =
        ApiResult.Success(emptyList())
    suspend fun listSerials(nomenclatureId: Long? = null): ApiResult<List<org.example.project.dto.SerialItemDto>> =
        ApiResult.Success(emptyList())

    /** Поздняя связь проведённой приёмки с заказом или накладной. */
    suspend fun linkReceipt(id: Long, request: LinkReceiptRequest): ApiResult<ReceiptDto>

    /** Ожидаемые к приёмке позиции по заказам: заказано/поставлено/принято/осталось. */
    suspend fun listExpectedReceipts(purchaseOrderId: Long? = null): ApiResult<List<ExpectedReceiptLineDto>>

    suspend fun createReservation(request: CreateReservationRequest): ApiResult<ReservationDto>
    suspend fun listReservations(
        warehouseId: Long?,
        nomenclatureId: Long?,
        status: ReservationStatus?
    ): ApiResult<List<ReservationDto>>

    suspend fun getReservation(id: Long): ApiResult<ReservationDto>
    suspend fun releaseReservation(id: Long, request: ReleaseReservationRequest): ApiResult<ReservationDto>

    // Перемещения (ТЗ §8.6)
    suspend fun createTransfer(request: CreateTransferRequest): ApiResult<TransferDto>
    suspend fun listTransfers(
        warehouseId: Long?,
        nomenclatureId: Long?,
        status: TransferStatus?
    ): ApiResult<List<TransferDto>>
    suspend fun getTransfer(id: Long): ApiResult<TransferDto>
    suspend fun shipTransfer(id: Long, request: ShipTransferRequest): ApiResult<TransferDto>
    suspend fun markTransferInTransit(id: Long, request: MarkTransferInTransitRequest): ApiResult<TransferDto>
    suspend fun receiveTransfer(id: Long, request: ReceiveTransferRequest): ApiResult<TransferDto>
    suspend fun cancelTransfer(id: Long, request: CancelTransferRequest): ApiResult<TransferDto>

    // Выдача (ТЗ §8.8)
    suspend fun createIssue(request: CreateIssueOrderRequest): ApiResult<IssueOrderDto>
    suspend fun listIssues(warehouseId: Long?, status: IssueStatus?): ApiResult<List<IssueOrderDto>>
    suspend fun getIssue(id: Long): ApiResult<IssueOrderDto>
    suspend fun pickIssue(id: Long, request: PickIssueRequest): ApiResult<IssueOrderDto>
    suspend fun issueOrder(id: Long, request: IssueActionRequest): ApiResult<IssueOrderDto>
    suspend fun confirmIssue(id: Long, request: IssueActionRequest): ApiResult<IssueOrderDto>
    suspend fun refuseIssue(id: Long, request: RefuseIssueRequest): ApiResult<IssueOrderDto>
    suspend fun cancelIssue(id: Long, request: IssueActionRequest): ApiResult<IssueOrderDto>

    // Возвраты (ТЗ §8.9)
    suspend fun createReturn(request: CreateReturnOrderRequest): ApiResult<ReturnOrderDto>
    suspend fun listReturns(warehouseId: Long?, status: ReturnStatus?): ApiResult<List<ReturnOrderDto>>
    suspend fun getReturn(id: Long): ApiResult<ReturnOrderDto>
    suspend fun receiveReturn(id: Long, request: ReturnActionRequest): ApiResult<ReturnOrderDto>
    suspend fun inspectReturnLine(
        id: Long,
        lineId: Long,
        request: InspectReturnLineRequest
    ): ApiResult<ReturnOrderDto>

    // Списание (ТЗ §8.10)
    suspend fun createWriteOff(request: CreateWriteOffRequest): ApiResult<WriteOffDto>
    suspend fun listWriteOffs(warehouseId: Long?, status: WriteOffStatus?): ApiResult<List<WriteOffDto>>
    suspend fun getWriteOff(id: Long): ApiResult<WriteOffDto>
    suspend fun postWriteOff(id: Long, request: WriteOffActionRequest): ApiResult<WriteOffDto>
    suspend fun cancelWriteOff(id: Long, request: CancelWriteOffRequest): ApiResult<WriteOffDto>
    suspend fun getWriteOffLimit(warehouseId: Long): ApiResult<WriteOffLimitDto>
    suspend fun setWriteOffLimit(warehouseId: Long, request: SetWriteOffLimitRequest): ApiResult<WriteOffLimitDto>

    // Инвентаризация (ТЗ §8.11)
    suspend fun createInventory(request: CreateInventoryRequest): ApiResult<InventoryDto>
    suspend fun listInventories(warehouseId: Long?, status: InventoryStatus?): ApiResult<List<InventoryDto>>
    suspend fun getInventory(id: Long): ApiResult<InventoryDto>
    suspend fun bookInventory(id: Long, request: InventoryActionRequest): ApiResult<InventoryDto>
    suspend fun submitInventoryCounts(id: Long, request: SubmitInventoryCountsRequest): ApiResult<InventoryDto>
    suspend fun approveInventory(id: Long, request: InventoryActionRequest): ApiResult<InventoryDto>
    suspend fun postInventory(id: Long, request: InventoryActionRequest): ApiResult<InventoryDto>
    suspend fun pauseInventory(id: Long, request: InventoryActionRequest): ApiResult<InventoryDto>
    suspend fun resumeInventory(id: Long, request: InventoryActionRequest): ApiResult<InventoryDto>
    suspend fun cancelInventory(id: Long, request: CancelInventoryRequest): ApiResult<InventoryDto>

    // Подсказка ячейки и поверка серийных экземпляров (из supply2)
    suspend fun suggestLocation(warehouseId: Long, nomenclatureId: Long): ApiResult<SuggestLocationDto>
    suspend fun updateSerialCalibration(serialId: Long, request: UpdateSerialCalibrationRequest): ApiResult<SerialItemDto>
}
