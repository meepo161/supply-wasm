package org.example.project.ui.supplier

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.AttachmentFileData
import org.example.project.data.DocumentRepository
import org.example.project.data.SupplierInvoiceRepository
import org.example.project.data.WmsRepository
import org.example.project.data.pickAttachmentFile
import org.example.project.data.safeAttachmentFileName
import org.example.project.data.saveAttachmentFile
import org.example.project.dto.ApiError
import org.example.project.dto.CancelSupplierInvoiceRequest
import org.example.project.dto.ConfirmSupplierInvoiceCheckRequest
import org.example.project.dto.CreateDocumentRequest
import org.example.project.dto.CreateReceiptFromInvoiceRequest
import org.example.project.dto.DocumentType
import org.example.project.dto.InvoiceIssueSeverity
import org.example.project.dto.InvoiceTextParser
import org.example.project.dto.LeadTimeKind
import org.example.project.dto.ParsedSupplierInvoice
import org.example.project.dto.ParsedSupplierInvoiceLine
import org.example.project.dto.PurchaseOrderDto
import org.example.project.dto.SupplierInvoiceActionRequest
import org.example.project.dto.SupplierInvoiceCheckDto
import org.example.project.dto.SupplierInvoiceDto
import org.example.project.dto.SupplierInvoiceHistoryDto
import org.example.project.dto.SupplierInvoiceInput
import org.example.project.dto.SupplierInvoiceLineInput
import org.example.project.dto.SupplierInvoicePreviewDto
import org.example.project.dto.SupplierInvoiceStatus
import org.example.project.dto.UpdateSupplierInvoiceRequest
import org.example.project.dto.UserDto
import org.example.project.dto.WarehouseDto
import org.example.project.dto.hasPermission
import org.example.project.ui.home.currentIsoDate
import kotlin.math.roundToLong

/** Состояние раздела «Счета» (D-61): счёт ведёт снабжение, склад только принимает. */
data class SupplierInvoiceUiState(
    val invoices: List<SupplierInvoiceDto> = emptyList(),
    val selected: SupplierInvoiceDto? = null,
    val check: SupplierInvoiceCheckDto? = null,
    val history: List<SupplierInvoiceHistoryDto> = emptyList(),
    val warehouses: List<WarehouseDto> = emptyList(),
    val statusFilter: SupplierInvoiceStatus? = null,
    val isLoading: Boolean = false,
    val isBusy: Boolean = false,
    val error: ApiError? = null,
    val message: String? = null,
    val dialog: SupplierInvoiceDialog? = null,
    val form: InvoiceForm? = null,
    val acceptReason: String = "",
    val cancelReason: String = "",
    val receiptWarehouseId: Long? = null
)

sealed class SupplierInvoiceDialog {
    data object Edit : SupplierInvoiceDialog()
    data object Confirm : SupplierInvoiceDialog()
    data object Cancel : SupplierInvoiceDialog()
    data object Receipt : SupplierInvoiceDialog()
}

/** Форма счёта: реквизиты переносятся из загруженного файла. */
data class InvoiceForm(
    val invoiceId: Long? = null,
    val version: Long = 0,
    val supplierId: Long,
    val supplierName: String,
    val purchaseOrderId: Long?,
    val purchaseOrderNumber: String?,
    val currency: String = "RUB",
    /** Выбранный, но ещё не загруженный файл счёта. */
    val pendingFile: AttachmentFileData? = null,
    /** Документ DMS с уже загруженным файлом (после первой попытки сохранения или при правке). */
    val documentId: Long? = null,
    val documentFileName: String? = null,
    val number: String = "",
    val invoiceDate: String = currentIsoDate(),
    val validUntil: String = "",
    val supplierInn: String = "",
    val supplierKpp: String = "",
    val pricesIncludeVat: Boolean = false,
    val paymentTerms: String = "",
    val prepaymentPct: String = "",
    val leadTimeDays: String = "",
    val leadTimeWorking: Boolean = true,
    val deliveryTerms: String = "",
    val paymentPurpose: String = "",
    val supplierManager: String = "",
    val note: String = "",
    val netTotal: String = "",
    val vatTotal: String = "",
    val grossTotal: String = "",
    val lines: List<InvoiceLineForm> = emptyList()
) {
    val hasFile: Boolean get() = pendingFile != null || documentId != null
    val fileLabel: String? get() = pendingFile?.fileName ?: documentFileName
}

data class InvoiceLineForm(
    val lineNo: Int,
    val supplierItemCode: String = "",
    val supplierItemName: String,
    val nomenclatureId: Long?,
    val purchaseOrderLineId: Long?,
    val nomenclatureLabel: String,
    val quantity: String,
    val unit: String,
    val unitPrice: String,
    /** Пусто — «Без НДС». */
    val vatRate: String = "20",
    val vatAmount: String = "",
    val lineTotal: String = ""
)

class SupplierInvoiceViewModel(
    private val repository: SupplierInvoiceRepository,
    private val documentRepository: DocumentRepository,
    private val wmsRepository: WmsRepository,
    private val currentUser: UserDto
) : ViewModel() {

    private val _uiState = MutableStateFlow(SupplierInvoiceUiState())
    val uiState: StateFlow<SupplierInvoiceUiState> = _uiState.asStateFlow()

    init {
        if (canRead()) load()
    }

    fun canRead(): Boolean = currentUser.hasPermission("invoices", "read") || canManage()
    fun canManage(): Boolean = currentUser.hasPermission("invoices", "manage")

    // --- Загрузка ---

    fun load() {
        update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.list(status = _uiState.value.statusFilter)) {
                is ApiResult.Success -> {
                    update { it.copy(invoices = r.data, isLoading = false) }
                    if (_uiState.value.selected == null && r.data.isNotEmpty()) {
                        select(r.data.first().id)
                    }
                }
                is ApiResult.Error -> update { it.copy(isLoading = false, error = r.error) }
            }
        }
    }

    fun updateStatusFilter(status: SupplierInvoiceStatus?) {
        update { it.copy(statusFilter = status) }
        load()
    }

    fun invoicesForOrder(orderId: Long): List<SupplierInvoiceDto> =
        _uiState.value.invoices.filter { it.purchaseOrderId == orderId }

    fun select(id: Long) {
        update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch { refreshSelected(id) }
    }

    private suspend fun refreshSelected(id: Long) {
        when (val r = repository.get(id)) {
            is ApiResult.Success -> {
                val check = (repository.check(id) as? ApiResult.Success)?.data
                val history = (repository.history(id) as? ApiResult.Success)?.data ?: emptyList()
                update { s ->
                    s.copy(
                        selected = r.data,
                        check = check,
                        history = history,
                        isBusy = false,
                        invoices = s.invoices.map { if (it.id == id) r.data.copy(lines = emptyList()) else it }
                    )
                }
            }
            is ApiResult.Error -> update { it.copy(isBusy = false, error = r.error) }
        }
    }

    // --- Регистрация и правка ---

    /** «Загрузить счёт» из карточки заказа: шапка и строки берутся из заказа. */
    fun openCreateFromOrder(order: PurchaseOrderDto) {
        val lines = order.lines.mapIndexed { index, line ->
            recalc(
                InvoiceLineForm(
                    lineNo = index + 1,
                    supplierItemName = line.nomenclatureName ?: line.nomenclatureCode ?: "Позиция ${line.lineNo}",
                    nomenclatureId = line.nomenclatureId,
                    purchaseOrderLineId = line.id,
                    nomenclatureLabel = listOfNotNull(line.nomenclatureCode, line.nomenclatureName).joinToString(" · "),
                    // Остаток к поставке «0» у полностью поставленного заказа — тогда счёт на заказанное.
                    quantity = line.remainingQuantity.takeIf { (it.toDoubleOrNull() ?: 0.0) > 0.0 }?.trimQty()
                        ?: line.quantity.trimQty(),
                    unit = line.unit,
                    unitPrice = line.unitPrice.trimMoney(),
                    vatRate = line.vatRate.trimQty()
                ),
                pricesIncludeVat = false
            )
        }
        val form = withTotals(
            InvoiceForm(
                supplierId = order.supplierId,
                supplierName = order.supplierName ?: "поставщик #${order.supplierId}",
                purchaseOrderId = order.id,
                purchaseOrderNumber = order.number,
                currency = order.currency,
                paymentTerms = order.paymentTerms.orEmpty(),
                paymentPurpose = "Оплата по заказу ${order.number}",
                lines = lines
            )
        )
        update { it.copy(dialog = SupplierInvoiceDialog.Edit, form = form, error = null) }
    }

    fun openEdit() {
        val invoice = _uiState.value.selected ?: return
        val form = InvoiceForm(
            invoiceId = invoice.id,
            version = invoice.version,
            supplierId = invoice.supplierId,
            supplierName = invoice.supplierName ?: "поставщик #${invoice.supplierId}",
            purchaseOrderId = invoice.purchaseOrderId,
            purchaseOrderNumber = invoice.purchaseOrderNumber,
            currency = invoice.currency,
            documentId = invoice.documentId,
            documentFileName = invoice.fileName,
            number = invoice.number,
            invoiceDate = invoice.invoiceDate,
            validUntil = invoice.validUntil.orEmpty(),
            supplierInn = invoice.supplierInn.orEmpty(),
            supplierKpp = invoice.supplierKpp.orEmpty(),
            pricesIncludeVat = invoice.pricesIncludeVat,
            paymentTerms = invoice.paymentTerms.orEmpty(),
            prepaymentPct = invoice.prepaymentPct?.trimQty().orEmpty(),
            leadTimeDays = invoice.leadTimeDays?.toString().orEmpty(),
            leadTimeWorking = invoice.leadTimeKind != LeadTimeKind.CALENDAR,
            deliveryTerms = invoice.deliveryTerms.orEmpty(),
            paymentPurpose = invoice.paymentPurpose.orEmpty(),
            supplierManager = invoice.supplierManager.orEmpty(),
            note = invoice.note.orEmpty(),
            netTotal = invoice.netTotal.trimMoney(),
            vatTotal = invoice.vatTotal.trimMoney(),
            grossTotal = invoice.grossTotal.trimMoney(),
            lines = invoice.lines.map {
                InvoiceLineForm(
                    lineNo = it.lineNo,
                    supplierItemCode = it.supplierItemCode.orEmpty(),
                    supplierItemName = it.supplierItemName,
                    nomenclatureId = it.nomenclatureId,
                    purchaseOrderLineId = it.purchaseOrderLineId,
                    nomenclatureLabel = listOfNotNull(it.nomenclatureCode, it.nomenclatureName).joinToString(" · "),
                    quantity = it.quantity.trimQty(),
                    unit = it.unit,
                    unitPrice = it.unitPrice.trimMoney(),
                    vatRate = it.vatRate?.trimQty().orEmpty(),
                    vatAmount = it.vatAmount.trimMoney(),
                    lineTotal = it.lineTotal.trimMoney()
                )
            }
        )
        update { it.copy(dialog = SupplierInvoiceDialog.Edit, form = form, error = null) }
    }

    fun updateForm(transform: (InvoiceForm) -> InvoiceForm) {
        val form = _uiState.value.form ?: return
        update { it.copy(form = transform(form)) }
    }

    /** Изменение количества, цены или ставки пересчитывает подсказку суммы строки и итоги. */
    fun updateLine(index: Int, transform: (InvoiceLineForm) -> InvoiceLineForm, recalculate: Boolean) {
        val form = _uiState.value.form ?: return
        val lines = form.lines.mapIndexed { i, line ->
            if (i != index) line else transform(line).let { if (recalculate) recalc(it, form.pricesIncludeVat) else it }
        }
        update { it.copy(form = withTotals(form.copy(lines = lines))) }
    }

    fun setPricesIncludeVat(value: Boolean) {
        val form = _uiState.value.form ?: return
        update { it.copy(form = withTotals(form.copy(pricesIncludeVat = value, lines = form.lines.map { l -> recalc(l, value) }))) }
    }

    fun applyParsedText(rawText: String) {
        val form = _uiState.value.form ?: return
        if (rawText.isBlank()) {
            update { it.copy(error = ApiError("VALIDATION_ERROR", "Вставьте текст счёта для распознавания")) }
            return
        }
        applyParsedInvoice(form, InvoiceTextParser.parse(rawText))
    }

    /** Заполняет форму счёта, отправляя приложенный PDF на сервер для извлечения текста через PDFBox. */
    fun autofillFromPdf() {
        val form = _uiState.value.form ?: return
        val pending = form.pendingFile
        val docId = form.documentId
        if (pending == null && docId == null) {
            update { it.copy(error = ApiError("VALIDATION_ERROR", "Сначала выберите PDF-файл счёта")) }
            return
        }
        update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val result = if (pending != null) {
                repository.parsePdfBytes(pending.fileName, pending.bytes)
            } else {
                repository.parseDocumentPdf(docId!!)
            }
            when (result) {
                is ApiResult.Success -> {
                    val dto = result.data
                    val parsed = ParsedSupplierInvoice(
                        number = dto.number,
                        invoiceDate = dto.invoiceDate,
                        validUntil = dto.validUntil,
                        supplierInn = dto.supplierInn,
                        supplierKpp = dto.supplierKpp,
                        paymentTerms = dto.paymentTerms,
                        prepaymentPct = dto.prepaymentPct,
                        leadTimeDays = dto.leadTimeDays,
                        leadTimeWorking = dto.leadTimeWorking,
                        deliveryTerms = dto.deliveryTerms,
                        supplierManager = dto.supplierManager,
                        pricesIncludeVat = dto.pricesIncludeVat,
                        lines = dto.lines.map {
                            ParsedSupplierInvoiceLine(
                                lineNo = it.lineNo,
                                supplierItemCode = it.supplierItemCode,
                                supplierItemName = it.supplierItemName,
                                quantity = it.quantity,
                                unit = it.unit,
                                unitPrice = it.unitPrice,
                                vatRate = it.vatRate,
                                vatAmount = it.vatAmount,
                                lineTotal = it.lineTotal
                            )
                        }
                    )
                    update { it.copy(isBusy = false) }
                    applyParsedInvoice(
                        form.copy(
                            supplierId = dto.suggestedSupplierId ?: form.supplierId,
                            supplierName = dto.suggestedSupplierName ?: form.supplierName
                        ),
                        parsed
                    )
                }
                is ApiResult.Error -> update { it.copy(isBusy = false, error = result.error) }
            }
        }
    }

    private fun applyParsedInvoice(form: InvoiceForm, parsed: ParsedSupplierInvoice) {
        val effectivePricesIncludeVat = parsed.pricesIncludeVat ?: form.pricesIncludeVat
        val mappedLines = if (parsed.lines.isNotEmpty()) {
            parsed.lines.mapIndexed { idx, pLine ->
                val existing = form.lines.getOrNull(idx)
                    ?: form.lines.firstOrNull {
                        it.supplierItemName.contains(pLine.supplierItemName, ignoreCase = true) ||
                            pLine.supplierItemName.contains(it.supplierItemName, ignoreCase = true)
                    }
                recalc(
                    InvoiceLineForm(
                        lineNo = pLine.lineNo,
                        supplierItemCode = pLine.supplierItemCode,
                        supplierItemName = pLine.supplierItemName,
                        nomenclatureId = existing?.nomenclatureId,
                        purchaseOrderLineId = existing?.purchaseOrderLineId,
                        nomenclatureLabel = existing?.nomenclatureLabel.orEmpty(),
                        quantity = pLine.quantity,
                        unit = pLine.unit,
                        unitPrice = pLine.unitPrice,
                        vatRate = pLine.vatRate
                    ),
                    pricesIncludeVat = effectivePricesIncludeVat
                )
            }
        } else {
            form.lines.map { recalc(it, effectivePricesIncludeVat) }
        }
        val updatedForm = withTotals(
            form.copy(
                number = parsed.number ?: form.number,
                invoiceDate = parsed.invoiceDate ?: form.invoiceDate,
                validUntil = parsed.validUntil ?: form.validUntil,
                supplierInn = parsed.supplierInn ?: form.supplierInn,
                supplierKpp = parsed.supplierKpp ?: form.supplierKpp,
                pricesIncludeVat = effectivePricesIncludeVat,
                paymentTerms = parsed.paymentTerms ?: form.paymentTerms,
                prepaymentPct = parsed.prepaymentPct ?: form.prepaymentPct,
                leadTimeDays = parsed.leadTimeDays ?: form.leadTimeDays,
                leadTimeWorking = parsed.leadTimeWorking ?: form.leadTimeWorking,
                deliveryTerms = parsed.deliveryTerms ?: form.deliveryTerms,
                supplierManager = parsed.supplierManager ?: form.supplierManager,
                lines = mappedLines
            )
        )
        val recognizedParts = buildList {
            if (parsed.number != null) add("№ ${parsed.number}")
            if (parsed.invoiceDate != null) add("дата ${parsed.invoiceDate}")
            if (parsed.supplierInn != null) add("ИНН ${parsed.supplierInn}")
            if (parsed.lines.isNotEmpty()) add("строк: ${parsed.lines.size}")
        }
        if (recognizedParts.isEmpty()) {
            update { it.copy(error = ApiError("VALIDATION_ERROR", "Не удалось распознать поля счёта, проверьте формат документа")) }
        } else {
            update {
                it.copy(
                    form = updatedForm,
                    error = null,
                    message = "Распознано: ${recognizedParts.joinToString(", ")}"
                )
            }
        }
    }

    fun pickInvoiceFile() {
        pickAttachmentFile(
            onPicked = { file -> updateForm { it.copy(pendingFile = file) } },
            onError = { message -> update { it.copy(error = ApiError("VALIDATION_ERROR", message)) } }
        )
    }

    fun saveInvoice() {
        val form = _uiState.value.form ?: return
        if (!form.hasFile) {
            update { it.copy(error = ApiError("VALIDATION_ERROR", "Загрузите файл счёта: без файла счёт не регистрируется")) }
            return
        }
        update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            // 1. Файл счёта — документ DMS типа «Счёт». Документ создаётся один раз:
            //    при повторной попытке сохранения используется уже загруженный.
            val documentId = form.documentId ?: run {
                val file = form.pendingFile!!
                val created = documentRepository.create(
                    CreateDocumentRequest(
                        name = "Счёт № ${form.number.ifBlank { "б/н" }} · ${form.supplierName}",
                        docType = DocumentType.INVOICE
                    )
                )
                val document = when (created) {
                    is ApiResult.Success -> created.data
                    is ApiResult.Error -> {
                        update { it.copy(isBusy = false, error = created.error) }
                        return@launch
                    }
                }
                when (val uploaded = documentRepository.uploadVersion(document.id, file, null)) {
                    is ApiResult.Success -> {
                        updateForm { it.copy(documentId = document.id, documentFileName = file.fileName, pendingFile = null) }
                        document.id
                    }
                    is ApiResult.Error -> {
                        update { it.copy(isBusy = false, error = uploaded.error) }
                        return@launch
                    }
                }
            }
            // 2. Сам счёт.
            val input = toInput(_uiState.value.form ?: form, documentId)
            val result = if (form.invoiceId == null) {
                repository.create(input)
            } else {
                repository.update(form.invoiceId, UpdateSupplierInvoiceRequest(input, form.version))
            }
            when (result) {
                is ApiResult.Success -> {
                    update { it.copy(isBusy = false, dialog = null, form = null, message = "Счёт № ${result.data.number} сохранён, проверьте сверку") }
                    load()
                    refreshSelected(result.data.id)
                }
                is ApiResult.Error -> update { it.copy(isBusy = false, error = result.error) }
            }
        }
    }

    // --- Сверка и переходы ---

    fun openConfirm() = update { it.copy(dialog = SupplierInvoiceDialog.Confirm, acceptReason = "", error = null) }
    fun updateAcceptReason(value: String) = update { it.copy(acceptReason = value) }

    fun confirmCheck() {
        val invoice = _uiState.value.selected ?: return
        val reason = _uiState.value.acceptReason.trim().ifEmpty { null }
        runAction("Сверка подтверждена") {
            repository.confirmCheck(invoice.id, ConfirmSupplierInvoiceCheckRequest(invoice.version, reason))
        }
    }

    fun reopen() {
        val invoice = _uiState.value.selected ?: return
        runAction("Счёт возвращён в черновик") { repository.reopen(invoice.id, SupplierInvoiceActionRequest(invoice.version)) }
    }

    fun openCancel() = update { it.copy(dialog = SupplierInvoiceDialog.Cancel, cancelReason = "", error = null) }
    fun updateCancelReason(value: String) = update { it.copy(cancelReason = value) }

    fun cancel() {
        val invoice = _uiState.value.selected ?: return
        runAction("Счёт отменён") {
            repository.cancel(invoice.id, CancelSupplierInvoiceRequest(invoice.version, _uiState.value.cancelReason.trim()))
        }
    }

    fun openReceipt() {
        update { it.copy(dialog = SupplierInvoiceDialog.Receipt, error = null) }
        viewModelScope.launch {
            when (val r = wmsRepository.listWarehouses()) {
                is ApiResult.Success -> update { s ->
                    val active = r.data.filter { it.active }
                    s.copy(warehouses = active, receiptWarehouseId = s.receiptWarehouseId ?: active.singleOrNull()?.id)
                }
                is ApiResult.Error -> update { it.copy(error = r.error) }
            }
        }
    }

    fun selectReceiptWarehouse(id: Long) = update { it.copy(receiptWarehouseId = id) }

    fun createReceipt() {
        val invoice = _uiState.value.selected ?: return
        val warehouseId = _uiState.value.receiptWarehouseId ?: return
        update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            when (val r = repository.createReceipt(invoice.id, CreateReceiptFromInvoiceRequest(warehouseId, invoice.version))) {
                is ApiResult.Success -> {
                    update { it.copy(isBusy = false, dialog = null, message = "Поступление ${r.data.number} передано складу: «Ожидается к приёмке»") }
                    refreshSelected(invoice.id)
                }
                is ApiResult.Error -> update { it.copy(isBusy = false, error = r.error) }
            }
        }
    }

    /** В 1 клик передаёт сверенный счёт на склад (или открывает выбор склада, если активных несколько). */
    fun sendToWarehouse(invoice: SupplierInvoiceDto) {
        update { it.copy(selected = invoice, isBusy = true, error = null, message = null) }
        viewModelScope.launch {
            when (val wRes = wmsRepository.listWarehouses()) {
                is ApiResult.Error -> update { it.copy(isBusy = false, error = wRes.error) }
                is ApiResult.Success -> {
                    val active = wRes.data.filter { it.active }
                    when {
                        active.isEmpty() -> update {
                            it.copy(isBusy = false, error = ApiError("VALIDATION_ERROR", "Нет активных складов для приёмки"))
                        }
                        active.size == 1 -> {
                            val warehouse = active.first()
                            when (val r = repository.createReceipt(
                                invoice.id,
                                CreateReceiptFromInvoiceRequest(warehouse.id, invoice.version)
                            )) {
                                is ApiResult.Success -> {
                                    update {
                                        it.copy(
                                            isBusy = false,
                                            dialog = null,
                                            warehouses = active,
                                            receiptWarehouseId = warehouse.id,
                                            message = "Поступление ${r.data.number} передано на склад «${warehouse.name}»"
                                        )
                                    }
                                    refreshSelected(invoice.id)
                                }
                                is ApiResult.Error -> update { it.copy(isBusy = false, error = r.error) }
                            }
                        }
                        else -> update {
                            it.copy(
                                isBusy = false,
                                warehouses = active,
                                receiptWarehouseId = it.receiptWarehouseId ?: active.first().id,
                                dialog = SupplierInvoiceDialog.Receipt
                            )
                        }
                    }
                }
            }
        }
    }

    fun downloadFile(target: SupplierInvoiceDto? = null) {
        val invoice = target ?: _uiState.value.selected ?: return
        viewModelScope.launch {
            when (val r = repository.downloadFile(invoice.id)) {
                is ApiResult.Success -> saveAttachmentFile(
                    safeAttachmentFileName(invoice.fileName ?: "invoice-${invoice.number}.pdf", invoice.id),
                    r.data.mimeType,
                    r.data.bytes
                )
                is ApiResult.Error -> update { it.copy(error = r.error) }
            }
        }
    }

    fun previewHtml(target: SupplierInvoiceDto? = null) {
        val invoice = target ?: _uiState.value.selected ?: return
        viewModelScope.launch {
            when (val r = repository.previewHtml(invoice.id)) {
                is ApiResult.Success -> saveAttachmentFile(
                    r.data.fileName,
                    "text/html; charset=utf-8",
                    r.data.htmlContent.encodeToByteArray()
                )
                is ApiResult.Error -> update { it.copy(error = r.error) }
            }
        }
    }

    fun dismissDialog() = update { it.copy(dialog = null, error = null) }
    fun dismissMessage() = update { it.copy(message = null) }

    // --- Вспомогательное ---

    private fun runAction(success: String, block: suspend () -> ApiResult<SupplierInvoiceDto>) {
        update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            when (val r = block()) {
                is ApiResult.Success -> {
                    update { it.copy(isBusy = false, dialog = null, message = success) }
                    refreshSelected(r.data.id)
                }
                is ApiResult.Error -> update { it.copy(isBusy = false, error = r.error) }
            }
        }
    }

    private fun toInput(form: InvoiceForm, documentId: Long) = SupplierInvoiceInput(
        number = form.number.trim(),
        invoiceDate = form.invoiceDate.trim(),
        supplierId = form.supplierId,
        purchaseOrderId = form.purchaseOrderId,
        documentId = documentId,
        supplierInn = form.supplierInn.trim().ifEmpty { null },
        supplierKpp = form.supplierKpp.trim().ifEmpty { null },
        currency = form.currency,
        pricesIncludeVat = form.pricesIncludeVat,
        netTotal = form.netTotal.normalized(),
        vatTotal = form.vatTotal.normalized(),
        grossTotal = form.grossTotal.normalized(),
        validUntil = form.validUntil.trim().ifEmpty { null },
        paymentTerms = form.paymentTerms.trim().ifEmpty { null },
        prepaymentPct = form.prepaymentPct.trim().ifEmpty { null },
        leadTimeDays = form.leadTimeDays.trim().toIntOrNull(),
        leadTimeKind = if (form.leadTimeDays.isBlank()) null else if (form.leadTimeWorking) LeadTimeKind.WORKING else LeadTimeKind.CALENDAR,
        deliveryTerms = form.deliveryTerms.trim().ifEmpty { null },
        paymentPurpose = form.paymentPurpose.trim().ifEmpty { null },
        supplierManager = form.supplierManager.trim().ifEmpty { null },
        note = form.note.trim().ifEmpty { null },
        lines = form.lines.map {
            SupplierInvoiceLineInput(
                lineNo = it.lineNo,
                supplierItemCode = it.supplierItemCode.trim().ifEmpty { null },
                supplierItemName = it.supplierItemName.trim(),
                nomenclatureId = it.nomenclatureId,
                purchaseOrderLineId = it.purchaseOrderLineId,
                quantity = it.quantity.normalized(),
                unit = it.unit.trim(),
                unitPrice = it.unitPrice.normalized(),
                vatRate = it.vatRate.trim().ifEmpty { null }?.normalized(),
                vatAmount = it.vatAmount.normalized(),
                lineTotal = it.lineTotal.normalized()
            )
        }
    )

    private fun update(transform: (SupplierInvoiceUiState) -> SupplierInvoiceUiState) {
        _uiState.value = transform(_uiState.value)
    }

    companion object {
        fun String.normalized(): String = trim().replace(" ", "").replace(',', '.')

        fun String.trimQty(): String = if (contains('.')) trimEnd('0').trimEnd('.') else this
        fun String.trimMoney(): String = toDoubleOrNull()?.let { money(it) } ?: this

        /** Подсказка суммы строки и НДС (до копеек); сервер всё равно пересчитает при сверке. */
        fun recalc(line: InvoiceLineForm, pricesIncludeVat: Boolean): InvoiceLineForm {
            val qty = line.quantity.normalized().toDoubleOrNull() ?: return line
            val price = line.unitPrice.normalized().toDoubleOrNull() ?: return line
            val rate = line.vatRate.normalized().toDoubleOrNull() ?: 0.0
            val amount = round2(qty * price)
            val vat = if (pricesIncludeVat) round2(amount * rate / (100 + rate)) else round2(amount * rate / 100)
            return line.copy(lineTotal = money(amount), vatAmount = money(vat))
        }

        fun withTotals(form: InvoiceForm): InvoiceForm {
            val sums = form.lines.map { (it.lineTotal.normalized().toDoubleOrNull() ?: 0.0) to (it.vatAmount.normalized().toDoubleOrNull() ?: 0.0) }
            val lineSum = round2(sums.sumOf { it.first })
            val vat = round2(sums.sumOf { it.second })
            val net = if (form.pricesIncludeVat) round2(lineSum - vat) else lineSum
            val gross = if (form.pricesIncludeVat) lineSum else round2(lineSum + vat)
            return form.copy(netTotal = money(net), vatTotal = money(vat), grossTotal = money(gross))
        }

        private fun round2(value: Double): Double = (value * 100).roundToLong() / 100.0

        fun money(value: Double): String {
            val cents = (value * 100).roundToLong()
            val sign = if (cents < 0) "-" else ""
            val abs = kotlin.math.abs(cents)
            return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
        }

        fun severityTitle(severity: InvoiceIssueSeverity): String = when (severity) {
            InvoiceIssueSeverity.ERROR -> "Ошибка"
            InvoiceIssueSeverity.REQUIRES_REASON -> "Нужно обоснование"
            InvoiceIssueSeverity.WARNING -> "Внимание"
        }
    }
}
