package org.example.project.ui.demand

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.BomRepository
import org.example.project.data.DemandRepository
import org.example.project.data.DocumentRepository
import org.example.project.data.ProjectRepository
import org.example.project.data.SupplierRepository
import org.example.project.data.WmsRepository
import org.example.project.data.pickAttachmentFile
import org.example.project.data.previewAttachmentFile
import org.example.project.data.safeAttachmentFileName
import org.example.project.data.saveAttachmentFile
import org.example.project.dto.ApiError
import org.example.project.dto.ApproveDemandRequest
import org.example.project.dto.CancelDemandRequest
import org.example.project.dto.CreateDemandFromBomRequest
import org.example.project.dto.CreateDemandRequest
import org.example.project.dto.CreateDocumentRequest
import org.example.project.dto.CreateSupplierOfferRequest
import org.example.project.dto.CreateSupplierRequest
import org.example.project.dto.DemandLineInput
import org.example.project.dto.DemandSourceType
import org.example.project.dto.DocumentConfidentiality
import org.example.project.dto.DocumentType
import org.example.project.dto.MarkDemandPaidRequest
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.SelectSupplierOfferRequest
import org.example.project.dto.SubmitDemandRequest
import org.example.project.dto.SupplierOfferCompliance
import org.example.project.dto.SupplierOfferDto
import org.example.project.dto.SupplierOfferLineInput
import org.example.project.dto.SupplierOfferStatus
import org.example.project.dto.SupplierVerificationRequest
import org.example.project.dto.SupplierVerificationStatus
import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission
import org.example.project.dto.isValidInn
import org.example.project.ui.home.currentIsoDate

class DemandViewModel(
    private val demandRepository: DemandRepository,
    private val wmsRepository: WmsRepository,
    private val projectRepository: ProjectRepository,
    private val bomRepository: BomRepository,
    private val supplierRepository: SupplierRepository,
    private val documentRepository: DocumentRepository,
    private val currentUser: UserDto
) : ViewModel() {

    private val _uiState = MutableStateFlow(DemandUiState())
    val uiState: StateFlow<DemandUiState> = _uiState.asStateFlow()

    init {
        loadDemands()
        loadProjects()
        loadModels()
        loadNomenclature()
        loadSuppliers()
        loadOffersIndex()
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(operationError = null)
    }

    fun dismissSuccess() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }

    fun showDialog(dialog: DemandDialog) {
        _uiState.value = _uiState.value.copy(activeDialog = dialog, operationError = null)
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(activeDialog = null, operationError = null, cancelReason = "")
    }

    fun navigateTo(page: DemandPage) {
        _uiState.value = _uiState.value.copy(currentPage = page, operationError = null, successMessage = null)
    }

    fun goBack() {
        when (_uiState.value.currentPage) {
            DemandPage.LIST -> { }
            DemandPage.DETAIL -> navigateTo(DemandPage.LIST)
        }
    }

    fun hasPermission(action: String): Boolean = currentUser.hasPermission("demands", action)
    fun canOffer(): Boolean = currentUser.hasPermission("suppliers", "offer")
    fun canOrder(): Boolean = currentUser.hasPermission("suppliers", "order")
    fun canManageSuppliers(): Boolean = currentUser.hasPermission("suppliers", "create")
    fun canVerifySuppliers(): Boolean = currentUser.hasPermission("suppliers", "verify")

    fun canSelectOfferFor(initiatorId: Long): Boolean =
        currentUser.hasPermission("suppliers", "select") &&
            (initiatorId == currentUser.id || currentUser.hasPermission("suppliers", "select_any"))

    fun updateStageFilter(filter: DemandStageFilter) {
        _uiState.value = _uiState.value.copy(stageFilter = filter)
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun loadDemands() {
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = demandRepository.listDemands()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    demands = result.data,
                    isLoading = false,
                    operationError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
        loadOffersIndex()
    }

    private fun loadOffersIndex() {
        if (!currentUser.hasPermission("suppliers", "read")) return
        viewModelScope.launch {
            when (val result = supplierRepository.listOffers()) {
                is ApiResult.Success -> {
                    val activeCounts = result.data
                        .filter {
                            it.status != SupplierOfferStatus.WITHDRAWN &&
                                it.status != SupplierOfferStatus.REJECTED &&
                                it.status != SupplierOfferStatus.EXPIRED
                        }
                        .groupBy { it.demandId }
                        .mapValues { it.value.size }
                    _uiState.value = _uiState.value.copy(
                        offersCountByDemand = activeCounts,
                        offersCountLoaded = true
                    )
                }
                is ApiResult.Error -> { }
            }
        }
    }

    private fun loadSuppliers() {
        if (!currentUser.hasPermission("suppliers", "read")) return
        viewModelScope.launch {
            when (val result = supplierRepository.listSuppliers(archived = false)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(suppliers = result.data)
                is ApiResult.Error -> { }
            }
        }
    }

    private fun loadProjects() {
        viewModelScope.launch {
            when (val result = projectRepository.list()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(projects = result.data)
                is ApiResult.Error -> { }
            }
        }
    }

    private fun loadModels() {
        viewModelScope.launch {
            when (val result = bomRepository.listModels()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(models = result.data)
                is ApiResult.Error -> { }
            }
        }
    }

    fun appendNomenclature(item: NomenclatureDto) {
        val current = _uiState.value.nomenclature
        if (current.none { it.id == item.id }) {
            _uiState.value = _uiState.value.copy(nomenclature = current + item)
        }
    }

    private fun loadNomenclature() {
        viewModelScope.launch {
            when (val result = wmsRepository.listNomenclature()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(nomenclature = result.data)
                is ApiResult.Error -> { }
            }
        }
    }

    fun selectDemand(id: Long) {
        _uiState.value = _uiState.value.copy(
            selectedDemand = if (_uiState.value.selectedDemand?.header?.id == id) _uiState.value.selectedDemand else null,
            operationError = null
        )
        viewModelScope.launch {
            when (val result = demandRepository.getDemand(id)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        selectedDemand = result.data,
                        currentPage = DemandPage.DETAIL
                    )
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    fun refreshSelectedDemand() {
        val id = _uiState.value.selectedDemand?.header?.id ?: return
        selectDemand(id)
    }

    fun canReserveStock(): Boolean = currentUser.hasPermission("suppliers", "offer") ||
        currentUser.hasPermission("suppliers", "order") || currentUser.hasPermission("wms", "manage")

    fun returnOfferForRevision(offer: org.example.project.dto.SupplierOfferDto, reason: String) {
        val demandId = _uiState.value.selectedDemand?.header?.id ?: return
        if (_uiState.value.isActionRunning) return
        _uiState.value = _uiState.value.copy(isActionRunning = true, operationError = null)
        viewModelScope.launch {
            when (val result = supplierRepository.returnOfferForRevision(
                offer.id, org.example.project.dto.ReturnOfferForRevisionRequest(reason.trim(), offer.version)
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(isActionRunning = false)
                    if (_uiState.value.selectedDemand?.header?.id == demandId) selectDemand(demandId)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(isActionRunning = false, operationError = result.error)
            }
        }
    }

    fun reserveDemandStock(lineId: Long) {
        val demandId = _uiState.value.selectedDemand?.header?.id ?: return
        if (!canReserveStock() || _uiState.value.isActionRunning) return
        _uiState.value = _uiState.value.copy(isActionRunning = true, operationError = null)
        viewModelScope.launch {
            val result = demandRepository.reserveDemandLineStock(demandId, lineId)
            if (_uiState.value.selectedDemand?.header?.id != demandId) {
                _uiState.value = _uiState.value.copy(isActionRunning = false)
                return@launch
            }
            _uiState.value = when (result) {
                is ApiResult.Success -> _uiState.value.copy(selectedDemand = result.data, isActionRunning = false)
                is ApiResult.Error -> _uiState.value.copy(operationError = result.error, isActionRunning = false)
            }
        }
    }

    fun refreshSelectedDemandIfOpen() {
        if (_uiState.value.currentPage == DemandPage.DETAIL) {
            refreshSelectedDemand()
        }
    }

    fun createDemand() {
        if (!hasPermission("create")) return
        val form = _uiState.value.createDemandForm
        when (form.sourceType) {
            DemandSourceType.PROJECT -> {
                if (form.useBomForProject) {
                    createDemandFromBom(form)
                } else {
                    if (form.projectId == null) {
                        _uiState.value = _uiState.value.copy(
                            operationError = ApiError("VALIDATION_ERROR", "Выберите проект")
                        )
                        return
                    }
                    createManualOrStockpile(form, projectId = form.projectId)
                }
            }
            DemandSourceType.STOCKPILE -> createManualOrStockpile(form, projectId = null)
            DemandSourceType.MANUAL -> createManualOrStockpile(form, projectId = null)
        }
    }

    private fun createManualOrStockpile(form: CreateDemandForm, projectId: Long?) {
        if (form.sourceType == DemandSourceType.STOCKPILE && form.stockpileNote.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Обоснование задела обязательно")
            )
            return
        }
        val validLines = form.lines.mapIndexed { index, line ->
            val nomId = line.nomenclatureId
            val qty = line.quantity
            if (nomId == null || qty.isBlank() || line.unit.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    operationError = ApiError("VALIDATION_ERROR", "В строке ${index + 1} выберите номенклатуру и укажите количество")
                )
                return
            }
            if (qty.toDoubleSafe() <= 0.0) {
                _uiState.value = _uiState.value.copy(
                    operationError = ApiError("VALIDATION_ERROR", "Количество в строке ${index + 1} должно быть больше нуля")
                )
                return
            }
            DemandLineInput(
                lineNo = index + 1,
                nomenclatureId = nomId,
                quantity = qty.trim().replace(',', '.'),
                unit = line.unit.trim()
            )
        }
        if (validLines.isEmpty()) {
            _uiState.value = _uiState.value.copy(operationError = ApiError("VALIDATION_ERROR", "Добавьте хотя бы одну позицию"))
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = demandRepository.createDemand(
                CreateDemandRequest(
                    sourceType = form.sourceType,
                    projectId = projectId,
                    stockpileNote = form.stockpileNote.takeIf { it.isNotBlank() }?.trim(),
                    requiredDate = form.requiredDate.takeIf { it.isNotBlank() }?.trim(),
                    priority = form.priority,
                    idempotencyKey = newKey("demand-create"),
                    lines = validLines
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createDemandForm = CreateDemandForm(),
                        activeDialog = null,
                        isLoading = false,
                        successMessage = "Заявка ${result.data.header.number} создана и передана снабжению"
                    )
                    loadDemands()
                    selectDemand(result.data.header.id)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    private fun createDemandFromBom(form: CreateDemandForm) {
        val projectId = form.projectId
        val revisionId = form.revisionId
        if (projectId == null || revisionId == null || form.releaseQuantity.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Проект, ревизия BOM и количество выпуска обязательны")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = demandRepository.createDemandFromBom(
                CreateDemandFromBomRequest(
                    projectId = projectId,
                    revisionId = revisionId,
                    releaseQuantity = form.releaseQuantity.trim().replace(',', '.'),
                    requiredDate = form.requiredDate.takeIf { it.isNotBlank() }?.trim(),
                    priority = form.priority,
                    idempotencyKey = newKey("demand-bom")
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createDemandForm = CreateDemandForm(),
                        activeDialog = null,
                        isLoading = false,
                        successMessage = "Заявка ${result.data.header.number} создана из BOM и передана снабжению"
                    )
                    loadDemands()
                    selectDemand(result.data.header.id)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun submitDemand() {
        if (!hasPermission("update")) return
        val demand = _uiState.value.selectedDemand ?: return
        if (!demand.header.status.canSubmit()) return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = demandRepository.submitDemand(demand.header.id, SubmitDemandRequest(demand.header.version))) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(selectedDemand = result.data, isLoading = false)
                    loadDemands()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun approveDemand() {
        if (!hasPermission("approve")) return
        val demand = _uiState.value.selectedDemand ?: return
        if (!demand.header.status.canApprove()) return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = demandRepository.approveDemand(demand.header.id, ApproveDemandRequest(demand.header.version))) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(selectedDemand = result.data, isLoading = false)
                    loadDemands()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun cancelDemand() {
        if (!hasPermission("update")) return
        val demand = _uiState.value.selectedDemand ?: return
        val reason = _uiState.value.cancelReason
        if (!demand.header.status.canCancel()) return
        if (reason.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Укажите причину отмены")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = demandRepository.cancelDemand(
                demand.header.id,
                CancelDemandRequest(demand.header.version, reason.trim())
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        selectedDemand = result.data,
                        activeDialog = null,
                        cancelReason = "",
                        isLoading = false,
                        successMessage = "Заявка ${result.data.header.number} отменена"
                    )
                    loadDemands()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    // --- Предложения со счётом (прямо внутри пульта потребности) ---

    fun openCreateOfferDialog() {
        if (!canOffer()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("FORBIDDEN", "Нет права вносить предложения поставщиков")
            )
            return
        }
        val demand = _uiState.value.selectedDemand ?: return
        if (_uiState.value.suppliers.isEmpty()) loadSuppliers()
        val nomenclature = _uiState.value.nomenclature
        val lines = demand.lines.mapNotNull { line ->
            val deficitRaw = line.deficitQuantity ?: run {
                val rem = (line.quantity.toDoubleSafe() - line.coveredQuantity.toDoubleSafe()).coerceAtLeast(0.0)
                if (rem <= 0.0) "0" else rem.toString()
            }
            val deficitPlain = deficitRaw.plainDecimalString()
            if (deficitPlain.toDoubleSafe() <= 0.0) return@mapNotNull null
            val item = nomenclature.firstOrNull { it.id == line.nomenclatureId }
            val nomCode = line.nomenclatureCode?.takeIf { it.isNotBlank() }
            val nomName = line.nomenclatureName?.takeIf { it.isNotBlank() }
            val label = when {
                nomCode != null && nomName != null -> "$nomCode · $nomName"
                nomName != null -> nomName
                nomCode != null -> nomCode
                item != null -> "${item.code} · ${item.name}"
                else -> "Позиция ${line.lineNo}"
            }
            DemandOfferLineForm(
                demandLineId = line.id,
                nomenclatureId = line.nomenclatureId,
                label = label,
                unit = line.unit,
                deficit = deficitPlain,
                quantity = deficitPlain
            )
        }
        val defaultValidUntil = isoDatePlusDays(currentIsoDate(), 30)
        _uiState.value = _uiState.value.copy(
            activeDialog = DemandDialog.CreateOffer,
            operationError = null,
            createOfferForm = DemandCreateOfferForm(
                demandId = demand.header.id,
                demandNumber = demand.header.number,
                validUntil = defaultValidUntil,
                lines = lines.ifEmpty {
                    demand.lines.map { line ->
                        val item = nomenclature.firstOrNull { it.id == line.nomenclatureId }
                        val nomCode = line.nomenclatureCode?.takeIf { it.isNotBlank() }
                        val nomName = line.nomenclatureName?.takeIf { it.isNotBlank() }
                        val label = when {
                            nomCode != null && nomName != null -> "$nomCode · $nomName"
                            nomName != null -> nomName
                            nomCode != null -> nomCode
                            item != null -> "${item.code} · ${item.name}"
                            else -> "Позиция ${line.lineNo}"
                        }
                        val qtyPlain = line.quantity.plainDecimalString()
                        DemandOfferLineForm(
                            demandLineId = line.id,
                            nomenclatureId = line.nomenclatureId,
                            label = label,
                            unit = line.unit,
                            deficit = qtyPlain,
                            quantity = qtyPlain
                        )
                    }
                }
            )
        )
    }

    fun updateCreateOfferForm(form: DemandCreateOfferForm) {
        _uiState.value = _uiState.value.copy(createOfferForm = form)
    }

    fun updateCreateOfferLine(index: Int, transform: (DemandOfferLineForm) -> DemandOfferLineForm) {
        val form = _uiState.value.createOfferForm
        val updated = form.lines.mapIndexed { idx, line -> if (idx == index) transform(line) else line }
        _uiState.value = _uiState.value.copy(createOfferForm = form.copy(lines = updated))
    }

    fun pickOfferInvoiceFile() {
        pickAttachmentFile(
            onPicked = { file ->
                val form = _uiState.value.createOfferForm
                _uiState.value = _uiState.value.copy(
                    operationError = null,
                    createOfferForm = form.copy(
                        pendingInvoiceFile = file,
                        invoiceDocumentId = null,
                        invoiceFileName = null
                    )
                )
            },
            onError = { message ->
                _uiState.value = _uiState.value.copy(
                    operationError = ApiError("VALIDATION_ERROR", message)
                )
            }
        )
    }

    fun quickCreateSupplier(
        name: String,
        inn: String,
        verifyNow: Boolean,
        onDone: () -> Unit
    ) {
        if (!canManageSuppliers()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("FORBIDDEN", "Нет права создавать поставщиков")
            )
            return
        }
        if (name.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Укажите наименование поставщика")
            )
            return
        }
        val cleanInn = inn.trim()
        if (cleanInn.isNotEmpty() && (!cleanInn.matches(Regex("^([0-9]{10}|[0-9]{12})$")) || !isValidInn(cleanInn))) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "ИНН должен содержать 10 цифр (организация) или 12 (ИП) с верной контрольной суммой")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isActionRunning = true, operationError = null)
        viewModelScope.launch {
            val request = CreateSupplierRequest(
                name = name.trim(),
                inn = cleanInn.ifEmpty { null },
                responsibleId = currentUser.id,
                idempotencyKey = newKey("demand-quick-supplier")
            )
            when (val result = supplierRepository.createSupplier(request)) {
                is ApiResult.Success -> {
                    var created = result.data.supplier
                    if (verifyNow && canVerifySuppliers()) {
                        when (val v = supplierRepository.verifySupplier(
                            created.id,
                            SupplierVerificationRequest(
                                status = SupplierVerificationStatus.VERIFIED,
                                note = "Быстрая проверка при внесении предложения",
                                version = created.version,
                                idempotencyKey = newKey("demand-quick-verify")
                            )
                        )) {
                            is ApiResult.Success -> created = v.data
                            is ApiResult.Error -> { }
                        }
                    }
                    _uiState.value = _uiState.value.copy(
                        suppliers = listOf(created) + _uiState.value.suppliers.filterNot { it.id == created.id },
                        createOfferForm = _uiState.value.createOfferForm.copy(supplierId = created.id),
                        isActionRunning = false
                    )
                    onDone()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isActionRunning = false,
                    operationError = result.error
                )
            }
        }
    }

    fun submitCreateOffer() {
        if (!canOffer()) return
        val form = _uiState.value.createOfferForm
        val demandId = form.demandId ?: return
        val supplierId = form.supplierId ?: run {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Выберите поставщика")
            )
            return
        }
        if (!form.hasInvoiceFile) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Сначала прикрепите файл счёта от поставщика")
            )
            return
        }
        val validUntil = form.validUntil.trim()
        if (validUntil.isNotEmpty() && !Regex("""\d{4}-\d{2}-\d{2}""").matches(validUntil)) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Укажите корректный срок действия предложения")
            )
            return
        }
        val invoiceDate = form.invoiceDate.trim()
        if (invoiceDate.isNotEmpty() && !Regex("""\d{4}-\d{2}-\d{2}""").matches(invoiceDate)) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Укажите корректную дату счёта")
            )
            return
        }
        val includedLines = form.lines.filter { it.included }
        if (includedLines.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Включите хотя бы одну позицию в предложение")
            )
            return
        }
        val lineInputs = mutableListOf<SupplierOfferLineInput>()
        includedLines.forEachIndexed { idx, line ->
            val qtyNum = line.quantity.toDoubleSafe()
            if (line.quantity.isBlank() || qtyNum <= 0.0) {
                _uiState.value = _uiState.value.copy(
                    operationError = ApiError("VALIDATION_ERROR", "Позиция ${idx + 1}: укажите положительное количество")
                )
                return
            }
            val deficitNum = line.deficit.toDoubleSafe()
            if (deficitNum > 0.0 && qtyNum > deficitNum + 1e-6) {
                _uiState.value = _uiState.value.copy(
                    operationError = ApiError("VALIDATION_ERROR", "Позиция ${idx + 1}: количество превышает остаток по заявке (${line.deficit})")
                )
                return
            }
            val priceNum = line.unitPrice.trim().replace(" ", "").replace(',', '.').toDoubleOrNull()
            if (priceNum == null || priceNum < 0.0) {
                _uiState.value = _uiState.value.copy(
                    operationError = ApiError("VALIDATION_ERROR", "Позиция ${idx + 1}: укажите корректную цену")
                )
                return
            }
            val vatStr = line.vatRate.trim().replace(',', '.').ifEmpty { "20" }
            val vatNum = vatStr.toDoubleOrNull()
            if (vatNum == null || vatNum < 0.0 || vatNum > 100.0) {
                _uiState.value = _uiState.value.copy(
                    operationError = ApiError("VALIDATION_ERROR", "Позиция ${idx + 1}: НДС должен быть от 0 до 100%")
                )
                return
            }
            lineInputs += SupplierOfferLineInput(
                demandLineId = line.demandLineId,
                quantity = line.quantity.trim().replace(" ", "").replace(',', '.'),
                unitPrice = line.unitPrice.trim().replace(" ", "").replace(',', '.'),
                vatRate = vatStr
            )
        }
        val deliveryDays = form.deliveryDays.trim().takeIf { it.isNotEmpty() }?.let { raw ->
            raw.toIntOrNull()?.takeIf { it >= 0 } ?: run {
                _uiState.value = _uiState.value.copy(
                    operationError = ApiError("VALIDATION_ERROR", "Срок поставки должен быть целым числом дней")
                )
                return
            }
        }

        _uiState.value = _uiState.value.copy(isActionRunning = true, operationError = null)
        viewModelScope.launch {
            val invoiceDocId = form.invoiceDocumentId ?: run {
                val file = form.pendingInvoiceFile!!
                val createdDoc = when (val docRes = documentRepository.create(
                    CreateDocumentRequest(
                        name = "Счёт к предложению ${form.demandNumber}",
                        docType = DocumentType.INVOICE,
                        confidentiality = DocumentConfidentiality.INTERNAL
                    )
                )) {
                    is ApiResult.Success -> docRes.data
                    is ApiResult.Error -> {
                        _uiState.value = _uiState.value.copy(isActionRunning = false, operationError = docRes.error)
                        return@launch
                    }
                }
                when (val uploadRes = documentRepository.uploadVersion(createdDoc.id, file, null)) {
                    is ApiResult.Success -> {
                        _uiState.value = _uiState.value.copy(
                            createOfferForm = _uiState.value.createOfferForm.copy(
                                invoiceDocumentId = createdDoc.id,
                                invoiceFileName = file.fileName,
                                pendingInvoiceFile = null
                            )
                        )
                        createdDoc.id
                    }
                    is ApiResult.Error -> {
                        _uiState.value = _uiState.value.copy(isActionRunning = false, operationError = uploadRes.error)
                        return@launch
                    }
                }
            }

            val request = CreateSupplierOfferRequest(
                demandId = demandId,
                supplierId = supplierId,
                currency = "RUB",
                compliance = SupplierOfferCompliance.FULL,
                documentsComplete = true,
                deliveryDays = deliveryDays,
                paymentTerms = form.paymentTerms.trim().ifEmpty { null },
                validUntil = validUntil,
                note = form.note.trim().ifEmpty { null },
                lines = lineInputs,
                idempotencyKey = newKey("demand-offer-create"),
                invoiceDocumentId = invoiceDocId,
                invoiceNumber = form.invoiceNumber.trim().ifEmpty { null },
                invoiceDate = invoiceDate.ifEmpty { null }
            )
            when (val result = supplierRepository.createOffer(request)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isActionRunning = false,
                        activeDialog = null,
                        createOfferForm = DemandCreateOfferForm(),
                        successMessage = "Предложение ${result.data.number} со счётом добавлено"
                    )
                    loadDemands()
                    selectDemand(demandId)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isActionRunning = false,
                    operationError = result.error
                )
            }
        }
    }

    fun selectOffer(offer: SupplierOfferDto) {
        val demand = _uiState.value.selectedDemand ?: return
        if (!canSelectOfferFor(demand.header.initiatorId)) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("FORBIDDEN", "Выбрать предложение может инициатор заявки или руководитель с правом выбора")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isActionRunning = true, operationError = null)
        viewModelScope.launch {
            when (val result = supplierRepository.selectOffer(
                offer.id,
                SelectSupplierOfferRequest(
                    version = offer.version,
                    idempotencyKey = newKey("demand-offer-select")
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isActionRunning = false,
                        successMessage = "Выбран вариант ${result.data.number} (${offer.supplierName ?: "поставщик"})"
                    )
                    loadDemands()
                    selectDemand(demand.header.id)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isActionRunning = false,
                    operationError = result.error
                )
            }
        }
    }

    fun clearOfferSelection(offer: SupplierOfferDto) {
        val demand = _uiState.value.selectedDemand ?: return
        if (!canSelectOfferFor(demand.header.initiatorId)) return
        _uiState.value = _uiState.value.copy(isActionRunning = true, operationError = null)
        viewModelScope.launch {
            when (val result = supplierRepository.clearSelection(offer.id, offer.version)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isActionRunning = false,
                        successMessage = "Выбор предложения ${result.data.number} снят"
                    )
                    loadDemands()
                    selectDemand(demand.header.id)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isActionRunning = false,
                    operationError = result.error
                )
            }
        }
    }

    fun openOfferInvoice(offer: SupplierOfferDto, preview: Boolean = true) {
        _uiState.value = _uiState.value.copy(isActionRunning = true, operationError = null)
        viewModelScope.launch {
            when (val result = supplierRepository.downloadOfferInvoiceFile(offer.id)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(isActionRunning = false)
                    val fileName = safeAttachmentFileName(
                        offer.invoiceFileName ?: "invoice-${offer.invoiceNumber ?: offer.number}.pdf",
                        offer.id
                    )
                    if (preview) {
                        previewAttachmentFile(fileName, result.data.mimeType, result.data.bytes)
                    } else {
                        saveAttachmentFile(fileName, result.data.mimeType, result.data.bytes)
                    }
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isActionRunning = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updatePaymentExpectedAt(value: String) {
        _uiState.value = _uiState.value.copy(paymentExpectedAt = value)
    }

    fun updatePaymentNote(value: String) {
        _uiState.value = _uiState.value.copy(paymentNote = value)
    }

    fun placeDemandOrder() {
        if (!canOrder() || _uiState.value.isActionRunning) return
        val demand = _uiState.value.selectedDemand ?: return
        val expected = _uiState.value.paymentExpectedAt.trim()
        if (expected.isNotEmpty() && !Regex("""\d{4}-\d{2}-\d{2}""").matches(expected)) {
            _uiState.value = _uiState.value.copy(operationError = ApiError("VALIDATION_ERROR", "Укажите корректную плановую дату прихода"))
            return
        }
        _uiState.value = _uiState.value.copy(isActionRunning = true, operationError = null)
        viewModelScope.launch {
            val result = demandRepository.placeDemandOrder(demand.header.id,
                org.example.project.dto.PlaceDemandOrderRequest(expectedAt = expected.ifEmpty { null }, markPaid = false))
            if (_uiState.value.selectedDemand?.header?.id != demand.header.id) {
                _uiState.value = _uiState.value.copy(isActionRunning = false)
                return@launch
            }
            when (result) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    selectedDemand = result.data, isActionRunning = false,
                    successMessage = "Заказ оформлен и передан в очередь склада. Оплата не отмечена."
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(isActionRunning = false, operationError = result.error)
            }
        }
    }

    fun markDemandPaid() {
        if (!canOrder()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("FORBIDDEN", "Нет права отмечать оплату заказов")
            )
            return
        }
        val state = _uiState.value
        val demand = state.selectedDemand ?: return
        val expected = state.paymentExpectedAt.trim()
        if (expected.isNotEmpty() && !Regex("""\d{4}-\d{2}-\d{2}""").matches(expected)) {
            _uiState.value = state.copy(
                operationError = ApiError("VALIDATION_ERROR", "Плановая дата прихода должна быть в формате дд.мм.гггг")
            )
            return
        }
        _uiState.value = state.copy(isActionRunning = true, operationError = null)
        viewModelScope.launch {
            when (val result = demandRepository.markDemandPaid(
                demand.header.id,
                MarkDemandPaidRequest(
                    paymentNote = state.paymentNote.trim().ifEmpty { null },
                    expectedAt = expected.ifEmpty { null },
                    idempotencyKey = newKey("demand-paid")
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isActionRunning = false,
                        selectedDemand = result.data,
                        paymentExpectedAt = "",
                        paymentNote = "",
                        successMessage = "Оплата отмечена · заказ отправлен поставщику и ожидается на складе"
                    )
                    loadDemands()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isActionRunning = false,
                    operationError = result.error
                )
            }
        }
    }

    // --- Форма создания заявки ---

    fun updateCreateDemandForm(form: CreateDemandForm) {
        _uiState.value = _uiState.value.copy(createDemandForm = form)
    }

    fun onSourceTypeChanged(sourceType: DemandSourceType) {
        _uiState.value = _uiState.value.copy(
            createDemandForm = CreateDemandForm(sourceType = sourceType),
            revisions = emptyList(),
            operationError = null
        )
    }

    fun toggleProjectBomMode(useBom: Boolean) {
        _uiState.value = _uiState.value.copy(
            createDemandForm = _uiState.value.createDemandForm.copy(useBomForProject = useBom),
            operationError = null
        )
    }

    fun onProjectSelected(projectId: Long?) {
        _uiState.value = _uiState.value.copy(
            createDemandForm = _uiState.value.createDemandForm.copy(projectId = projectId)
        )
    }

    fun onModelSelected(modelId: Long?) {
        _uiState.value = _uiState.value.copy(
            createDemandForm = _uiState.value.createDemandForm.copy(modelId = modelId, revisionId = null),
            revisions = emptyList()
        )
        modelId?.let { loadRevisions(it) }
    }

    fun onRevisionSelected(revisionId: Long?) {
        _uiState.value = _uiState.value.copy(
            createDemandForm = _uiState.value.createDemandForm.copy(revisionId = revisionId)
        )
    }

    private fun loadRevisions(modelId: Long) {
        viewModelScope.launch {
            when (val result = bomRepository.listRevisions(modelId)) {
                is ApiResult.Success -> if (_uiState.value.createDemandForm.modelId == modelId) {
                    _uiState.value = _uiState.value.copy(revisions = result.data)
                }
                is ApiResult.Error -> if (_uiState.value.createDemandForm.modelId == modelId) {
                    _uiState.value = _uiState.value.copy(operationError = result.error)
                }
            }
        }
    }

    fun addDemandLine() {
        val form = _uiState.value.createDemandForm
        val nextLineNo = (form.lines.maxOfOrNull { it.lineNo } ?: 0) + 1
        _uiState.value = _uiState.value.copy(
            createDemandForm = form.copy(lines = form.lines + CreateDemandLineForm(lineNo = nextLineNo))
        )
    }

    fun updateDemandLine(index: Int, line: CreateDemandLineForm) {
        val form = _uiState.value.createDemandForm
        val lines = form.lines.toMutableList()
        if (index in lines.indices) {
            lines[index] = line
            _uiState.value = _uiState.value.copy(createDemandForm = form.copy(lines = lines))
        }
    }

    fun removeDemandLine(index: Int) {
        val form = _uiState.value.createDemandForm
        val lines = form.lines.toMutableList()
        if (index in lines.indices) {
            lines.removeAt(index)
            _uiState.value = _uiState.value.copy(createDemandForm = form.copy(lines = lines))
        }
    }

    fun updateCancelReason(reason: String) {
        _uiState.value = _uiState.value.copy(cancelReason = reason)
    }

    private fun newKey(prefix: String): String =
        "$prefix-${currentUser.id}-${kotlin.random.Random.nextLong().toString(16)}"
}
