package org.example.project.ui.approvals

import org.example.project.dto.ApiError
import org.example.project.dto.ApprovalRouteDto
import org.example.project.dto.ApprovalTemplateDto

data class ApprovalUiState(
    val routes: List<ApprovalRouteDto> = emptyList(),
    val selectedRoute: ApprovalRouteDto? = null,
    val templates: List<ApprovalTemplateDto> = emptyList(),
    val templatesLoaded: Boolean = false,
    val selectedTemplateId: Long? = null,
    val isLoading: Boolean = false,
    val isStarting: Boolean = false,
    val isSubmitting: Boolean = false,
    val isCancelling: Boolean = false,
    val error: ApiError? = null,
    val readDenied: Boolean = false,
    val decisionComment: String = "",
    val cancelReason: String = "",
    val cancelDialogOpen: Boolean = false
)
