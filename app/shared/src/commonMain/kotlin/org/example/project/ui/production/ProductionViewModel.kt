package org.example.project.ui.production

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.BomRepository
import org.example.project.data.ProjectRepository
import org.example.project.data.ProductionRepository
import org.example.project.data.UserRepository
import org.example.project.dto.ApiError
import org.example.project.dto.BomRevisionStatus
import org.example.project.dto.CancelProductionOrderRequest
import org.example.project.dto.CompleteProductionOrderRequest
import org.example.project.dto.CreateProductionOrderRequest
import org.example.project.dto.CreateProductionStageInput
import org.example.project.dto.ProductionOrderActionRequest
import org.example.project.dto.ProductionOrderDto
import org.example.project.dto.ProductionOrderStatus
import org.example.project.dto.ProductionStageActionRequest
import org.example.project.dto.ProductionStageStatus
import org.example.project.dto.ProductionStageType
import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission

class ProductionViewModel(
    private val productionRepository: ProductionRepository,
    private val projectRepository: ProjectRepository,
    private val bomRepository: BomRepository,
    private val userRepository: UserRepository,
    private val currentUser: UserDto
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProductionUiState())
    val uiState: StateFlow<ProductionUiState> = _uiState.asStateFlow()

    init {
        // Ответственный по умолчанию — текущий пользователь: справочник
        // сотрудников может быть недоступен без права users:read_any.
        _uiState.value = _uiState.value.copy(
            createForm = _uiState.value.createForm.copy(responsibleId = currentUser.id),
            users = listOf(currentUser)
        )
        loadOrders()
        loadProjects()
        loadModels()
        loadUsers()
    }

    fun hasPermission(): Boolean = currentUser.hasPermission("production", "manage")

    fun dismissError() {
        _uiState.value = _uiState.value.copy(operationError = null)
    }

    fun dismissSuccess() {
        _uiState.value = _uiState.value.copy(successMessage = null)
    }

    // --- Загрузка справочников ---

    fun loadOrders() {
        _uiState.value = _uiState.value.copy(isOrdersLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = productionRepository.listOrders(
                projectId = _uiState.value.projectFilter,
                status = _uiState.value.statusFilter
            )) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    orders = result.data,
                    isOrdersLoading = false
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isOrdersLoading = false,
                    operationError = result.error
                )
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
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    models = result.data,
                    modelsAccessDenied = false
                )
                is ApiResult.Error -> {
                    // 401/403 не скрываем: без справочника изделий заказ не создать.
                    val denied = result.statusCode == 401 || result.statusCode == 403
                    if (denied) {
                        _uiState.value = _uiState.value.copy(modelsAccessDenied = true)
                    }
                }
            }
        }
    }

    private fun loadUsers() {
        viewModelScope.launch {
            when (val result = userRepository.list()) {
                is ApiResult.Success -> {
                    val merged = (result.data + currentUser).distinctBy { it.id }
                    _uiState.value = _uiState.value.copy(users = merged)
                }
                is ApiResult.Error -> { }
            }
        }
    }

    fun updateStatusFilter(status: ProductionOrderStatus?) {
        _uiState.value = _uiState.value.copy(statusFilter = status)
        loadOrders()
    }

    fun updateProjectFilter(projectId: Long?) {
        _uiState.value = _uiState.value.copy(projectFilter = projectId)
        loadOrders()
    }

    // --- Карточка заказа ---

    fun selectOrder(order: ProductionOrderDto?) {
        _uiState.value = _uiState.value.copy(
            selectedOrder = order,
            instances = emptyList(),
            operationError = null,
            successMessage = null
        )
        order?.let { loadOrder(it.id) }
    }

    fun refreshSelectedOrder() {
        _uiState.value.selectedOrder?.let { loadOrder(it.id) }
    }

    private fun loadOrder(id: Long) {
        viewModelScope.launch {
            when (val result = productionRepository.getOrder(id)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(selectedOrder = result.data)
                    loadInstances(id)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    private fun loadInstances(orderId: Long) {
        viewModelScope.launch {
            when (val result = productionRepository.listInstances(orderId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(instances = result.data)
                is ApiResult.Error -> { }
            }
        }
    }

    // --- Диалоги ---

    fun showDialog(dialog: ProductionDialog) {
        _uiState.value = _uiState.value.copy(activeDialog = dialog, operationError = null)
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(
            activeDialog = null,
            operationError = null,
            cancelReason = "",
            skipReason = "",
            noteReason = "",
            pendingStageSkipId = null,
            completeForm = CompleteProductionForm(),
            successMessage = null
        )
    }

    // --- Создание заказа ---

    fun updateCreateForm(form: CreateProductionOrderForm) {
        _uiState.value = _uiState.value.copy(createForm = form)
    }

    fun onModelChanged(modelId: Long?) {
        _uiState.value = _uiState.value.copy(
            createForm = _uiState.value.createForm.copy(modelId = modelId, revisionId = null),
            revisions = emptyList()
        )
        loadRevisions(modelId)
    }

    private fun loadRevisions(modelId: Long?) {
        modelId ?: return
        viewModelScope.launch {
            when (val result = bomRepository.listRevisions(modelId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    revisions = result.data.filter {
                        it.status == BomRevisionStatus.APPROVED || it.status == BomRevisionStatus.ACTIVE
                    }
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    fun addStage() {
        val form = _uiState.value.createForm
        _uiState.value = _uiState.value.copy(
            createForm = form.copy(stageTypes = form.stageTypes + null)
        )
    }

    fun removeStage(index: Int) {
        val form = _uiState.value.createForm
        if (form.stageTypes.size <= 1) return
        _uiState.value = _uiState.value.copy(
            createForm = form.copy(stageTypes = form.stageTypes.filterIndexed { i, _ -> i != index })
        )
    }

    fun updateStageType(index: Int, type: ProductionStageType?) {
        val form = _uiState.value.createForm
        _uiState.value = _uiState.value.copy(
            createForm = form.copy(
                stageTypes = form.stageTypes.mapIndexed { i, current -> if (i == index) type else current }
            )
        )
    }

    fun createOrder() {
        if (!hasPermission()) return
        val form = _uiState.value.createForm
        val projectId = form.projectId
        val modelId = form.modelId
        val revisionId = form.revisionId
        val responsibleId = form.responsibleId
        val quantity = form.plannedQuantity.trim().toIntOrNull()
        when {
            projectId == null -> return fail("Выберите проект")
            modelId == null -> return fail("Выберите изделие")
            revisionId == null -> return fail("Выберите утверждённую ревизию BOM")
            responsibleId == null -> return fail("Выберите ответственного")
            quantity == null || quantity <= 0 -> return fail("Количество должно быть положительным")
        }
        val stageTypes = form.stageTypes.filterNotNull()
        if (stageTypes.isEmpty()) {
            return fail("Добавьте хотя бы один этап производства")
        }
        val plannedStartAt = form.plannedStartAt.trim()
        val plannedEndAt = form.plannedEndAt.trim()
        if (plannedStartAt.isNotEmpty() && !plannedStartAt.isIsoDateTime()) {
            return fail("Начало укажите в формате 2026-11-20T09:00+03:00")
        }
        if (plannedEndAt.isNotEmpty() && !plannedEndAt.isIsoDateTime()) {
            return fail("Окончание укажите в формате 2026-11-20T18:00+03:00")
        }
        _uiState.value = _uiState.value.copy(isActionRunning = true, operationError = null)
        viewModelScope.launch {
            val request = CreateProductionOrderRequest(
                projectId = projectId,
                productModelId = modelId,
                bomRevisionId = revisionId,
                plannedQuantity = quantity,
                plannedStartAt = plannedStartAt.ifEmpty { null },
                plannedEndAt = plannedEndAt.ifEmpty { null },
                responsibleId = responsibleId,
                departmentId = form.departmentId,
                priority = form.priority,
                stages = stageTypes.mapIndexed { index, type ->
                    CreateProductionStageInput(
                        stageType = type,
                        name = type.localizedName(),
                        sequence = index + 1
                    )
                }
            )
            when (val result = productionRepository.createOrder(request)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isActionRunning = false,
                        activeDialog = null,
                        createForm = CreateProductionOrderForm(),
                        revisions = emptyList(),
                        successMessage = "Заказ ${result.data.number} создан",
                        selectedOrder = result.data
                    )
                    loadOrders()
                    loadInstances(result.data.id)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isActionRunning = false,
                    operationError = result.error
                )
            }
        }
    }

    // --- Переходы статусов ---

    fun planOrder() = orderAction("Заказ запланирован") { id, version ->
        productionRepository.planOrder(id, ProductionOrderActionRequest(version))
    }

    fun startOrder() = orderAction("Производство запущено") { id, version ->
        productionRepository.startOrder(id, ProductionOrderActionRequest(version))
    }

    fun resumeOrder() = orderAction("Производство возобновлено") { id, version ->
        productionRepository.resumeOrder(id, ProductionOrderActionRequest(version))
    }

    fun closeOrder() = orderAction("Заказ закрыт") { id, version ->
        productionRepository.closeOrder(id, ProductionOrderActionRequest(version))
    }

    fun submitHold() {
        val order = _uiState.value.selectedOrder ?: return
        val note = _uiState.value.noteReason.trim()
        if (note.isBlank()) return fail("Укажите причину приостановки")
        runOrderAction("Заказ приостановлен") {
            productionRepository.holdOrder(order.id, ProductionOrderActionRequest(order.version, note))
        }
    }

    fun submitCancel() {
        val order = _uiState.value.selectedOrder ?: return
        val reason = _uiState.value.cancelReason.trim()
        if (reason.isBlank()) return fail("Укажите причину отмены")
        _uiState.value = _uiState.value.copy(isActionRunning = true, operationError = null)
        viewModelScope.launch {
            when (val result = productionRepository.cancelOrder(
                order.id,
                CancelProductionOrderRequest(order.version, reason)
            )) {
                is ApiResult.Success -> applyOrder(result.data, "Заказ отменён")
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    fun updateCancelReason(value: String) {
        _uiState.value = _uiState.value.copy(cancelReason = value)
    }

    fun updateNoteReason(value: String) {
        _uiState.value = _uiState.value.copy(noteReason = value)
    }

    fun updateSkipReason(value: String) {
        _uiState.value = _uiState.value.copy(skipReason = value)
    }

    // --- Завершение выпуска ---

    fun updateCompleteForm(form: CompleteProductionForm) {
        _uiState.value = _uiState.value.copy(completeForm = form)
    }

    /** Готовит форму завершения: количество по плану и пустой список номеров. */
    fun prepareComplete() {
        val order = _uiState.value.selectedOrder ?: return
        _uiState.value = _uiState.value.copy(
            completeForm = CompleteProductionForm(
                actualQuantity = order.plannedQuantity.toString(),
                serialNumbers = ""
            ),
            operationError = null
        )
        showDialog(ProductionDialog.CompleteOrder)
    }

    fun submitComplete() {
        val order = _uiState.value.selectedOrder ?: return
        val form = _uiState.value.completeForm
        val quantity = form.actualQuantity.trim().toIntOrNull()
        if (quantity == null || quantity <= 0) return fail("Укажите фактическое количество")
        val serials = form.serialNumbers.split('\n', ',', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (serials.size != quantity) {
            return fail("Нужно указать ровно $quantity серийных номеров")
        }
        _uiState.value = _uiState.value.copy(isActionRunning = true, operationError = null)
        viewModelScope.launch {
            when (val result = productionRepository.completeOrder(
                order.id,
                CompleteProductionOrderRequest(order.version, quantity, serials)
            )) {
                is ApiResult.Success -> applyOrder(result.data, "Выпуск завершён, создано единиц: $quantity")
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    // --- Этапы ---

    fun startStage(stageId: Long) {
        val order = _uiState.value.selectedOrder ?: return
        stageAction("Этап запущен") {
            productionRepository.startStage(order.id, stageId, ProductionStageActionRequest(stageVersion(stageId)))
        }
    }

    fun completeStage(stageId: Long) {
        val order = _uiState.value.selectedOrder ?: return
        stageAction("Этап завершён") {
            productionRepository.completeStage(order.id, stageId, ProductionStageActionRequest(stageVersion(stageId)))
        }
    }

    fun submitSkip() {
        val order = _uiState.value.selectedOrder ?: return
        val stageId = _uiState.value.pendingStageSkipId ?: return
        val reason = _uiState.value.skipReason.trim()
        if (reason.isBlank()) return fail("Укажите основание пропуска этапа")
        stageAction("Этап пропущен") {
            productionRepository.skipStage(
                order.id,
                stageId,
                ProductionStageActionRequest(stageVersion(stageId), reason)
            )
        }
    }

    fun openSkipStage(stageId: Long, stageName: String) {
        _uiState.value = _uiState.value.copy(
            activeDialog = ProductionDialog.SkipStage(stageId, stageName),
            pendingStageSkipId = stageId,
            skipReason = ""
        )
    }

    private fun stageVersion(stageId: Long): Long =
        _uiState.value.selectedOrder?.stages?.firstOrNull { it.id == stageId }?.version ?: 0

    private fun stageAction(
        message: String,
        block: suspend () -> ApiResult<ProductionOrderDto>
    ) {
        if (!hasPermission()) return fail("Нет права управлять производством")
        _uiState.value = _uiState.value.copy(isActionRunning = true, operationError = null)
        viewModelScope.launch {
            when (val result = block()) {
                is ApiResult.Success -> applyOrder(result.data, message)
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    private fun orderAction(
        message: String,
        block: suspend (Long, Long) -> ApiResult<ProductionOrderDto>
    ) {
        if (!hasPermission()) return fail("Нет права управлять производством")
        val order = _uiState.value.selectedOrder ?: return
        runOrderAction(message) { block(order.id, order.version) }
    }

    private fun runOrderAction(message: String, block: suspend () -> ApiResult<ProductionOrderDto>) {
        _uiState.value = _uiState.value.copy(isActionRunning = true, operationError = null)
        viewModelScope.launch {
            when (val result = block()) {
                is ApiResult.Success -> applyOrder(result.data, message)
                is ApiResult.Error -> failWith(result.error)
            }
        }
    }

    private fun applyOrder(order: ProductionOrderDto, message: String) {
        _uiState.value = _uiState.value.copy(
            selectedOrder = order,
            isActionRunning = false,
            activeDialog = null,
            cancelReason = "",
            noteReason = "",
            skipReason = "",
            pendingStageSkipId = null,
            completeForm = CompleteProductionForm(),
            successMessage = message
        )
        loadOrders()
        loadInstances(order.id)
    }

    private fun fail(message: String) {
        _uiState.value = _uiState.value.copy(
            operationError = ApiError("VALIDATION_ERROR", message),
            isActionRunning = false
        )
    }

    private fun failWith(error: ApiError) {
        _uiState.value = _uiState.value.copy(operationError = error, isActionRunning = false)
    }

    /** Допустимые действия заказа — те же правила, что и в сервисе. */
    companion object {
        fun canPlan(status: ProductionOrderStatus) = status == ProductionOrderStatus.DRAFT
        fun canStart(status: ProductionOrderStatus) = status == ProductionOrderStatus.PLANNED
        fun canHold(status: ProductionOrderStatus) = status == ProductionOrderStatus.IN_PROGRESS
        fun canResume(status: ProductionOrderStatus) = status == ProductionOrderStatus.ON_HOLD
        fun canComplete(status: ProductionOrderStatus) = status == ProductionOrderStatus.IN_PROGRESS
        fun canClose(status: ProductionOrderStatus) = status == ProductionOrderStatus.COMPLETED
        fun canCancel(status: ProductionOrderStatus) = status in setOf(
            ProductionOrderStatus.DRAFT,
            ProductionOrderStatus.PLANNED,
            ProductionOrderStatus.ON_HOLD
        )

        /** Правки этапов возможны только в работающем или приостановленном заказе. */
        fun canEditStages(status: ProductionOrderStatus) = status == ProductionOrderStatus.IN_PROGRESS ||
            status == ProductionOrderStatus.ON_HOLD

        fun canStartStage(status: ProductionStageStatus) = status == ProductionStageStatus.PENDING
        fun canCompleteStage(status: ProductionStageStatus) = status == ProductionStageStatus.IN_PROGRESS
        fun canSkipStage(status: ProductionStageStatus) = status == ProductionStageStatus.PENDING
    }
}
