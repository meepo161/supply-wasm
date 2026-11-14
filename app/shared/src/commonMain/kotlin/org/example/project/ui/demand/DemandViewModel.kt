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
import org.example.project.data.ProjectRepository
import org.example.project.data.WmsRepository
import org.example.project.dto.ApproveDemandRequest
import org.example.project.dto.CancelDemandRequest
import org.example.project.dto.CreateDemandFromBomRequest
import org.example.project.dto.CreateDemandRequest
import org.example.project.dto.DemandLineInput
import org.example.project.dto.DemandSourceType
import org.example.project.dto.DemandStatus
import org.example.project.dto.SubmitDemandRequest
import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission
import org.example.project.dto.ApiError

class DemandViewModel(
    private val demandRepository: DemandRepository,
    private val wmsRepository: WmsRepository,
    private val projectRepository: ProjectRepository,
    private val bomRepository: BomRepository,
    private val currentUser: UserDto
) : ViewModel() {

    private val _uiState = MutableStateFlow(DemandUiState())
    val uiState: StateFlow<DemandUiState> = _uiState.asStateFlow()

    init {
        loadDemands()
        loadProjects()
        loadModels()
        loadNomenclature()
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(operationError = null)
    }

    fun showDialog(dialog: DemandDialog) {
        _uiState.value = _uiState.value.copy(activeDialog = dialog, operationError = null)
    }

    fun dismissDialog() {
        _uiState.value = _uiState.value.copy(activeDialog = null, operationError = null, cancelReason = "")
    }

    fun navigateTo(page: DemandPage) {
        _uiState.value = _uiState.value.copy(currentPage = page, operationError = null)
    }

    fun goBack() {
        when (_uiState.value.currentPage) {
            DemandPage.LIST -> { }
            DemandPage.DETAIL -> navigateTo(DemandPage.LIST)
            DemandPage.COVERAGE -> navigateTo(DemandPage.DETAIL)
        }
    }

    fun hasPermission(action: String): Boolean = currentUser.hasPermission("demands", action)

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

    private fun loadNomenclature() {
        viewModelScope.launch {
            when (val result = wmsRepository.listNomenclature()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(nomenclature = result.data)
                is ApiResult.Error -> { }
            }
        }
    }

    fun selectDemand(id: Long) {
        _uiState.value = _uiState.value.copy(selectedDemand = null, coverage = null, operationError = null)
        viewModelScope.launch {
            when (val result = demandRepository.getDemand(id)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(selectedDemand = result.data)
                    navigateTo(DemandPage.DETAIL)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(operationError = result.error)
            }
        }
    }

    fun refreshSelectedDemand() {
        val id = _uiState.value.selectedDemand?.header?.id ?: return
        selectDemand(id)
    }

    fun loadCoverage() {
        val id = _uiState.value.selectedDemand?.header?.id ?: return
        _uiState.value = _uiState.value.copy(isLoading = true, operationError = null)
        viewModelScope.launch {
            when (val result = demandRepository.coveragePreview(id)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    coverage = result.data,
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

    fun showCoverage() {
        loadCoverage()
        navigateTo(DemandPage.COVERAGE)
    }

    fun createDemand() {
        if (!hasPermission("create")) return
        val form = _uiState.value.createDemandForm
        when (form.sourceType) {
            DemandSourceType.PROJECT -> createDemandFromBom(form)
            DemandSourceType.STOCKPILE -> createManualOrStockpile(form)
            DemandSourceType.MANUAL -> createManualOrStockpile(form)
        }
    }

    private fun createManualOrStockpile(form: CreateDemandForm) {
        if (form.sourceType == DemandSourceType.STOCKPILE && form.stockpileNote.isBlank()) {
            _uiState.value = _uiState.value.copy(
                operationError = ApiError("VALIDATION_ERROR", "Примечание к заделу обязательно")
            )
            return
        }
        val validLines = form.lines.mapIndexedNotNull { index, line ->
            val nomId = line.nomenclatureId
            val qty = line.quantity
            if (nomId == null || qty.isBlank() || line.unit.isBlank()) {
                _uiState.value = _uiState.value.copy(
                    operationError = ApiError("VALIDATION_ERROR", "В строке ${index + 1} не заполнены номенклатура, количество или единица")
                )
                return
            }
            DemandLineInput(
                lineNo = line.lineNo,
                nomenclatureId = nomId,
                quantity = qty.trim(),
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
                    projectId = null,
                    stockpileNote = form.stockpileNote.takeIf { it.isNotBlank() }?.trim(),
                    requiredDate = form.requiredDate.takeIf { it.isNotBlank() }?.trim(),
                    priority = form.priority,
                    lines = validLines
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createDemandForm = CreateDemandForm(),
                        activeDialog = null,
                        isLoading = false
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
                    releaseQuantity = form.releaseQuantity.trim(),
                    requiredDate = form.requiredDate.takeIf { it.isNotBlank() }?.trim(),
                    priority = form.priority
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createDemandForm = CreateDemandForm(),
                        activeDialog = null,
                        isLoading = false
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
                        isLoading = false
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
}
