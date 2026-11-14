package org.example.project.ui.demand

import org.example.project.dto.ApiError
import org.example.project.dto.DemandCoverageDto
import org.example.project.dto.DemandDetailDto
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.DemandPriority
import org.example.project.dto.DemandSourceType
import org.example.project.dto.DemandStatus
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ProductModelDto
import org.example.project.dto.ProjectDto
import org.example.project.dto.BomRevisionSummaryDto

data class DemandUiState(
    val demands: List<DemandHeaderDto> = emptyList(),
    val selectedDemand: DemandDetailDto? = null,
    val coverage: DemandCoverageDto? = null,
    val projects: List<ProjectDto> = emptyList(),
    val models: List<ProductModelDto> = emptyList(),
    val revisions: List<BomRevisionSummaryDto> = emptyList(),
    val nomenclature: List<NomenclatureDto> = emptyList(),
    val currentPage: DemandPage = DemandPage.LIST,
    val isLoading: Boolean = false,
    val operationError: ApiError? = null,
    val activeDialog: DemandDialog? = null,
    val createDemandForm: CreateDemandForm = CreateDemandForm(),
    val cancelReason: String = ""
)

enum class DemandPage(val title: String) {
    LIST("Потребности"),
    DETAIL("Карточка потребности"),
    COVERAGE("Покрытие потребности")
}

sealed class DemandDialog {
    data object CreateDemand : DemandDialog()
    data object CancelDemand : DemandDialog()
}

data class CreateDemandForm(
    val sourceType: DemandSourceType = DemandSourceType.MANUAL,
    val projectId: Long? = null,
    val modelId: Long? = null,
    val revisionId: Long? = null,
    val releaseQuantity: String = "1",
    val stockpileNote: String = "",
    val requiredDate: String = "",
    val priority: DemandPriority = DemandPriority.NORMAL,
    val lines: List<CreateDemandLineForm> = listOf(CreateDemandLineForm(lineNo = 1))
)

data class CreateDemandLineForm(
    val lineNo: Int,
    val nomenclatureId: Long? = null,
    val quantity: String = "",
    val unit: String = ""
)

fun DemandStatus.canSubmit(): Boolean = this == DemandStatus.DRAFT
fun DemandStatus.canApprove(): Boolean = this == DemandStatus.PENDING_APPROVAL
fun DemandStatus.canCancel(): Boolean = this == DemandStatus.DRAFT || this == DemandStatus.PENDING_APPROVAL
