package org.example.project.ui.bom

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.BomRepository
import org.example.project.data.WmsRepository
import org.example.project.dto.ApproveBomRevisionRequest
import org.example.project.dto.BomExplodeRequest
import org.example.project.dto.BomRevisionStatus
import org.example.project.dto.CopyBomRevisionRequest
import org.example.project.dto.CreateBomLineRequest
import org.example.project.dto.CreateBomRevisionRequest
import org.example.project.dto.CreateProductModelRequest
import org.example.project.dto.ProductModelDto
import org.example.project.dto.TransitionBomRevisionRequest
import org.example.project.dto.UpdateBomLineRequest
import org.example.project.dto.UpdateProductModelRequest
import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission

class BomViewModel(
    private val bomRepository: BomRepository,
    private val wmsRepository: WmsRepository,
    private val currentUser: UserDto
) : ViewModel() {

    private val _uiState = MutableStateFlow(BomUiState())
    val uiState: StateFlow<BomUiState> = _uiState.asStateFlow()

    init {
        loadModels()
        loadNomenclature()
        loadWarehouses()
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(operationError = null)
    }

    fun showDialog(dialog: BomDialog) {
        when (dialog) {
            is BomDialog.EditModel -> _uiState.value = _uiState.value.copy(
                activeDialog = dialog,
                editModelForm = CreateProductModelForm(
                    code = dialog.model.code,
                    name = dialog.model.name,
                    description = dialog.model.description ?: "",
                    status = dialog.model.status,
                    designerId = dialog.model.designerId?.toString() ?: "",
                    technologistId = dialog.model.technologistId?.toString() ?: ""
                ),
                operationError = null
            )
            is BomDialog.EditLine -> _uiState.value = _uiState.value.copy(
                activeDialog = dialog,
                editLineForm = EditLineForm(
                    lineId = dialog.line.id,
                    quantity = dialog.line.quantity,
                    unit = dialog.line.unit,
                    position = dialog.line.position ?: "",
                    level = dialog.line.level.toString(),
                    mandatory = dialog.line.mandatory,
                    consumptionRate = dialog.line.consumptionRate ?: "",
                    lossPct = dialog.line.lossPct
                ),
                operationError = null
            )
            is BomDialog.CopyRevision -> _uiState.value = _uiState.value.copy(
                activeDialog = dialog,
                copyRevisionForm = CopyRevisionForm(),
                operationError = null
            )
            else -> _uiState.value = _uiState.value.copy(activeDialog = dialog, operationError = null)
        }
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(activeDialog = null, operationError = null)
    }

    fun navigateTo(page: BomPage) {
        _uiState.value = _uiState.value.copy(
            currentPage = page,
            operationError = null,
            explodeReport = null
        )
    }

    fun goBack() {
        when (_uiState.value.currentPage) {
            BomPage.MODELS -> { }
            BomPage.MODEL_DETAIL -> navigateTo(BomPage.MODELS)
            BomPage.REVISION_DETAIL -> navigateTo(BomPage.MODEL_DETAIL)
            BomPage.EXPLODE -> navigateTo(BomPage.REVISION_DETAIL)
        }
    }

    fun selectModel(model: ProductModelDto?) {
        _uiState.value = _uiState.value.copy(
            selectedModel = model,
            selectedRevision = null,
            revisions = emptyList(),
            revisionLines = emptyList(),
            explodeReport = null,
            operationError = null
        )
        model?.let {
            loadRevisions(it.id)
            navigateTo(BomPage.MODEL_DETAIL)
        }
    }

    fun selectRevision(revisionId: Long) {
        _uiState.value = _uiState.value.copy(
            selectedRevision = null,
            revisionLines = emptyList(),
            explodeReport = null,
            operationError = null
        )
        viewModelScope.launch {
            when (val result = bomRepository.getRevision(revisionId)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(selectedRevision = result.data)
                    loadLines(revisionId)
                    navigateTo(BomPage.REVISION_DETAIL)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    fun refreshCurrentRevision() {
        val revision = _uiState.value.selectedRevision ?: return
        selectRevision(revision.id)
    }

    fun hasPermission(resource: String, action: String): Boolean =
        currentUser.hasPermission(resource, action)

    fun loadModels() {
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = bomRepository.listModels()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    models = result.data,
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

    fun loadNomenclature() {
        viewModelScope.launch {
            when (val result = wmsRepository.listNomenclature()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(nomenclature = result.data)
                is ApiResult.Error -> { }
            }
        }
    }

    fun loadWarehouses() {
        viewModelScope.launch {
            when (val result = wmsRepository.listWarehouses()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(warehouses = result.data)
                is ApiResult.Error -> { }
            }
        }
    }

    private fun loadRevisions(modelId: Long) {
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = bomRepository.listRevisions(modelId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    revisions = result.data,
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

    private fun loadLines(revisionId: Long) {
        viewModelScope.launch {
            when (val result = bomRepository.listLines(revisionId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(revisionLines = result.data)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    fun createModel() {
        if (!hasPermission("bom", "create")) return
        val form = _uiState.value.createModelForm
        if (form.code.isBlank() || form.name.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = org.example.project.dto.ApiError("VALIDATION_ERROR", "Код и название обязательны")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = bomRepository.createModel(
                CreateProductModelRequest(
                    code = form.code.trim(),
                    name = form.name.trim(),
                    description = form.description.takeIf { it.isNotBlank() }?.trim(),
                    status = form.status,
                    designerId = form.designerId.toLongOrNull(),
                    technologistId = form.technologistId.toLongOrNull()
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createModelForm = CreateProductModelForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadModels()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateModel() {
        if (!hasPermission("bom", "update")) return
        val model = (_uiState.value.activeDialog as? BomDialog.EditModel)?.model ?: return
        val form = _uiState.value.editModelForm
        if (form.name.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = org.example.project.dto.ApiError("VALIDATION_ERROR", "Название обязательно")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = bomRepository.updateModel(
                model.id,
                UpdateProductModelRequest(
                    name = form.name.trim().takeIf { it.isNotBlank() },
                    description = form.description.takeIf { it.isNotBlank() }?.trim(),
                    status = form.status,
                    designerId = form.designerId.toLongOrNull(),
                    technologistId = form.technologistId.toLongOrNull(),
                    version = model.version
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        activeDialog = null,
                        isLoading = false,
                        selectedModel = result.data
                    )
                    loadModels()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun createRevision() {
        if (!hasPermission("bom", "create")) return
        val modelId = _uiState.value.selectedModel?.id ?: return
        val form = _uiState.value.createRevisionForm
        if (form.revisionNumber.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = org.example.project.dto.ApiError("VALIDATION_ERROR", "Номер ревизии обязателен")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = bomRepository.createRevision(
                modelId,
                CreateBomRevisionRequest(
                    revisionNumber = form.revisionNumber.trim(),
                    changeReason = form.changeReason.takeIf { it.isNotBlank() }?.trim()
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createRevisionForm = CreateRevisionForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadRevisions(modelId)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun copyRevision() {
        if (!hasPermission("bom", "create")) return
        val revisionId = (_uiState.value.activeDialog as? BomDialog.CopyRevision)?.revision?.id ?: return
        val modelId = _uiState.value.selectedModel?.id ?: return
        val form = _uiState.value.copyRevisionForm
        if (form.revisionNumber.isBlank() || form.reason.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = org.example.project.dto.ApiError(
                    "VALIDATION_ERROR",
                    "Номер ревизии и причина копирования обязательны"
                )
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = bomRepository.copyRevision(
                revisionId,
                CopyBomRevisionRequest(
                    revisionNumber = form.revisionNumber.trim(),
                    reason = form.reason.trim()
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        copyRevisionForm = CopyRevisionForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadRevisions(modelId)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun approveRevision() {
        if (!hasPermission("bom", "approve")) return
        val revision = _uiState.value.selectedRevision ?: return
        if (revision.status != BomRevisionStatus.DRAFT) return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = bomRepository.approveRevision(
                revision.id,
                ApproveBomRevisionRequest(revision.version)
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        selectedRevision = result.data,
                        isLoading = false
                    )
                    refreshRevisions()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun activateRevision() {
        if (!hasPermission("bom", "approve")) return
        val revision = _uiState.value.selectedRevision ?: return
        if (revision.status != BomRevisionStatus.APPROVED) return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = bomRepository.activateRevision(
                revision.id,
                TransitionBomRevisionRequest(revision.version)
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        selectedRevision = result.data,
                        isLoading = false
                    )
                    refreshRevisions()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun replaceRevision() {
        if (!hasPermission("bom", "approve")) return
        val revision = _uiState.value.selectedRevision ?: return
        if (revision.status != BomRevisionStatus.ACTIVE) return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = bomRepository.replaceRevision(
                revision.id,
                TransitionBomRevisionRequest(revision.version)
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        selectedRevision = result.data,
                        isLoading = false
                    )
                    refreshRevisions()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun cancelRevision() {
        if (!hasPermission("bom", "delete_draft")) return
        val revision = _uiState.value.selectedRevision ?: return
        if (revision.status != BomRevisionStatus.DRAFT) return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = bomRepository.cancelRevision(
                revision.id,
                TransitionBomRevisionRequest(revision.version)
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        selectedRevision = result.data,
                        isLoading = false
                    )
                    refreshRevisions()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    private fun refreshRevisions() {
        _uiState.value.selectedModel?.let { loadRevisions(it.id) }
    }

    fun refreshCurrentModel() {
        _uiState.value.selectedModel?.let { loadRevisions(it.id) }
    }

    fun createLine() {
        if (!hasPermission("bom", "update")) return
        val revision = _uiState.value.selectedRevision ?: return
        if (revision.status.isReadOnly()) return
        val form = _uiState.value.createLineForm
        if (form.parentNomenclatureId == null || form.childNomenclatureId == null || form.quantity.isBlank() || form.unit.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = org.example.project.dto.ApiError(
                    "VALIDATION_ERROR",
                    "Родительская/дочерняя номенклатура, количество и единица обязательны"
                )
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = bomRepository.createLine(
                revision.id,
                CreateBomLineRequest(
                    parentLineId = form.parentLineId.toLongOrNull(),
                    parentNomenclatureId = form.parentNomenclatureId,
                    childNomenclatureId = form.childNomenclatureId,
                    quantity = form.quantity.trim(),
                    unit = form.unit.trim(),
                    position = form.position.takeIf { it.isNotBlank() }?.trim(),
                    level = form.level.toIntOrNull(),
                    mandatory = form.mandatory,
                    substituteOfLineId = form.substituteOfLineId.toLongOrNull(),
                    consumptionRate = form.consumptionRate.takeIf { it.isNotBlank() }?.trim(),
                    lossPct = form.lossPct.takeIf { it.isNotBlank() }?.trim()
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createLineForm = CreateLineForm(),
                        activeDialog = null,
                        isLoading = false
                    )
                    loadLines(revision.id)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateLine() {
        if (!hasPermission("bom", "update")) return
        val revision = _uiState.value.selectedRevision ?: return
        if (revision.status.isReadOnly()) return
        val form = _uiState.value.editLineForm
        if (form.quantity.isBlank() || form.unit.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = org.example.project.dto.ApiError("VALIDATION_ERROR", "Количество и единица обязательны")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = bomRepository.updateLine(
                revision.id,
                form.lineId,
                UpdateBomLineRequest(
                    quantity = form.quantity.trim(),
                    unit = form.unit.trim(),
                    position = form.position.takeIf { it.isNotBlank() }?.trim(),
                    level = form.level.toIntOrNull(),
                    mandatory = form.mandatory,
                    consumptionRate = form.consumptionRate.takeIf { it.isNotBlank() }?.trim(),
                    lossPct = form.lossPct.takeIf { it.isNotBlank() }?.trim()
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        activeDialog = null,
                        isLoading = false
                    )
                    loadLines(revision.id)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun deleteLine(line: org.example.project.dto.BomLineDto) {
        if (!hasPermission("bom", "update")) return
        val revision = _uiState.value.selectedRevision ?: return
        if (revision.status.isReadOnly()) return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = bomRepository.deleteLine(revision.id, line.id)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    loadLines(revision.id)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun explodeRevision() {
        if (!hasPermission("bom", "explode")) return
        val revision = _uiState.value.selectedRevision ?: return
        val form = _uiState.value.explodeForm
        if (form.releaseQuantity.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = org.example.project.dto.ApiError("VALIDATION_ERROR", "Количество выпуска обязательно")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = bomRepository.explodeRevision(
                revision.id,
                BomExplodeRequest(
                    releaseQuantity = form.releaseQuantity.trim(),
                    warehouseId = form.warehouseId
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        explodeReport = result.data,
                        activeDialog = null,
                        isLoading = false
                    )
                    navigateTo(BomPage.EXPLODE)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    operationError = result.error
                )
            }
        }
    }

    fun updateCreateModelForm(form: CreateProductModelForm) {
        _uiState.value = _uiState.value.copy(createModelForm = form)
    }

    fun updateEditModelForm(form: CreateProductModelForm) {
        _uiState.value = _uiState.value.copy(editModelForm = form)
    }

    fun updateCreateRevisionForm(form: CreateRevisionForm) {
        _uiState.value = _uiState.value.copy(createRevisionForm = form)
    }

    fun updateCopyRevisionForm(form: CopyRevisionForm) {
        _uiState.value = _uiState.value.copy(copyRevisionForm = form)
    }

    fun updateCreateLineForm(form: CreateLineForm) {
        _uiState.value = _uiState.value.copy(createLineForm = form)
    }

    fun updateEditLineForm(form: EditLineForm) {
        _uiState.value = _uiState.value.copy(editLineForm = form)
    }

    fun updateExplodeForm(form: ExplodeForm) {
        _uiState.value = _uiState.value.copy(explodeForm = form)
    }
}
