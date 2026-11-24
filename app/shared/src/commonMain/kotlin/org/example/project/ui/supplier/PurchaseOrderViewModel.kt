package org.example.project.ui.supplier

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.PurchaseOrderRepository
import org.example.project.data.SupplierRepository
import org.example.project.data.UserRepository
import org.example.project.dto.ApiError
import org.example.project.dto.CancelPurchaseDeliveryRequest
import org.example.project.dto.CancelPurchaseOrderRequest
import org.example.project.dto.CreatePurchaseDeliveryLineInput
import org.example.project.dto.CreatePurchaseDeliveryRequest
import org.example.project.dto.CreatePurchaseOrderLineInput
import org.example.project.dto.CreatePurchaseOrderRequest
import org.example.project.dto.PurchaseOrderActionRequest
import org.example.project.dto.PurchaseOrderDto
import org.example.project.dto.PurchaseOrderStatus
import org.example.project.dto.SupplierOfferStatus
import org.example.project.dto.UpdatePurchaseOrderLineInput
import org.example.project.dto.UpdatePurchaseOrderRequest
import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission
import org.example.project.ui.home.currentIsoDate

class PurchaseOrderViewModel(
    private val purchaseOrderRepository: PurchaseOrderRepository,
    private val supplierRepository: SupplierRepository,
    private val userRepository: UserRepository,
    private val currentUser: UserDto
) : ViewModel() {

    private val _uiState = MutableStateFlow(PurchaseOrderUiState())
    val uiState: StateFlow<PurchaseOrderUiState> = _uiState.asStateFlow()

    init {
        loadOrders()
        loadSelectedOffers()
        loadUsers()
    }

    // --- Права ---

    fun canOrder(): Boolean = currentUser.hasPermission("suppliers", "order")
    fun canApprove(): Boolean = currentUser.hasPermission("suppliers", "approve_order")
    fun canReceive(): Boolean = currentUser.hasPermission("suppliers", "receive")

    // --- Загрузка ---

    fun loadOrders() {
        update { it.copy(isOrdersLoading = true, operationError = null) }
        viewModelScope.launch {
            when (val result = purchaseOrderRepository.listOrders(status = _uiState.value.statusFilter)) {
                is ApiResult.Success -> {
                    update { it.copy(orders = result.data, isOrdersLoading = false) }
                    if (_uiState.value.selectedOrder == null && result.data.isNotEmpty()) {
                        selectOrder(result.data.first().id)
                    }
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    fun loadDeliveries() {
        update { it.copy(isOrdersLoading = true, operationError = null) }
        viewModelScope.launch {
            // Один запрос вместо обхода заказов: фильтр по заказу делает сервер.
            when (val result = purchaseOrderRepository.listDeliveries()) {
                is ApiResult.Success -> update {
                    it.copy(deliveries = result.data, isOrdersLoading = false)
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    private fun loadSelectedOffers(preselectId: Long? = null, reportErrors: Boolean = false) {
        viewModelScope.launch {
            when (val result = supplierRepository.listOffers(status = SupplierOfferStatus.SELECTED)) {
                is ApiResult.Success -> {
                    update { it.copy(selectedOffers = result.data) }
                    // Диалог открыт без предложения, а оно появилось после обновления списка.
                    val state = _uiState.value
                    if (state.activeDialog == PurchaseOrderDialog.Create && state.createForm.offerId == null && preselectId == null) {
                        result.data.firstOrNull()?.let { selectOfferForOrder(it.id) }
                    }
                }
                // При входе ошибка не показывается (нет права — нет и кнопки), в диалоге — показывается.
                is ApiResult.Error -> if (reportErrors) failWith(result.error)
            }
        }
    }

    private fun loadUsers() {
        viewModelScope.launch {
            when (val result = userRepository.list()) {
                is ApiResult.Success -> update {
                    it.copy(users = (result.data + currentUser).distinctBy { user -> user.id })
                }
                is ApiResult.Error -> { }
            }
        }
    }

    fun selectOrder(id: Long?) {
        if (id == null) {
            update { it.copy(selectedOrder = null, history = emptyList(), deliveries = emptyList()) }
            return
        }
        update { it.copy(isOrderLoading = true, operationError = null) }
        viewModelScope.launch {
            when (val result = purchaseOrderRepository.getOrder(id)) {
                is ApiResult.Success -> {
                    update { it.copy(selectedOrder = result.data, isOrderLoading = false) }
                    loadOrderDetails(id)
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    private fun loadOrderDetails(id: Long) {
        viewModelScope.launch {
            val history = when (val result = purchaseOrderRepository.orderHistory(id)) {
                is ApiResult.Success -> result.data
                is ApiResult.Error -> emptyList()
            }
            val deliveries = when (val result = purchaseOrderRepository.listDeliveries(id)) {
                is ApiResult.Success -> result.data
                is ApiResult.Error -> emptyList()
            }
            update { it.copy(history = history, deliveries = deliveries) }
        }
    }

    fun selectTab(tab: PurchaseOrderTab) {
        update { it.copy(activeTab = tab, operationError = null) }
        if (tab == PurchaseOrderTab.DELIVERIES) loadDeliveries()
    }

    fun updateStatusFilter(status: PurchaseOrderStatus?) {
        update { it.copy(statusFilter = status) }
        loadOrders()
    }

    /** Имя пользователя по id — только из реально загруженного списка. */
    fun userName(id: Long): String =
        _uiState.value.users.firstOrNull { it.id == id }?.fullName ?: "пользователь #$id"

    fun dismissError() = update { it.copy(operationError = null) }
    fun dismissSuccess() = update { it.copy(successMessage = null) }
    fun dismissDialog() = update { it.copy(activeDialog = null, operationError = null) }
    fun showDialog(dialog: PurchaseOrderDialog) = update { it.copy(activeDialog = dialog, operationError = null) }

    // --- Создание заказа ---

    fun openCreate(offerId: Long? = null) {
        if (!canOrder()) {
            fail("Нет права оформлять заказ поставщику")
            return
        }
        val offers = _uiState.value.selectedOffers
        val offer = offers.firstOrNull { it.id == offerId } ?: offers.firstOrNull()
        update {
            it.copy(
                activeDialog = PurchaseOrderDialog.Create,
                operationError = null,
                createForm = formFor(offer)
            )
        }
        offer?.let { loadOfferLines(it.id) }
        // Список выбранных предложений мог устареть с момента входа.
        loadSelectedOffers(preselectId = offer?.id, reportErrors = true)
    }

    private fun formFor(offer: org.example.project.dto.SupplierOfferDto?): CreatePurchaseOrderForm {
        if (offer == null) return CreatePurchaseOrderForm(responsibleId = currentUser.id)
        return CreatePurchaseOrderForm(
            offerId = offer.id,
            currency = offer.currency,
            paymentTerms = offer.paymentTerms.orEmpty(),
            expectedAt = "",
            responsibleId = currentUser.id,
            demandIds = listOf(offer.demandId),
            lines = offer.lines.map { line ->
                OrderLineForm(
                    offerLineId = line.id,
                    label = "Позиция ${line.lineNo}",
                    unit = line.unit,
                    unitPrice = line.unitPrice,
                    offeredQuantity = line.quantity,
                    quantity = line.quantity
                )
            }
        )
    }

    fun selectOfferForOrder(offerId: Long) {
        val offer = _uiState.value.selectedOffers.firstOrNull { it.id == offerId } ?: return
        update { it.copy(createForm = formFor(offer)) }
        loadOfferLines(offerId)
    }

    /** Список предложений приходит без позиций — берём их из карточки предложения. */
    private fun loadOfferLines(offerId: Long) {
        viewModelScope.launch {
            when (val result = supplierRepository.getOffer(offerId)) {
                is ApiResult.Success -> update {
                    if (it.createForm.offerId == offerId) it.copy(createForm = formFor(result.data)) else it
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    fun updateCreateForm(form: CreatePurchaseOrderForm) = update { it.copy(createForm = form) }

    fun updateCreateLine(index: Int, quantity: String) {
        val form = _uiState.value.createForm
        val lines = form.lines.toMutableList()
        lines.getOrNull(index)?.let { lines[index] = it.copy(quantity = quantity) }
        update { it.copy(createForm = form.copy(lines = lines)) }
    }

    fun createOrder() {
        val form = _uiState.value.createForm
        val offerId = form.offerId ?: run { fail("Выберите предложение"); return }
        if (form.lines.isEmpty()) {
            fail("У предложения нет позиций")
            return
        }
        val lineInputs = mutableListOf<CreatePurchaseOrderLineInput>()
        form.lines.forEachIndexed { index, line ->
            if (!line.quantity.isPositiveDecimal()) {
                fail("Количество в позиции ${index + 1} должно быть положительным числом")
                return
            }
            lineInputs += CreatePurchaseOrderLineInput(line.offerLineId, line.quantity.trim())
        }
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            val request = CreatePurchaseOrderRequest(
                offerId = offerId,
                demandIds = form.demandIds,
                deliveryAddress = form.deliveryAddress.trim().ifBlank { null },
                paymentTerms = form.paymentTerms.trim().ifBlank { null },
                contractNo = form.contractNo.trim().ifBlank { null },
                invoiceNo = form.invoiceNo.trim().ifBlank { null },
                expectedAt = form.expectedAt.trim().ifBlank { null },
                note = form.note.trim().ifBlank { null },
                responsibleId = form.responsibleId,
                lines = lineInputs,
                idempotencyKey = newKey("po-create")
            )
            when (val result = purchaseOrderRepository.createOrder(request)) {
                is ApiResult.Success -> {
                    update {
                        it.copy(
                            isActionRunning = false,
                            activeDialog = null,
                            createForm = CreatePurchaseOrderForm(responsibleId = currentUser.id),
                            successMessage = "Заказ ${result.data.number} создан",
                            selectedOrder = result.data
                        )
                    }
                    loadOrders()
                    loadSelectedOffers()
                    loadOrderDetails(result.data.id)
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    // --- Правка заказа ---

    fun openEdit() {
        val order = _uiState.value.selectedOrder ?: return
        if (!canOrder()) {
            fail("Нет права оформлять заказ поставщику")
            return
        }
        update {
            it.copy(
                operationError = null,
                activeDialog = PurchaseOrderDialog.Edit,
                editForm = EditPurchaseOrderForm(
                    id = order.id,
                    version = order.version,
                    deliveryAddress = order.deliveryAddress.orEmpty(),
                    paymentTerms = order.paymentTerms.orEmpty(),
                    contractNo = order.contractNo.orEmpty(),
                    invoiceNo = order.invoiceNo.orEmpty(),
                    expectedAt = order.expectedAt?.substringBefore('T').orEmpty(),
                    note = order.note.orEmpty(),
                    lines = order.lines.map { line ->
                        val remaining = line.remainingQuantity
                        OrderLineEditForm(
                            orderLineId = line.id,
                            label = "Позиция ${line.lineNo}",
                            unit = line.unit,
                            orderedQuantity = line.quantity,
                            deliveredQuantity = "${line.deliveredQuantity} / осталось $remaining",
                            quantity = line.quantity
                        )
                    }
                )
            )
        }
    }

    fun updateEditForm(form: EditPurchaseOrderForm) = update { it.copy(editForm = form) }

    fun updateEditLine(index: Int, quantity: String) {
        val form = _uiState.value.editForm ?: return
        val lines = form.lines.toMutableList()
        lines.getOrNull(index)?.let { lines[index] = it.copy(quantity = quantity) }
        update { it.copy(editForm = form.copy(lines = lines)) }
    }

    fun saveEdit() {
        val form = _uiState.value.editForm ?: return
        val lineInputs = mutableListOf<UpdatePurchaseOrderLineInput>()
        form.lines.forEachIndexed { index, line ->
            if (!line.quantity.isPositiveDecimal()) {
                fail("Количество в позиции ${index + 1} должно быть положительным числом")
                return
            }
            lineInputs += UpdatePurchaseOrderLineInput(line.orderLineId, line.quantity.trim())
        }
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            val request = UpdatePurchaseOrderRequest(
                deliveryAddress = form.deliveryAddress.trim().ifBlank { null },
                paymentTerms = form.paymentTerms.trim().ifBlank { null },
                contractNo = form.contractNo.trim().ifBlank { null },
                invoiceNo = form.invoiceNo.trim().ifBlank { null },
                expectedAt = form.expectedAt.trim().ifBlank { null },
                note = form.note.trim().ifBlank { null },
                lines = lineInputs,
                changeReason = form.changeReason.trim().ifBlank { null },
                version = form.version
            )
            when (val result = purchaseOrderRepository.updateOrder(form.id, request)) {
                is ApiResult.Success -> {
                    update {
                        it.copy(
                            isActionRunning = false,
                            activeDialog = null,
                            editForm = null,
                            selectedOrder = result.data,
                            successMessage = if (result.data.reapprovalRequired) {
                                "Заказ изменён и возвращён на согласование"
                            } else {
                                "Заказ изменён"
                            }
                        )
                    }
                    loadOrders()
                    loadOrderDetails(result.data.id)
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    // --- Жизненный цикл ---

    fun submit() = action("submit", "Заказ отправлен на согласование") { id, version ->
        purchaseOrderRepository.submitOrder(id, PurchaseOrderActionRequest(version, idempotencyKey = newKey("po-submit")))
    }

    fun approve() = action("approve", "Заказ согласован") { id, version ->
        purchaseOrderRepository.approveOrder(id, PurchaseOrderActionRequest(version, idempotencyKey = newKey("po-approve")))
    }

    fun send() = action("send", "Заказ отправлен поставщику") { id, version ->
        purchaseOrderRepository.sendOrder(id, PurchaseOrderActionRequest(version, idempotencyKey = newKey("po-send")))
    }

    fun close() = action("close", "Заказ закрыт") { id, version ->
        purchaseOrderRepository.closeOrder(id, PurchaseOrderActionRequest(version, idempotencyKey = newKey("po-close")))
    }

    fun openCancelOrder() {
        val order = _uiState.value.selectedOrder ?: return
        update {
            it.copy(
                operationError = null,
                activeDialog = PurchaseOrderDialog.CancelOrder(order.number),
                cancelForm = CancelOrderForm()
            )
        }
    }

    fun updateCancelForm(form: CancelOrderForm) = update { it.copy(cancelForm = form) }

    fun cancelOrder() {
        val order = _uiState.value.selectedOrder ?: return
        val form = _uiState.value.cancelForm ?: CancelOrderForm()
        if (form.reason.isBlank()) {
            fail("Укажите причину отмены")
            return
        }
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            val request = CancelPurchaseOrderRequest(order.version, form.reason.trim(), newKey("po-cancel"))
            when (val result = purchaseOrderRepository.cancelOrder(order.id, request)) {
                is ApiResult.Success -> {
                    update {
                        it.copy(
                            isActionRunning = false,
                            activeDialog = null,
                            selectedOrder = result.data,
                            successMessage = "Заказ ${result.data.number} отменён"
                        )
                    }
                    loadOrders()
                    loadOrderDetails(order.id)
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    private fun action(
        kind: String,
        successMessage: String,
        call: suspend (Long, Long) -> ApiResult<PurchaseOrderDto>
    ) {
        val order = _uiState.value.selectedOrder ?: return
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            when (val result = call(order.id, order.version)) {
                is ApiResult.Success -> {
                    update {
                        it.copy(
                            isActionRunning = false,
                            selectedOrder = result.data,
                            successMessage = successMessage
                        )
                    }
                    loadOrders()
                    loadOrderDetails(order.id)
                    if (kind == "approve" || kind == "send") loadSelectedOffers()
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    // --- Поставки ---

    fun openDelivery() {
        val order = _uiState.value.selectedOrder ?: return
        if (!canReceive()) {
            fail("Нет права регистрировать поставку")
            return
        }
        val lines = order.lines
            .filter { it.remainingQuantity.isPositiveDecimal() }
            .map { line ->
                DeliveryLineForm(
                    orderLineId = line.id,
                    label = "Позиция ${line.lineNo}",
                    unit = line.unit,
                    remaining = line.remainingQuantity,
                    quantity = line.remainingQuantity
                )
            }
        if (lines.isEmpty()) {
            fail("По заказу не осталось недопоставленных позиций")
            return
        }
        update {
            it.copy(
                operationError = null,
                activeDialog = PurchaseOrderDialog.RegisterDelivery,
                deliveryForm = CreateDeliveryForm(deliveredAt = currentIsoDate(), lines = lines)
            )
        }
    }

    fun updateDeliveryForm(form: CreateDeliveryForm) = update { it.copy(deliveryForm = form) }

    fun updateDeliveryLine(index: Int, quantity: String) {
        val form = _uiState.value.deliveryForm ?: return
        val lines = form.lines.toMutableList()
        lines.getOrNull(index)?.let { lines[index] = it.copy(quantity = quantity) }
        update { it.copy(deliveryForm = form.copy(lines = lines)) }
    }

    fun registerDelivery() {
        val order = _uiState.value.selectedOrder ?: return
        val form = _uiState.value.deliveryForm ?: return
        if (!form.deliveredAt.trim().matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) {
            fail("Дата поставки должна быть в формате ГГГГ-ММ-ДД")
            return
        }
        val inputs = mutableListOf<CreatePurchaseDeliveryLineInput>()
        form.lines.forEachIndexed { index, line ->
            if (!line.quantity.isPositiveDecimal()) {
                fail("Количество в позиции ${index + 1} должно быть положительным числом")
                return
            }
            inputs += CreatePurchaseDeliveryLineInput(line.orderLineId, line.quantity.trim())
        }
        if (inputs.isEmpty()) {
            fail("Добавьте хотя бы одну позицию поставки")
            return
        }
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            val request = CreatePurchaseDeliveryRequest(
                deliveredAt = form.deliveredAt.trim(),
                deliveryNote = form.deliveryNote.trim().ifBlank { null },
                sourceDocument = form.sourceDocument.trim().ifBlank { null },
                note = form.note.trim().ifBlank { null },
                lines = inputs,
                idempotencyKey = newKey("pd-create")
            )
            when (val result = purchaseOrderRepository.registerDelivery(order.id, request)) {
                is ApiResult.Success -> {
                    update {
                        it.copy(
                            isActionRunning = false,
                            activeDialog = null,
                            deliveryForm = null,
                            successMessage = "Поставка ${result.data.number} зарегистрирована"
                        )
                    }
                    loadOrders()
                    selectOrder(order.id)
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    fun openCancelDelivery(deliveryId: Long) {
        val delivery = _uiState.value.deliveries.firstOrNull { it.id == deliveryId } ?: return
        update {
            it.copy(
                operationError = null,
                activeDialog = PurchaseOrderDialog.CancelDelivery(delivery.id, delivery.number),
                cancelForm = CancelOrderForm()
            )
        }
    }

    fun cancelDelivery() {
        val dialog = _uiState.value.activeDialog as? PurchaseOrderDialog.CancelDelivery ?: return
        val form = _uiState.value.cancelForm ?: CancelOrderForm()
        if (form.reason.isBlank()) {
            fail("Укажите причину отмены")
            return
        }
        update { it.copy(isActionRunning = true, operationError = null) }
        viewModelScope.launch {
            // Версия поставки берётся из загруженного списка: сервер отклонит устаревшую.
            val actual = _uiState.value.deliveries.firstOrNull { it.id == dialog.id }
            val payload = CancelPurchaseDeliveryRequest(
                version = actual?.version ?: 0,
                reason = form.reason.trim(),
                idempotencyKey = newKey("pd-cancel")
            )
            when (val result = purchaseOrderRepository.cancelDelivery(dialog.id, payload)) {
                is ApiResult.Success -> {
                    update {
                        it.copy(
                            isActionRunning = false,
                            activeDialog = null,
                            successMessage = "Поставка ${result.data.number} отменена"
                        )
                    }
                    loadOrders()
                    _uiState.value.selectedOrder?.let { selectOrder(it.id) }
                }
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    // --- Служебное ---

    private fun update(block: (PurchaseOrderUiState) -> PurchaseOrderUiState) {
        _uiState.value = block(_uiState.value)
    }

    private fun fail(message: String) {
        update { it.copy(operationError = ApiError("VALIDATION_ERROR", message), isActionRunning = false) }
    }

    private fun failWith(error: ApiError) {
        update { it.copy(operationError = error, isActionRunning = false, isOrdersLoading = false, isOrderLoading = false) }
    }

    private fun newKey(prefix: String): String =
        "$prefix-${currentUser.id}-${kotlin.random.Random.nextLong().toString(16)}"
}

private fun String.isPositiveDecimal(): Boolean =
    trim().toDoubleOrNull()?.let { it > 0.0 } == true
