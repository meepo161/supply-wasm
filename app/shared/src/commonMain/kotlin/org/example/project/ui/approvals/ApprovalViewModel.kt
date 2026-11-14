package org.example.project.ui.approvals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random
import org.example.project.data.ApiResult
import org.example.project.data.ApprovalRepository
import org.example.project.dto.ApiError
import org.example.project.dto.ApprovalDecision
import org.example.project.dto.ApprovalRouteDto
import org.example.project.dto.CancelApprovalRouteRequest
import org.example.project.dto.StartApprovalRouteRequest
import org.example.project.dto.SubmitApprovalDecisionRequest
import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission

class ApprovalViewModel(
    private val repository: ApprovalRepository,
    private val currentUser: UserDto,
    private val onTargetChanged: () -> Unit
) : ViewModel() {

    private val _uiState = MutableStateFlow(ApprovalUiState())
    val uiState: StateFlow<ApprovalUiState> = _uiState.asStateFlow()

    private var targetType: String = ""
    private var targetId: Long = 0

    fun canStartRoute(): Boolean = currentUser.hasPermission("approvals", "start")

    fun canCancelRoute(): Boolean = currentUser.hasPermission("approvals", "cancel")

    fun load(targetType: String, targetId: Long) {
        this.targetType = targetType
        this.targetId = targetId
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        loadRoutes()
        loadTemplates()
    }

    fun reload() {
        if (targetType.isEmpty()) return
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        loadRoutes()
        loadTemplates()
    }

    private fun loadRoutes() {
        viewModelScope.launch {
            when (val result = repository.listRoutes(targetType, targetId)) {
                is ApiResult.Success -> {
                    val selected = _uiState.value.selectedRoute
                    _uiState.value = _uiState.value.copy(
                        routes = result.data,
                        isLoading = false,
                        error = null,
                        readDenied = false,
                        selectedRoute = selected?.let { sel -> result.data.firstOrNull { it.id == sel.id } }
                    )
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.error,
                    readDenied = result.statusCode == 403 || result.statusCode == 404
                )
            }
        }
    }

    private fun loadTemplates() {
        viewModelScope.launch {
            when (val result = repository.listTemplates(targetType)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    templates = result.data.filter { it.active },
                    templatesLoaded = true
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(templatesLoaded = true)
            }
        }
    }

    fun selectRoute(route: ApprovalRouteDto) {
        _uiState.value = _uiState.value.copy(
            selectedRoute = route,
            error = null,
            decisionComment = "",
            cancelDialogOpen = false,
            cancelReason = ""
        )
        viewModelScope.launch {
            when (val result = repository.getRoute(route.id)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(selectedRoute = result.data)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(error = result.error)
            }
        }
    }

    fun selectTemplate(templateId: Long) {
        _uiState.value = _uiState.value.copy(selectedTemplateId = templateId, error = null)
    }

    fun updateDecisionComment(value: String) {
        _uiState.value = _uiState.value.copy(decisionComment = value)
    }

    fun updateCancelReason(value: String) {
        _uiState.value = _uiState.value.copy(cancelReason = value)
    }

    fun showCancelDialog() {
        _uiState.value = _uiState.value.copy(cancelDialogOpen = true, error = null)
    }

    fun dismissCancelDialog() {
        _uiState.value = _uiState.value.copy(cancelDialogOpen = false, cancelReason = "")
    }

    fun dismissError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    fun startRoute() {
        if (!canStartRoute()) return
        val templateId = _uiState.value.selectedTemplateId
        if (templateId == null) {
            _uiState.value = _uiState.value.copy(
                error = ApiError("VALIDATION_ERROR", "Выберите шаблон маршрута")
            )
            return
        }
        if (_uiState.value.routes.activeRoute() != null) {
            _uiState.value = _uiState.value.copy(
                error = ApiError("VALIDATION_ERROR", "У объекта уже есть активный маршрут согласования")
            )
            return
        }
        val idempotencyKey = newIdempotencyKey()
        _uiState.value = _uiState.value.copy(isStarting = true, error = null)
        viewModelScope.launch {
            when (val result = repository.startRoute(
                StartApprovalRouteRequest(
                    templateId = templateId,
                    targetType = targetType,
                    targetId = targetId,
                    idempotencyKey = idempotencyKey
                )
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isStarting = false,
                        selectedTemplateId = null,
                        selectedRoute = result.data,
                        error = null
                    )
                    reload()
                    onTargetChanged()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isStarting = false,
                    error = result.error
                )
            }
        }
    }

    fun submitDecision(decision: ApprovalDecision) {
        val route = _uiState.value.selectedRoute ?: return
        if (!canUserDecideCurrentStage(route, currentUser.id)) return
        val comment = _uiState.value.decisionComment.trim()
        if (decision.requiresComment() && comment.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                error = ApiError("VALIDATION_ERROR", "Комментарий обязателен при отклонении или возврате")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isSubmitting = true, error = null)
        viewModelScope.launch {
            when (val result = repository.submitDecision(
                route.id,
                SubmitApprovalDecisionRequest(decision, comment.takeIf { it.isNotEmpty() })
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isSubmitting = false,
                        selectedRoute = result.data,
                        decisionComment = "",
                        error = null
                    )
                    reload()
                    onTargetChanged()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(isSubmitting = false, error = result.error)
                    if (result.statusCode == 409) refreshSelectedRoute()
                }
            }
        }
    }

    fun cancelRoute() {
        if (!canCancelRoute()) return
        val route = _uiState.value.selectedRoute ?: return
        val reason = _uiState.value.cancelReason.trim()
        if (reason.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                error = ApiError("VALIDATION_ERROR", "Укажите причину отмены маршрута")
            )
            return
        }
        _uiState.value = _uiState.value.copy(isCancelling = true, error = null)
        viewModelScope.launch {
            when (val result = repository.cancelRoute(route.id, CancelApprovalRouteRequest(reason))) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isCancelling = false,
                        selectedRoute = result.data,
                        cancelDialogOpen = false,
                        cancelReason = "",
                        error = null
                    )
                    reload()
                    onTargetChanged()
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(isCancelling = false, error = result.error)
                    if (result.statusCode == 409) refreshSelectedRoute()
                }
            }
        }
    }

    private fun refreshSelectedRoute() {
        val routeId = _uiState.value.selectedRoute?.id ?: return
        viewModelScope.launch {
            when (val result = repository.getRoute(routeId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(selectedRoute = result.data)
                is ApiResult.Error -> { }
            }
        }
    }

    private fun newIdempotencyKey(): String =
        "ui-${Random.nextLong().toString(16)}-${Random.nextLong().toString(16)}"
}
