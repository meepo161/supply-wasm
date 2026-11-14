package org.example.project.ui.wms

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
import org.example.project.dto.ReceiptLineInput
import org.example.project.dto.ReleaseReservationRequest
import org.example.project.dto.ReservationDto
import org.example.project.dto.ReservationStatus
import org.example.project.dto.StorageLocationKind
import org.example.project.dto.StorageLocationPurpose

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
            WmsTab.RECEIPTS -> { }
            WmsTab.RESERVATIONS -> loadReservations()
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
        val validLines = form.lines.mapIndexedNotNull { index, line ->
            val nomId = line.nomenclatureId
            val locId = line.locationId
            val qty = line.expectedQuantity
            if (nomId == null || locId == null || qty.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    operationError = ApiError("VALIDATION_ERROR", "В строке ${index + 1} не заполнены номенклатура, ячейка или количество")
                )
                return
            }
            ReceiptLineInput(
                lineNo = line.lineNo,
                nomenclatureId = nomId,
                expectedQuantity = qty.trim(),
                receivedQuantity = null,
                lotNumber = line.lotNumber.takeIf { it.isNotBlank() },
                locationId = locId,
                note = line.note.takeIf { it.isNotBlank() }
            )
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.createReceipt(
                CreateReceiptRequest(
                    sourceType = form.sourceType,
                    sourceRef = form.sourceRef.takeIf { it.isNotBlank() },
                    warehouseId = warehouseId,
                    expectedAt = form.expectedAt.takeIf { it.isNotBlank() },
                    note = form.note.takeIf { it.isNotBlank() },
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
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun loadReceiptById() {
        val id = _uiState.value.loadReceiptId.toLongOrNull()
        if (id == null) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Введите числовой ID приёмки")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = repository.getReceipt(id)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    currentReceipt = result.data,
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
        _uiState.value = _uiState.value.copy(currentReceipt = null, loadReceiptId = "")
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

    fun updateLoadReceiptId(value: String) {
        _uiState.value = _uiState.value.copy(loadReceiptId = value)
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
    val currentReceipt: org.example.project.dto.ReceiptDto? = null,
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
    val loadReceiptId: String = "",
    val releaseReason: String = "",
    val receiptLocations: List<org.example.project.dto.StorageLocationDto> = emptyList(),
    val reservationLocations: List<org.example.project.dto.StorageLocationDto> = emptyList()
)

enum class WmsTab(val title: String) {
    WAREHOUSES("Склады"),
    LOCATIONS("Ячейки"),
    NOMENCLATURE("Номенклатура"),
    STOCK("Остатки"),
    RECEIPTS("Приёмка"),
    RESERVATIONS("Резервы")
}

sealed class WmsDialog {
    data object CreateWarehouse : WmsDialog()
    data object CreateLocation : WmsDialog()
    data object CreateNomenclature : WmsDialog()
    data object CreateReceipt : WmsDialog()
    data object CreateReservation : WmsDialog()
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
    val locationId: Long? = null,
    val lotNumber: String = "",
    val note: String = ""
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
