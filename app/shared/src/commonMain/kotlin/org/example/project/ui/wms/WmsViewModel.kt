package org.example.project.ui.wms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import org.example.project.data.ApiResult
import org.example.project.data.WmsRepository
import org.example.project.dto.ApiError
import org.example.project.dto.CreateNomenclatureBarcodeRequest
import org.example.project.dto.CreateNomenclatureRequest
import org.example.project.dto.CreateReceiptRequest
import org.example.project.dto.CreateReservationRequest
import org.example.project.dto.CreateStorageLocationRequest
import org.example.project.dto.CreateWarehouseRequest
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.PostReceiptRequest
import org.example.project.dto.ReceiptDto
import org.example.project.dto.ReceiptLineInput
import org.example.project.dto.ReceiptStatus
import org.example.project.dto.ReleaseReservationRequest
import org.example.project.dto.ReservationDto
import org.example.project.dto.ReservationStatus
import org.example.project.dto.StorageLocationKind
import org.example.project.dto.StorageLocationPurpose
import org.example.project.dto.CancelTransferRequest
import org.example.project.dto.CreateIssueOrderRequest
import org.example.project.dto.CreateReturnOrderRequest
import org.example.project.dto.CreateTransferRequest
import org.example.project.dto.InspectReturnLineRequest
import org.example.project.dto.IssueActionRequest
import org.example.project.dto.IssueLineInput
import org.example.project.dto.IssueOrderDto
import org.example.project.dto.IssuePickInput
import org.example.project.dto.IssueStatus
import org.example.project.dto.MarkTransferInTransitRequest
import org.example.project.dto.PickIssueRequest
import org.example.project.dto.ReceiveTransferRequest
import org.example.project.dto.RefuseIssueRequest
import org.example.project.dto.ReturnActionRequest
import org.example.project.dto.ReturnInspectionDecision
import org.example.project.dto.ReturnLineInput
import org.example.project.dto.ReturnOrderDto
import org.example.project.dto.ReturnStatus
import org.example.project.dto.ShipTransferRequest
import org.example.project.dto.TransferDto
import org.example.project.dto.TransferKind
import org.example.project.dto.TransferStatus
import org.example.project.dto.CancelWriteOffRequest
import org.example.project.dto.CreateWriteOffRequest
import org.example.project.dto.SetWriteOffLimitRequest
import org.example.project.dto.WriteOffActionRequest
import org.example.project.dto.WriteOffDto
import org.example.project.dto.WriteOffLineInput
import org.example.project.dto.WriteOffReasonType
import org.example.project.dto.WriteOffStatus
import org.example.project.dto.CancelInventoryRequest
import org.example.project.dto.CreateInventoryRequest
import org.example.project.dto.InventoryActionRequest
import org.example.project.dto.InventoryCountInput
import org.example.project.dto.InventoryDto
import org.example.project.dto.InventoryScopeType
import org.example.project.dto.InventoryStatus
import org.example.project.dto.SubmitInventoryCountsRequest

class WmsViewModel(
    private val repository: WmsRepository,
    private val currentUserId: Long
) : ViewModel() {

    private val _uiState = MutableStateFlow(WmsUiState())
    val uiState: StateFlow<WmsUiState> = _uiState.asStateFlow()

    init {
        loadWarehouses()
        loadNomenclature()
        loadReservations()
        loadStock()
    }

    fun selectTab(tab: WmsTab) {
        _uiState.value = _uiState.value.copy(
            selectedTab = tab,
            operationError = null,
            selectedNomenclature = null,
            currentReservation = null
        )
        when (tab) {
            WmsTab.WAREHOUSES -> loadWarehouses()
            WmsTab.LOCATIONS -> {
                val warehouseId = _uiState.value.locationFilterWarehouseId
                    ?: _uiState.value.warehouses.firstOrNull()?.id
                warehouseId?.let { loadLocations(it) }
            }
            WmsTab.NOMENCLATURE -> loadNomenclature()
            WmsTab.STOCK -> loadStock()
            WmsTab.RECEIPTS -> loadReceipts()
            WmsTab.RESERVATIONS -> loadReservations()
            WmsTab.TRANSFERS -> loadTransfers()
            WmsTab.ISSUES -> loadIssues()
            WmsTab.RETURNS -> loadReturns()
            WmsTab.WRITE_OFFS -> loadWriteOffs()
            WmsTab.INVENTORIES -> loadInventories()
        }
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(operationError = null)
    }

    fun showDialog(dialog: WmsDialog) {
        _uiState.value = _uiState.value.copy(activeDialog = dialog, operationError = null)
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(activeDialog = null, operationError = null)
    }

    fun loadWarehouses() {
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.listWarehouses()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    warehouses = result.data,
                    isLoading = false,
                    operationError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun createWarehouse() {
        val form = _uiState.value.createWarehouseForm
        if (form.code.isBlank() || form.name.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Код и название склада обязательны")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.createWarehouse(
                CreateWarehouseRequest(
                    code = form.code.trim(),
                    name = form.name.trim(),
                    address = form.address.takeIf { it.isNotBlank() }?.trim()
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createWarehouseForm = CreateWarehouseForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadWarehouses()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun loadLocations(warehouseId: Long) {
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.listLocations(warehouseId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    locations = result.data,
                    locationFilterWarehouseId = warehouseId,
                    isLoading = false,
                    operationError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun createLocation() {
        val form = _uiState.value.createLocationForm
        val warehouseId = form.warehouseId
        if (warehouseId == null || form.code.isBlank() || form.name.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Склад, код и название ячейки обязательны")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.createLocation(
                CreateStorageLocationRequest(
                    warehouseId = warehouseId,
                    parentId = form.parentId.toLongOrNull(),
                    code = form.code.trim(),
                    name = form.name.trim(),
                    kind = form.kind,
                    purpose = form.purpose,
                    capacity = form.capacity.takeIf { it.isNotBlank() }?.trim()
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createLocationForm = CreateLocationForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadLocations(warehouseId)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun loadNomenclature() {
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.listNomenclature()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    nomenclature = result.data,
                    isLoading = false,
                    operationError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun createNomenclature() {
        val form = _uiState.value.createNomenclatureForm
        if (form.code.isBlank() || form.name.isBlank() || form.unit.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Код, название и единица измерения обязательны")
            )
            return
        }
        val barcodes = form.barcodes.filter { it.barcode.isNotBlank() }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.createNomenclature(
                CreateNomenclatureRequest(
                    code = form.code.trim(),
                    name = form.name.trim(),
                    description = form.description.takeIf { it.isNotBlank() }?.trim(),
                    type = form.type,
                    unit = form.unit.trim(),
                    lotTracked = form.lotTracked,
                    serialTracked = form.serialTracked,
                    barcodes = barcodes
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createNomenclatureForm = CreateNomenclatureForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadNomenclature()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun archiveNomenclature(nomenclature: NomenclatureDto) {
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.archiveNomenclature(nomenclature.id)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        selectedNomenclature = null
                    )
                    loadNomenclature()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun selectNomenclature(nomenclature: NomenclatureDto?) {
        _uiState.value = _uiState.value.copy(selectedNomenclature = nomenclature, operationError = null)
    }

    fun loadStock() {
        val filters = _uiState.value.stockFilters
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.getStock(filters.warehouseId, filters.nomenclatureId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    stock = result.data,
                    isLoading = false,
                    operationError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun createReceipt() {
        val form = _uiState.value.createReceiptForm
        val warehouseId = form.warehouseId
        if (warehouseId == null || form.lines.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Выберите склад и добавьте хотя бы одну строку")
            )
            return
        }
        val validLines = form.lines.mapIndexed { index, line ->
            val nomId = line.nomenclatureId
            val locId = line.locationId
            val qty = line.expectedQuantity
            if (nomId == null || locId == null || qty.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    operationError = ApiError("VALIDATION_ERROR", "В строке ${index + 1} не заполнены номенклатура, ячейка или ожидаемое количество")
                )
                return
            }
            if (_uiState.value.nomenclature.find { it.id == nomId }?.lotTracked == true && line.lotNumber.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    operationError = ApiError("VALIDATION_ERROR", "В строке ${index + 1} укажите номер партии")
                )
                return
            }
            ReceiptLineInput(
                lineNo = line.lineNo,
                nomenclatureId = nomId,
                expectedQuantity = qty.trim(),
                receivedQuantity = line.receivedQuantity.takeIf { it.isNotBlank() }?.trim(),
                lotNumber = line.lotNumber.takeIf { it.isNotBlank() }?.trim(),
                locationId = locId,
                note = line.note.takeIf { it.isNotBlank() }?.trim()
            )
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.createReceipt(
                CreateReceiptRequest(
                    sourceType = form.sourceType,
                    sourceRef = form.sourceRef.takeIf { it.isNotBlank() }?.trim(),
                    warehouseId = warehouseId,
                    expectedAt = form.expectedAt.takeIf { it.isNotBlank() }?.trim(),
                    note = form.note.takeIf { it.isNotBlank() }?.trim(),
                    lines = validLines
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createReceiptForm = CreateReceiptForm(),
                        currentReceipt = result.data,
                        activeDialog = null,
                        isLoading = false
                    )
                    loadReceipts()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun loadReceipts() {
        val filters = _uiState.value.receiptFilters
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.listReceipts(filters.warehouseId, filters.status)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    receipts = result.data,
                    isLoading = false,
                    operationError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun selectReceipt(receipt: ReceiptDto) {
        _uiState.value = _uiState.value.copy(currentReceipt = receipt, operationError = null)
    }

    fun updateReceiptWarehouseFilter(warehouseId: Long?) {
        _uiState.value = _uiState.value.copy(
            receiptFilters = _uiState.value.receiptFilters.copy(warehouseId = warehouseId)
        )
        loadReceipts()
    }

    fun updateReceiptStatusFilter(status: ReceiptStatus?) {
        _uiState.value = _uiState.value.copy(
            receiptFilters = _uiState.value.receiptFilters.copy(status = status)
        )
        loadReceipts()
    }

    fun postReceipt() {
        val receipt = _uiState.value.currentReceipt ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.postReceipt(receipt.id, PostReceiptRequest(receipt.version))) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        currentReceipt = result.data,
                        isLoading = false,
                        operationError = null
                    )
                    loadReceipts()
                    loadStock()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun clearCurrentReceipt() {
        _uiState.value = _uiState.value.copy(currentReceipt = null)
    }

    fun createReservation() {
        val form = _uiState.value.createReservationForm
        val warehouseId = form.warehouseId
        val nomenclatureId = form.nomenclatureId
        val locationId = form.locationId
        if (warehouseId == null || nomenclatureId == null || locationId == null || form.quantity.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Склад, номенклатура, ячейка и количество обязательны")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.createReservation(
                CreateReservationRequest(
                    reasonType = form.reasonType,
                    projectId = form.projectId.toLongOrNull(),
                    taskId = form.taskId.toLongOrNull(),
                    reasonNote = form.reasonNote.takeIf { it.isNotBlank() },
                    nomenclatureId = nomenclatureId,
                    warehouseId = warehouseId,
                    locationId = locationId,
                    lotId = form.lotId.toLongOrNull(),
                    serialId = form.serialId.toLongOrNull(),
                    quantity = form.quantity.trim(),
                    ownerId = currentUserId,
                    expiresAt = form.expiresAt.takeIf { it.isNotBlank() }
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createReservationForm = CreateReservationForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadReservations()
                    loadStock()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun loadReservations() {
        val filters = _uiState.value.reservationFilters
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.listReservations(filters.warehouseId, filters.nomenclatureId, filters.status)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    reservations = result.data,
                    isLoading = false,
                    operationError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun selectReservation(reservation: ReservationDto?) {
        _uiState.value = _uiState.value.copy(currentReservation = reservation, operationError = null)
    }

    fun releaseReservation() {
        val reservation = _uiState.value.currentReservation ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.releaseReservation(
                reservation.id,
                ReleaseReservationRequest(reservation.version, _uiState.value.releaseReason.takeIf { it.isNotBlank() })
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        currentReservation = result.data,
                        releaseReason = "",
                        isLoading = false
                    )
                    loadReservations()
                    loadStock()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun clearCurrentReservation() {
        _uiState.value = _uiState.value.copy(currentReservation = null, releaseReason = "")
    }

    // ---------------- Перемещения (§8.6) ----------------

    fun loadTransfers() {
        val status = _uiState.value.transferStatusFilter
        _uiState.value = _uiState.value.copy(transfersLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.listTransfers(null, null, status)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    transfers = result.data,
                    transfersLoading = false,
                    operationError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    transfersLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateTransferStatusFilter(status: TransferStatus?) {
        _uiState.value = _uiState.value.copy(transferStatusFilter = status)
        loadTransfers()
    }

    fun selectTransfer(transfer: TransferDto?) {
        _uiState.value = _uiState.value.copy(
            selectedTransfer = transfer,
            transferReceiveQuantity = transfer?.quantity ?: "",
            operationError = null
        )
    }

    fun updateTransferReceiveQuantity(value: String) {
        _uiState.value = _uiState.value.copy(transferReceiveQuantity = value)
    }

    fun updateCreateTransferForm(form: CreateTransferForm) {
        _uiState.value = _uiState.value.copy(createTransferForm = form)
    }

    fun onTransferFromWarehouseChanged(warehouseId: Long?) {
        _uiState.value = _uiState.value.copy(
            createTransferForm = _uiState.value.createTransferForm.copy(
                fromWarehouseId = warehouseId,
                fromLocationId = null
            ),
            transferLocationsFrom = emptyList()
        )
        warehouseId?.let { id ->
            viewModelScope.launch {
                when (val result = repository.listLocations(id)) {
                    is ApiResult.Success -> _uiState.value = _uiState.value.copy(transferLocationsFrom = result.data)
                    is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
                }
            }
        }
    }

    fun onTransferToWarehouseChanged(warehouseId: Long?) {
        _uiState.value = _uiState.value.copy(
            createTransferForm = _uiState.value.createTransferForm.copy(
                toWarehouseId = warehouseId,
                toLocationId = null
            ),
            transferLocationsTo = emptyList()
        )
        warehouseId?.let { id ->
            viewModelScope.launch {
                when (val result = repository.listLocations(id)) {
                    is ApiResult.Success -> _uiState.value = _uiState.value.copy(transferLocationsTo = result.data)
                    is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
                }
            }
        }
    }

    fun createTransfer() {
        val form = _uiState.value.createTransferForm
        val fromWarehouseId = form.fromWarehouseId
        val fromLocationId = form.fromLocationId
        val toWarehouseId = form.toWarehouseId
        val toLocationId = form.toLocationId
        val nomenclatureId = form.nomenclatureId
        if (fromWarehouseId == null || fromLocationId == null || toWarehouseId == null ||
            toLocationId == null || nomenclatureId == null || form.quantity.isBlank()
        ) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError(
                    "VALIDATION_ERROR",
                    "Заполните склады, ячейки, номенклатуру и количество"
                )
            )
            return
        }
        if (fromWarehouseId == toWarehouseId && fromLocationId == toLocationId) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Ячейка-источник и ячейка-получатель совпадают")
            )
            return
        }
        val kind = if (fromWarehouseId == toWarehouseId) TransferKind.INTERNAL else TransferKind.INTER_WAREHOUSE
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.createTransfer(
                CreateTransferRequest(
                    kind = kind,
                    fromWarehouseId = fromWarehouseId,
                    fromLocationId = fromLocationId,
                    toWarehouseId = toWarehouseId,
                    toLocationId = toLocationId,
                    nomenclatureId = nomenclatureId,
                    lotId = form.lotId.toLongOrNull(),
                    serialId = form.serialId.toLongOrNull(),
                    quantity = form.quantity.trim(),
                    note = form.note.takeIf { it.isNotBlank() }?.trim(),
                    idempotencyKey = newIdempotencyKey()
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createTransferForm = CreateTransferForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadTransfers()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun shipTransfer() = transferCommand { id, version ->
        repository.shipTransfer(id, ShipTransferRequest(version, newIdempotencyKey()))
    }

    fun markTransferInTransit() = transferCommand { id, version ->
        repository.markTransferInTransit(id, MarkTransferInTransitRequest(version, newIdempotencyKey()))
    }

    fun receiveTransfer() {
        val transfer = _uiState.value.selectedTransfer ?: return
        val quantity = _uiState.value.transferReceiveQuantity.trim()
        if (quantity.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Укажите принятое количество")
            )
            return
        }
        transferCommand { id, version ->
            repository.receiveTransfer(
                id,
                ReceiveTransferRequest(
                    version = version,
                    receivedQuantity = quantity,
                    note = null,
                    idempotencyKey = newIdempotencyKey()
                )
            )
        }
    }

    fun cancelTransfer() {
        if (_uiState.value.transferCancelReason.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Укажите причину отмены")
            )
            return
        }
        val reason = _uiState.value.transferCancelReason.trim()
        transferCommand { id, version ->
            repository.cancelTransfer(id, CancelTransferRequest(version, reason, newIdempotencyKey()))
        }
    }

    fun updateTransferCancelReason(value: String) {
        _uiState.value = _uiState.value.copy(transferCancelReason = value)
    }

    private fun transferCommand(
        action: suspend (Long, Long) -> ApiResult<TransferDto>
    ) {
        val transfer = _uiState.value.selectedTransfer ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = action(transfer.id, transfer.version)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        selectedTransfer = result.data,
                        transferReceiveQuantity = result.data.quantity,
                        transferCancelReason = "",
                        isLoading = false
                    )
                    loadTransfers()
                    loadStock()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    // ---------------- Выдача (§8.8) ----------------

    fun loadIssues() {
        val status = _uiState.value.issueStatusFilter
        _uiState.value = _uiState.value.copy(issuesLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.listIssues(null, status)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    issues = result.data,
                    issuesLoading = false,
                    operationError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    issuesLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateIssueStatusFilter(status: IssueStatus?) {
        _uiState.value = _uiState.value.copy(issueStatusFilter = status)
        loadIssues()
    }

    fun selectIssue(issue: IssueOrderDto?) {
        _uiState.value = _uiState.value.copy(
            selectedIssue = issue,
            issuePickForms = emptyMap(),
            issueRefusalReason = "",
            operationError = null
        )
        // Ячейки нужны для формы отбора по строкам выбранного заказа.
        issue?.let { order ->
            viewModelScope.launch {
                when (val result = repository.listLocations(order.warehouseId)) {
                    is ApiResult.Success -> _uiState.value = _uiState.value.copy(issueLocations = result.data)
                    is ApiResult.Error -> { }
                }
            }
        }
    }

    fun updateCreateIssueForm(form: CreateIssueForm) {
        _uiState.value = _uiState.value.copy(createIssueForm = form)
    }

    fun onIssueWarehouseChanged(warehouseId: Long?) {
        _uiState.value = _uiState.value.copy(
            createIssueForm = _uiState.value.createIssueForm.copy(warehouseId = warehouseId),
            issueLocations = emptyList()
        )
        warehouseId?.let { id ->
            viewModelScope.launch {
                when (val result = repository.listLocations(id)) {
                    is ApiResult.Success -> _uiState.value = _uiState.value.copy(issueLocations = result.data)
                    is ApiResult.Error -> { }
                }
            }
        }
    }

    fun addIssueLine() {
        val form = _uiState.value.createIssueForm
        val nextLineNo = (form.lines.maxOfOrNull { it.lineNo } ?: 0) + 1
        _uiState.value = _uiState.value.copy(
            createIssueForm = form.copy(lines = form.lines + CreateIssueLineForm(lineNo = nextLineNo))
        )
    }

    fun updateIssueLine(index: Int, line: CreateIssueLineForm) {
        val form = _uiState.value.createIssueForm
        if (index !in form.lines.indices) return
        val lines = form.lines.toMutableList()
        lines[index] = line
        _uiState.value = _uiState.value.copy(createIssueForm = form.copy(lines = lines))
    }

    fun removeIssueLine(index: Int) {
        val form = _uiState.value.createIssueForm
        if (index !in form.lines.indices) return
        val lines = form.lines.toMutableList()
        lines.removeAt(index)
        _uiState.value = _uiState.value.copy(createIssueForm = form.copy(lines = lines))
    }

    fun createIssue() {
        val form = _uiState.value.createIssueForm
        val warehouseId = form.warehouseId
        val lines = form.lines.mapNotNull { line ->
            val nomenclatureId = line.nomenclatureId ?: return@mapNotNull null
            if (line.quantity.isBlank()) return@mapNotNull null
            IssueLineInput(
                lineNo = line.lineNo,
                nomenclatureId = nomenclatureId,
                requestedQuantity = line.quantity.trim(),
                reservationId = line.reservationId.toLongOrNull(),
                note = line.note.takeIf { it.isNotBlank() }?.trim()
            )
        }
        if (warehouseId == null || lines.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError(
                    "VALIDATION_ERROR",
                    "Выберите склад и добавьте хотя бы одну строку с количеством"
                )
            )
            return
        }
        if (form.reasonType == org.example.project.dto.ReservationReasonType.PROJECT &&
            form.projectId.isBlank() && form.taskId.isBlank()
        ) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Для основания «Проект» укажите проект или задачу")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.createIssue(
                CreateIssueOrderRequest(
                    warehouseId = warehouseId,
                    reasonType = form.reasonType,
                    projectId = form.projectId.toLongOrNull(),
                    taskId = form.taskId.toLongOrNull(),
                    reasonNote = form.reasonNote.takeIf { it.isNotBlank() }?.trim(),
                    recipientId = form.recipientId.toLongOrNull() ?: currentUserId,
                    note = form.note.takeIf { it.isNotBlank() }?.trim(),
                    lines = lines,
                    idempotencyKey = newIdempotencyKey()
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createIssueForm = CreateIssueForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadIssues()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateIssuePickForm(lineId: Long, form: IssuePickForm) {
        val forms = _uiState.value.issuePickForms.toMutableMap()
        forms[lineId] = form
        _uiState.value = _uiState.value.copy(issuePickForms = forms)
    }

    fun pickIssueLine(lineId: Long) {
        val issue = _uiState.value.selectedIssue ?: return
        val form = _uiState.value.issuePickForms[lineId] ?: IssuePickForm()
        val locationId = form.locationId
        if (locationId == null || form.quantity.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Для отбора укажите ячейку и количество")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.pickIssue(
                issue.id,
                PickIssueRequest(
                    version = issue.version,
                    picks = listOf(
                        IssuePickInput(
                            lineId = lineId,
                            locationId = locationId,
                            lotId = form.lotId.toLongOrNull(),
                            serialId = form.serialId.toLongOrNull(),
                            quantity = form.quantity.trim()
                        )
                    ),
                    idempotencyKey = newIdempotencyKey()
                )
            )) {
                is ApiResult.Success -> {
                    val picks = _uiState.value.issuePickForms.toMutableMap()
                    picks.remove(lineId)
                    _uiState.value = _uiState.value.copy(
                        selectedIssue = result.data,
                        issuePickForms = picks,
                        isLoading = false
                    )
                    loadIssues()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun issueSelectedOrder() = issueCommand { id, version ->
        repository.issueOrder(id, IssueActionRequest(version, newIdempotencyKey()))
    }

    fun confirmSelectedIssue() = issueCommand { id, version ->
        repository.confirmIssue(id, IssueActionRequest(version, newIdempotencyKey()))
    }

    fun cancelSelectedIssue() = issueCommand { id, version ->
        repository.cancelIssue(id, IssueActionRequest(version, newIdempotencyKey()))
    }

    fun updateIssueRefusalReason(value: String) {
        _uiState.value = _uiState.value.copy(issueRefusalReason = value)
    }

    fun refuseSelectedIssue() {
        val issue = _uiState.value.selectedIssue ?: return
        val reason = _uiState.value.issueRefusalReason.trim()
        if (reason.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Укажите причину отказа")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.refuseIssue(
                issue.id,
                RefuseIssueRequest(issue.version, reason, newIdempotencyKey())
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        selectedIssue = result.data,
                        issueRefusalReason = "",
                        isLoading = false
                    )
                    loadIssues()
                    loadStock()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    private fun issueCommand(
        action: suspend (Long, Long) -> ApiResult<IssueOrderDto>
    ) {
        val issue = _uiState.value.selectedIssue ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = action(issue.id, issue.version)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(selectedIssue = result.data, isLoading = false)
                    loadIssues()
                    loadStock()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    // ---------------- Возвраты (§8.9) ----------------

    fun loadReturns() {
        val status = _uiState.value.returnStatusFilter
        _uiState.value = _uiState.value.copy(returnsLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.listReturns(null, status)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    returns = result.data,
                    returnsLoading = false,
                    operationError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    returnsLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateReturnStatusFilter(status: ReturnStatus?) {
        _uiState.value = _uiState.value.copy(returnStatusFilter = status)
        loadReturns()
    }

    fun selectReturn(order: ReturnOrderDto?) {
        _uiState.value = _uiState.value.copy(
            selectedReturn = order,
            returnInspectForms = emptyMap(),
            operationError = null
        )
        // Ячейки нужны для размещения годной продукции при проверке.
        order?.let { ret ->
            viewModelScope.launch {
                when (val result = repository.listLocations(ret.warehouseId)) {
                    is ApiResult.Success -> _uiState.value = _uiState.value.copy(returnLocations = result.data)
                    is ApiResult.Error -> { }
                }
            }
        }
    }

    fun updateCreateReturnForm(form: CreateReturnForm) {
        _uiState.value = _uiState.value.copy(createReturnForm = form)
    }

    fun onReturnWarehouseChanged(warehouseId: Long?) {
        _uiState.value = _uiState.value.copy(
            createReturnForm = _uiState.value.createReturnForm.copy(
                warehouseId = warehouseId,
                quarantineLocationId = null
            ),
            returnLocations = emptyList()
        )
        warehouseId?.let { id ->
            viewModelScope.launch {
                when (val result = repository.listLocations(id)) {
                    is ApiResult.Success -> _uiState.value = _uiState.value.copy(returnLocations = result.data)
                    is ApiResult.Error -> { }
                }
            }
        }
    }

    fun addReturnLine() {
        _uiState.value = _uiState.value.copy(
            createReturnForm = _uiState.value.createReturnForm.copy(
                lines = _uiState.value.createReturnForm.lines + CreateReturnLineForm()
            )
        )
    }

    fun updateReturnLine(index: Int, line: CreateReturnLineForm) {
        val form = _uiState.value.createReturnForm
        if (index !in form.lines.indices) return
        val lines = form.lines.toMutableList()
        lines[index] = line
        _uiState.value = _uiState.value.copy(createReturnForm = form.copy(lines = lines))
    }

    fun removeReturnLine(index: Int) {
        val form = _uiState.value.createReturnForm
        if (index !in form.lines.indices) return
        val lines = form.lines.toMutableList()
        lines.removeAt(index)
        _uiState.value = _uiState.value.copy(createReturnForm = form.copy(lines = lines))
    }

    fun createReturn() {
        val form = _uiState.value.createReturnForm
        val warehouseId = form.warehouseId
        val quarantineLocationId = form.quarantineLocationId
        val issueOrderId = form.issueOrderId.toLongOrNull()
        val lines = form.lines.mapNotNull { line ->
            val issueLineId = line.issueLineId.toLongOrNull() ?: return@mapNotNull null
            if (line.quantity.isBlank()) return@mapNotNull null
            ReturnLineInput(
                issueLineId = issueLineId,
                quantity = line.quantity.trim(),
                lotId = line.lotId.toLongOrNull(),
                serialId = line.serialId.toLongOrNull()
            )
        }
        if (warehouseId == null || quarantineLocationId == null || issueOrderId == null ||
            form.reason.isBlank() || lines.isEmpty()
        ) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError(
                    "VALIDATION_ERROR",
                    "Заполните заказ выдачи, карантинную ячейку, причину и строки возврата"
                )
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.createReturn(
                CreateReturnOrderRequest(
                    issueOrderId = issueOrderId,
                    quarantineLocationId = quarantineLocationId,
                    returnedById = currentUserId,
                    reason = form.reason.trim(),
                    lines = lines,
                    idempotencyKey = newIdempotencyKey()
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createReturnForm = CreateReturnForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadReturns()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun receiveSelectedReturn() {
        val order = _uiState.value.selectedReturn ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.receiveReturn(
                order.id,
                ReturnActionRequest(order.version, newIdempotencyKey())
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(selectedReturn = result.data, isLoading = false)
                    loadReturns()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateReturnInspectForm(lineId: Long, form: ReturnInspectForm) {
        val forms = _uiState.value.returnInspectForms.toMutableMap()
        forms[lineId] = form
        _uiState.value = _uiState.value.copy(returnInspectForms = forms)
    }

    fun inspectReturnLine(lineId: Long) {
        val order = _uiState.value.selectedReturn ?: return
        val form = _uiState.value.returnInspectForms[lineId] ?: ReturnInspectForm()
        if (form.decision == ReturnInspectionDecision.GOOD && form.targetLocationId == null) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Для годной продукции укажите ячейку размещения")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.inspectReturnLine(
                order.id,
                lineId,
                InspectReturnLineRequest(
                    version = order.version,
                    decision = form.decision,
                    targetLocationId = form.targetLocationId,
                    note = form.note.takeIf { it.isNotBlank() }?.trim(),
                    idempotencyKey = newIdempotencyKey()
                )
            )) {
                is ApiResult.Success -> {
                    val forms = _uiState.value.returnInspectForms.toMutableMap()
                    forms.remove(lineId)
                    _uiState.value = _uiState.value.copy(
                        selectedReturn = result.data,
                        returnInspectForms = forms,
                        isLoading = false
                    )
                    loadReturns()
                    loadStock()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    private fun newIdempotencyKey(): String =
        "ui-${Random.nextLong().toString(16)}-${Random.nextLong().toString(16)}"

    // ---------------- Списание (§8.10) ----------------

    fun loadWriteOffs() {
        val status = _uiState.value.writeOffStatusFilter
        _uiState.value = _uiState.value.copy(writeOffsLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.listWriteOffs(null, status)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    writeOffs = result.data,
                    writeOffsLoading = false,
                    operationError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    writeOffsLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateWriteOffStatusFilter(status: WriteOffStatus?) {
        _uiState.value = _uiState.value.copy(writeOffStatusFilter = status)
        loadWriteOffs()
    }

    fun selectWriteOff(writeOff: WriteOffDto?) {
        _uiState.value = _uiState.value.copy(
            selectedWriteOff = writeOff,
            writeOffCancelReason = "",
            operationError = null
        )
        writeOff?.let { order ->
            viewModelScope.launch {
                when (val result = repository.listLocations(order.warehouseId)) {
                    is ApiResult.Success -> _uiState.value = _uiState.value.copy(writeOffLocations = result.data)
                    is ApiResult.Error -> { }
                }
            }
        }
    }

    fun updateCreateWriteOffForm(form: CreateWriteOffForm) {
        _uiState.value = _uiState.value.copy(createWriteOffForm = form)
    }

    fun onWriteOffWarehouseChanged(warehouseId: Long?) {
        _uiState.value = _uiState.value.copy(
            createWriteOffForm = _uiState.value.createWriteOffForm.copy(
                warehouseId = warehouseId,
                lines = listOf(CreateWriteOffLineForm())
            ),
            writeOffLocations = emptyList(),
            writeOffLimit = "",
            operationError = null
        )
        warehouseId ?: return
        viewModelScope.launch {
            when (val locations = repository.listLocations(warehouseId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(writeOffLocations = locations.data)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = locations.error)
            }
            when (val limit = repository.getWriteOffLimit(warehouseId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    writeOffLimit = limit.data.limitQuantity,
                    writeOffLimitWarehouseId = warehouseId
                )
                is ApiResult.Error -> { }
            }
        }
    }

    fun updateWriteOffLimit(value: String) {
        _uiState.value = _uiState.value.copy(writeOffLimit = value)
    }

    fun saveWriteOffLimit() {
        val warehouseId = _uiState.value.writeOffLimitWarehouseId ?: _uiState.value.createWriteOffForm.warehouseId ?: return
        val limit = _uiState.value.writeOffLimit.trim()
        if (limit.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Укажите лимит списания без согласования")
            )
            return
        }
        viewModelScope.launch {
            when (val result = repository.setWriteOffLimit(warehouseId, SetWriteOffLimitRequest(limit))) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    writeOffLimit = result.data.limitQuantity,
                    operationError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    fun addWriteOffLine() {
        val form = _uiState.value.createWriteOffForm
        val nextLineNo = (form.lines.maxOfOrNull { it.lineNo } ?: 0) + 1
        _uiState.value = _uiState.value.copy(
            createWriteOffForm = form.copy(lines = form.lines + CreateWriteOffLineForm(lineNo = nextLineNo))
        )
    }

    fun updateWriteOffLine(index: Int, line: CreateWriteOffLineForm) {
        val form = _uiState.value.createWriteOffForm
        if (index !in form.lines.indices) return
        val lines = form.lines.toMutableList()
        lines[index] = line
        _uiState.value = _uiState.value.copy(createWriteOffForm = form.copy(lines = lines))
    }

    fun removeWriteOffLine(index: Int) {
        val form = _uiState.value.createWriteOffForm
        if (index !in form.lines.indices) return
        val lines = form.lines.toMutableList()
        lines.removeAt(index)
        _uiState.value = _uiState.value.copy(createWriteOffForm = form.copy(lines = lines))
    }

    fun createWriteOff() {
        val form = _uiState.value.createWriteOffForm
        val warehouseId = form.warehouseId
        if (warehouseId == null) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Выберите склад")
            )
            return
        }
        if (form.reasonNote.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Укажите основание списания")
            )
            return
        }
        if (form.reasonType == WriteOffReasonType.OTHER && form.reasonNote.trim().length < 10) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError(
                    "VALIDATION_ERROR",
                    "Для прочей причины основание должно быть не короче 10 символов"
                )
            )
            return
        }
        val lines = form.lines.mapNotNull { line ->
            val nomenclatureId = line.nomenclatureId ?: return@mapNotNull null
            val locationId = line.locationId ?: return@mapNotNull null
            if (line.quantity.isBlank()) return@mapNotNull null
            WriteOffLineInput(
                lineNo = line.lineNo,
                nomenclatureId = nomenclatureId,
                locationId = locationId,
                quantity = line.quantity.trim(),
                lotId = line.lotId.toLongOrNull(),
                serialId = line.serialId.toLongOrNull(),
                note = line.note.takeIf { it.isNotBlank() }?.trim()
            )
        }
        if (lines.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError(
                    "VALIDATION_ERROR",
                    "Добавьте хотя бы одну строку: номенклатуру, ячейку и количество"
                )
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.createWriteOff(
                CreateWriteOffRequest(
                    warehouseId = warehouseId,
                    reasonType = form.reasonType,
                    reasonNote = form.reasonNote.trim(),
                    note = form.note.takeIf { it.isNotBlank() }?.trim(),
                    lines = lines,
                    idempotencyKey = newIdempotencyKey()
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createWriteOffForm = CreateWriteOffForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadWriteOffs()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun postSelectedWriteOff() {
        val writeOff = _uiState.value.selectedWriteOff ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.postWriteOff(
                writeOff.id,
                WriteOffActionRequest(writeOff.version, newIdempotencyKey())
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(selectedWriteOff = result.data, isLoading = false)
                    loadWriteOffs()
                    loadStock()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateWriteOffCancelReason(value: String) {
        _uiState.value = _uiState.value.copy(writeOffCancelReason = value)
    }

    fun cancelSelectedWriteOff() {
        val writeOff = _uiState.value.selectedWriteOff ?: return
        val reason = _uiState.value.writeOffCancelReason.trim()
        if (reason.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Укажите причину отмены")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.cancelWriteOff(
                writeOff.id,
                CancelWriteOffRequest(writeOff.version, reason, newIdempotencyKey())
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        selectedWriteOff = result.data,
                        writeOffCancelReason = "",
                        isLoading = false
                    )
                    loadWriteOffs()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    // ---------------- Инвентаризация (§8.11) ----------------

    fun loadInventories() {
        val status = _uiState.value.inventoryStatusFilter
        _uiState.value = _uiState.value.copy(inventoriesLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.listInventories(null, status)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    inventories = result.data,
                    inventoriesLoading = false,
                    operationError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    inventoriesLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateInventoryStatusFilter(status: InventoryStatus?) {
        _uiState.value = _uiState.value.copy(inventoryStatusFilter = status)
        loadInventories()
    }

    fun selectInventory(inventory: InventoryDto?) {
        // Строки подсчёта инициализируются значением среза, чтобы комиссии
        // было достаточно поправить расхождения.
        val inputs = inventory?.snapshot?.associate { line ->
            line.id to (line.countedQuantity ?: line.bookQuantity)
        }.orEmpty()
        _uiState.value = _uiState.value.copy(
            selectedInventory = inventory,
            inventoryCountInputs = inputs,
            inventoryCancelReason = "",
            operationError = null
        )
    }

    fun updateInventoryCountInput(snapshotId: Long, value: String) {
        val inputs = _uiState.value.inventoryCountInputs.toMutableMap()
        inputs[snapshotId] = value
        _uiState.value = _uiState.value.copy(inventoryCountInputs = inputs)
    }

    fun updateInventoryCancelReason(value: String) {
        _uiState.value = _uiState.value.copy(inventoryCancelReason = value)
    }

    fun updateCreateInventoryForm(form: CreateInventoryForm) {
        _uiState.value = _uiState.value.copy(createInventoryForm = form)
    }

    fun onInventoryWarehouseChanged(warehouseId: Long?) {
        _uiState.value = _uiState.value.copy(
            createInventoryForm = _uiState.value.createInventoryForm.copy(
                warehouseId = warehouseId,
                scopeLocationId = null
            ),
            inventoryLocations = emptyList()
        )
        warehouseId ?: return
        viewModelScope.launch {
            when (val result = repository.listLocations(warehouseId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(inventoryLocations = result.data)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    fun createInventory() {
        val form = _uiState.value.createInventoryForm
        val warehouseId = form.warehouseId
        if (warehouseId == null) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Выберите склад")
            )
            return
        }
        if ((form.scopeType == InventoryScopeType.ZONE || form.scopeType == InventoryScopeType.CELL) &&
            form.scopeLocationId == null
        ) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Выберите зону или ячейку")
            )
            return
        }
        if (form.scopeType == InventoryScopeType.NOMENCLATURE && form.scopeNomenclatureId == null) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Выберите номенклатуру")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.createInventory(
                CreateInventoryRequest(
                    warehouseId = warehouseId,
                    scopeType = form.scopeType,
                    scopeLocationId = if (form.scopeType == InventoryScopeType.WAREHOUSE ||
                        form.scopeType == InventoryScopeType.NOMENCLATURE
                    ) null else form.scopeLocationId,
                    scopeNomenclatureId = if (form.scopeType == InventoryScopeType.NOMENCLATURE) {
                        form.scopeNomenclatureId
                    } else {
                        null
                    },
                    note = form.note.takeIf { it.isNotBlank() }?.trim(),
                    idempotencyKey = newIdempotencyKey()
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createInventoryForm = CreateInventoryForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadInventories()
                    selectInventory(result.data)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun bookSelectedInventory() = inventoryCommand { id, version ->
        repository.bookInventory(id, InventoryActionRequest(version, newIdempotencyKey()))
    }

    fun approveSelectedInventory() = inventoryCommand { id, version ->
        repository.approveInventory(id, InventoryActionRequest(version, newIdempotencyKey()))
    }

    fun postSelectedInventory() = inventoryCommand { id, version ->
        repository.postInventory(id, InventoryActionRequest(version, newIdempotencyKey()))
    }

    fun submitSelectedInventoryCounts() {
        val inventory = _uiState.value.selectedInventory ?: return
        if (inventory.snapshot.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Сначала зафиксируйте учётный срез")
            )
            return
        }
        val counts = inventory.snapshot.mapNotNull { line ->
            val value = _uiState.value.inventoryCountInputs[line.id]?.trim() ?: return@mapNotNull null
            if (value.isBlank()) return@mapNotNull null
            InventoryCountInput(
                snapshotId = line.id,
                countedQuantity = value,
                recount = line.countedQuantity != null
            )
        }
        if (counts.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Укажите фактическое количество")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.submitInventoryCounts(
                inventory.id,
                SubmitInventoryCountsRequest(
                    version = inventory.version,
                    counts = counts,
                    idempotencyKey = newIdempotencyKey()
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    selectInventory(result.data)
                    loadInventories()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun cancelSelectedInventory() {
        val inventory = _uiState.value.selectedInventory ?: return
        val reason = _uiState.value.inventoryCancelReason.trim()
        if (reason.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Укажите причину отмены")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.cancelInventory(
                inventory.id,
                CancelInventoryRequest(inventory.version, reason, newIdempotencyKey())
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        selectedInventory = result.data,
                        inventoryCancelReason = "",
                        isLoading = false
                    )
                    loadInventories()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    private fun inventoryCommand(
        action: suspend (Long, Long) -> ApiResult<InventoryDto>
    ) {
        val inventory = _uiState.value.selectedInventory ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = action(inventory.id, inventory.version)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    selectInventory(result.data)
                    loadInventories()
                    loadStock()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateCreateWarehouseForm(form: CreateWarehouseForm) {
        _uiState.value = _uiState.value.copy(createWarehouseForm = form)
    }

    fun updateCreateLocationForm(form: CreateLocationForm) {
        _uiState.value = _uiState.value.copy(createLocationForm = form)
    }

    fun updateCreateNomenclatureForm(form: CreateNomenclatureForm) {
        _uiState.value = _uiState.value.copy(createNomenclatureForm = form)
    }

    fun updateCreateReceiptForm(form: CreateReceiptForm) {
        _uiState.value = _uiState.value.copy(createReceiptForm = form)
    }

    fun onReceiptWarehouseChanged(warehouseId: Long?) {
        _uiState.value = _uiState.value.copy(
            createReceiptForm = _uiState.value.createReceiptForm.copy(
                warehouseId = warehouseId,
                lines = emptyList()
            ),
            receiptLocations = emptyList()
        )
        warehouseId?.let { loadReceiptLocations(it) }
    }

    private fun loadReceiptLocations(warehouseId: Long) {
        viewModelScope.launch {
            when (val result = repository.listLocations(warehouseId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(receiptLocations = result.data)
                is ApiResult.Error -> { }
            }
        }
    }

    fun addReceiptLine() {
        val form = _uiState.value.createReceiptForm
        val nextLineNo = (form.lines.maxOfOrNull { it.lineNo } ?: 0) + 1
        _uiState.value = _uiState.value.copy(
            createReceiptForm = form.copy(lines = form.lines + CreateReceiptLineForm(lineNo = nextLineNo))
        )
    }

    fun updateReceiptLine(index: Int, line: CreateReceiptLineForm) {
        val form = _uiState.value.createReceiptForm
        val lines = form.lines.toMutableList()
        if (index in lines.indices) {
            lines[index] = line
            _uiState.value = _uiState.value.copy(createReceiptForm = form.copy(lines = lines))
        }
    }

    fun removeReceiptLine(index: Int) {
        val form = _uiState.value.createReceiptForm
        val lines = form.lines.toMutableList()
        if (index in lines.indices) {
            lines.removeAt(index)
            _uiState.value = _uiState.value.copy(createReceiptForm = form.copy(lines = lines))
        }
    }

    fun addBarcode() {
        val form = _uiState.value.createNomenclatureForm
        _uiState.value = _uiState.value.copy(
            createNomenclatureForm = form.copy(barcodes = form.barcodes + CreateNomenclatureBarcodeRequest(barcode = ""))
        )
    }

    fun updateBarcode(index: Int, barcode: CreateNomenclatureBarcodeRequest) {
        val form = _uiState.value.createNomenclatureForm
        val barcodes = form.barcodes.toMutableList()
        if (index in barcodes.indices) {
            barcodes[index] = barcode
            _uiState.value = _uiState.value.copy(createNomenclatureForm = form.copy(barcodes = barcodes))
        }
    }

    fun removeBarcode(index: Int) {
        val form = _uiState.value.createNomenclatureForm
        val barcodes = form.barcodes.toMutableList()
        if (index in barcodes.indices) {
            barcodes.removeAt(index)
            _uiState.value = _uiState.value.copy(createNomenclatureForm = form.copy(barcodes = barcodes))
        }
    }

    fun updateCreateReservationForm(form: CreateReservationForm) {
        _uiState.value = _uiState.value.copy(createReservationForm = form)
    }

    fun onReservationWarehouseChanged(warehouseId: Long?) {
        _uiState.value = _uiState.value.copy(
            createReservationForm = _uiState.value.createReservationForm.copy(
                warehouseId = warehouseId,
                locationId = null
            ),
            reservationLocations = emptyList()
        )
        warehouseId?.let { loadReservationLocations(it) }
    }

    private fun loadReservationLocations(warehouseId: Long) {
        viewModelScope.launch {
            when (val result = repository.listLocations(warehouseId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(reservationLocations = result.data)
                is ApiResult.Error -> { }
            }
        }
    }

    fun updateStockFilters(filters: StockFilters) {
        _uiState.value = _uiState.value.copy(stockFilters = filters)
    }

    fun updateReservationFilters(filters: ReservationFilters) {
        _uiState.value = _uiState.value.copy(reservationFilters = filters)
    }

    fun updateLocationFilterWarehouse(warehouseId: Long?) {
        _uiState.value = _uiState.value.copy(locationFilterWarehouseId = warehouseId)
        warehouseId?.let { loadLocations(it) }
    }

    fun updateReleaseReason(value: String) {
        _uiState.value = _uiState.value.copy(releaseReason = value)
    }
}

data class WmsUiState(
    val warehouses: List<org.example.project.dto.WarehouseDto> = emptyList(),
    val locations: List<org.example.project.dto.StorageLocationDto> = emptyList(),
    val nomenclature: List<org.example.project.dto.NomenclatureDto> = emptyList(),
    val stock: List<org.example.project.dto.StockBalanceDto> = emptyList(),
    val reservations: List<org.example.project.dto.ReservationDto> = emptyList(),
    val receipts: List<ReceiptDto> = emptyList(),
    val currentReceipt: ReceiptDto? = null,
    val receiptFilters: ReceiptFilters = ReceiptFilters(),
    val currentReservation: org.example.project.dto.ReservationDto? = null,
    val selectedNomenclature: org.example.project.dto.NomenclatureDto? = null,
    val selectedTab: WmsTab = WmsTab.WAREHOUSES,
    val isLoading: Boolean = false,
    val operationError: ApiError? = null,
    val createWarehouseForm: CreateWarehouseForm = CreateWarehouseForm(),
    val createLocationForm: CreateLocationForm = CreateLocationForm(),
    val createNomenclatureForm: CreateNomenclatureForm = CreateNomenclatureForm(),
    val createReceiptForm: CreateReceiptForm = CreateReceiptForm(),
    val createReservationForm: CreateReservationForm = CreateReservationForm(),
    val locationFilterWarehouseId: Long? = null,
    val stockFilters: StockFilters = StockFilters(),
    val reservationFilters: ReservationFilters = ReservationFilters(),
    val activeDialog: WmsDialog? = null,
    val releaseReason: String = "",
    val receiptLocations: List<org.example.project.dto.StorageLocationDto> = emptyList(),
    val reservationLocations: List<org.example.project.dto.StorageLocationDto> = emptyList(),

    // Перемещения (§8.6)
    val transfers: List<org.example.project.dto.TransferDto> = emptyList(),
    val transfersLoading: Boolean = false,
    val transferStatusFilter: org.example.project.dto.TransferStatus? = null,
    val createTransferForm: CreateTransferForm = CreateTransferForm(),
    val transferLocationsFrom: List<org.example.project.dto.StorageLocationDto> = emptyList(),
    val transferLocationsTo: List<org.example.project.dto.StorageLocationDto> = emptyList(),
    val transferReceiveQuantity: String = "",

    // Выдача (§8.8)
    val issues: List<org.example.project.dto.IssueOrderDto> = emptyList(),
    val issuesLoading: Boolean = false,
    val issueStatusFilter: org.example.project.dto.IssueStatus? = null,
    val createIssueForm: CreateIssueForm = CreateIssueForm(),
    val issueLocations: List<org.example.project.dto.StorageLocationDto> = emptyList(),

    // Возвраты (§8.9)
    val returns: List<org.example.project.dto.ReturnOrderDto> = emptyList(),
    val returnsLoading: Boolean = false,
    val returnStatusFilter: org.example.project.dto.ReturnStatus? = null,

    val selectedTransfer: TransferDto? = null,
    val transferCancelReason: String = "",
    val selectedIssue: IssueOrderDto? = null,
    val issueRefusalReason: String = "",
    val issuePickForms: Map<Long, IssuePickForm> = emptyMap(),
    val selectedReturn: ReturnOrderDto? = null,
    val createReturnForm: CreateReturnForm = CreateReturnForm(),
    val returnLocations: List<org.example.project.dto.StorageLocationDto> = emptyList(),
    val returnInspectForms: Map<Long, ReturnInspectForm> = emptyMap(),

    // Списание (§8.10)
    val writeOffs: List<WriteOffDto> = emptyList(),
    val writeOffsLoading: Boolean = false,
    val writeOffStatusFilter: WriteOffStatus? = null,
    val createWriteOffForm: CreateWriteOffForm = CreateWriteOffForm(),
    val writeOffLocations: List<org.example.project.dto.StorageLocationDto> = emptyList(),
    val selectedWriteOff: WriteOffDto? = null,
    val writeOffCancelReason: String = "",
    val writeOffLimit: String = "",
    val writeOffLimitWarehouseId: Long? = null,

    // Инвентаризация (§8.11)
    val inventories: List<InventoryDto> = emptyList(),
    val inventoriesLoading: Boolean = false,
    val inventoryStatusFilter: InventoryStatus? = null,
    val createInventoryForm: CreateInventoryForm = CreateInventoryForm(),
    val inventoryLocations: List<org.example.project.dto.StorageLocationDto> = emptyList(),
    val selectedInventory: InventoryDto? = null,
    val inventoryCountInputs: Map<Long, String> = emptyMap(),
    val inventoryCancelReason: String = ""
)

enum class WmsTab(val title: String) {
    WAREHOUSES("Склады"),
    LOCATIONS("Ячейки"),
    NOMENCLATURE("Номенклатура"),
    STOCK("Остатки"),
    RECEIPTS("Приёмка"),
    RESERVATIONS("Резервы"),
    TRANSFERS("Перемещения"),
    ISSUES("Выдача"),
    RETURNS("Возвраты"),
    WRITE_OFFS("Списания"),
    INVENTORIES("Инвентаризация")
}

sealed class WmsDialog {
    data object CreateWarehouse : WmsDialog()
    data object CreateLocation : WmsDialog()
    data object CreateNomenclature : WmsDialog()
    data object CreateReceipt : WmsDialog()
    data object CreateReservation : WmsDialog()
    data object CreateTransfer : WmsDialog()
    data object CreateIssue : WmsDialog()
    data object CreateReturn : WmsDialog()
    data object CreateWriteOff : WmsDialog()
    data object CreateInventory : WmsDialog()
}

data class CreateWarehouseForm(
    val code: String = "",
    val name: String = "",
    val address: String = ""
)

data class CreateLocationForm(
    val warehouseId: Long? = null,
    val parentId: String = "",
    val code: String = "",
    val name: String = "",
    val kind: StorageLocationKind = StorageLocationKind.CELL,
    val purpose: StorageLocationPurpose = StorageLocationPurpose.STORAGE,
    val capacity: String = ""
)

data class CreateNomenclatureForm(
    val code: String = "",
    val name: String = "",
    val description: String = "",
    val type: org.example.project.dto.NomenclatureType = org.example.project.dto.NomenclatureType.MATERIAL,
    val unit: String = "",
    val lotTracked: Boolean = false,
    val serialTracked: Boolean = false,
    val barcodes: List<CreateNomenclatureBarcodeRequest> = listOf(CreateNomenclatureBarcodeRequest(barcode = ""))
)

data class CreateReceiptForm(
    val sourceType: org.example.project.dto.ReceiptSourceType = org.example.project.dto.ReceiptSourceType.OTHER,
    val sourceRef: String = "",
    val warehouseId: Long? = null,
    val expectedAt: String = "",
    val note: String = "",
    val lines: List<CreateReceiptLineForm> = emptyList()
)

data class CreateReceiptLineForm(
    val lineNo: Int = 1,
    val nomenclatureId: Long? = null,
    val expectedQuantity: String = "",
    val receivedQuantity: String = "",
    val locationId: Long? = null,
    val lotNumber: String = "",
    val note: String = ""
)

data class ReceiptFilters(
    val warehouseId: Long? = null,
    val status: ReceiptStatus? = null
)

data class CreateReservationForm(
    val reasonType: org.example.project.dto.ReservationReasonType = org.example.project.dto.ReservationReasonType.PROJECT,
    val projectId: String = "",
    val taskId: String = "",
    val reasonNote: String = "",
    val nomenclatureId: Long? = null,
    val warehouseId: Long? = null,
    val locationId: Long? = null,
    val lotId: String = "",
    val serialId: String = "",
    val quantity: String = "",
    val expiresAt: String = ""
)

data class StockFilters(
    val warehouseId: Long? = null,
    val nomenclatureId: Long? = null
)

data class ReservationFilters(
    val warehouseId: Long? = null,
    val nomenclatureId: Long? = null,
    val status: ReservationStatus? = null
)

data class CreateTransferForm(
    val fromWarehouseId: Long? = null,
    val fromLocationId: Long? = null,
    val toWarehouseId: Long? = null,
    val toLocationId: Long? = null,
    val nomenclatureId: Long? = null,
    val lotId: String = "",
    val serialId: String = "",
    val quantity: String = "",
    val note: String = ""
)

data class CreateIssueForm(
    val warehouseId: Long? = null,
    val reasonType: org.example.project.dto.ReservationReasonType = org.example.project.dto.ReservationReasonType.PROJECT,
    val projectId: String = "",
    val taskId: String = "",
    val reasonNote: String = "",
    val recipientId: String = "",
    val note: String = "",
    val lines: List<CreateIssueLineForm> = listOf(CreateIssueLineForm())
)

data class CreateIssueLineForm(
    val lineNo: Int = 1,
    val nomenclatureId: Long? = null,
    val quantity: String = "",
    val reservationId: String = "",
    val note: String = ""
)

data class IssuePickForm(
    val locationId: Long? = null,
    val lotId: String = "",
    val serialId: String = "",
    val quantity: String = ""
)

data class CreateReturnForm(
    val warehouseId: Long? = null,
    val issueOrderId: String = "",
    val quarantineLocationId: Long? = null,
    val reason: String = "",
    val lines: List<CreateReturnLineForm> = listOf(CreateReturnLineForm())
)

data class CreateReturnLineForm(
    val issueLineId: String = "",
    val quantity: String = "",
    val lotId: String = "",
    val serialId: String = ""
)

data class ReturnInspectForm(
    val decision: ReturnInspectionDecision = ReturnInspectionDecision.GOOD,
    val targetLocationId: Long? = null,
    val note: String = ""
)

data class CreateWriteOffForm(
    val warehouseId: Long? = null,
    val reasonType: WriteOffReasonType = WriteOffReasonType.DAMAGE,
    val reasonNote: String = "",
    val note: String = "",
    val lines: List<CreateWriteOffLineForm> = listOf(CreateWriteOffLineForm())
)

data class CreateWriteOffLineForm(
    val lineNo: Int = 1,
    val nomenclatureId: Long? = null,
    val locationId: Long? = null,
    val quantity: String = "",
    val lotId: String = "",
    val serialId: String = "",
    val note: String = ""
)

data class CreateInventoryForm(
    val warehouseId: Long? = null,
    val scopeType: InventoryScopeType = InventoryScopeType.WAREHOUSE,
    val scopeLocationId: Long? = null,
    val scopeNomenclatureId: Long? = null,
    val note: String = ""
)
