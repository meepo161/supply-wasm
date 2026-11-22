package org.example.project.ui.production

import org.example.project.dto.ApiError
import org.example.project.dto.BomRevisionSummaryDto
import org.example.project.dto.EngineeringChangeImpactDto
import org.example.project.dto.NonconformityDto
import org.example.project.dto.ProductInstanceDetailDto
import org.example.project.dto.ProductInstanceDto
import org.example.project.dto.ProductModelDto
import org.example.project.dto.ProductionOrderDto
import org.example.project.dto.ProductionOrderMaterialDto
import org.example.project.dto.ProductionOrderStatus
import org.example.project.dto.ProductionStageType
import org.example.project.dto.ProjectDto
import org.example.project.dto.UserDto

data class ProductionUiState(
    val orders: List<ProductionOrderDto> = emptyList(),
    val selectedOrder: ProductionOrderDto? = null,
    val instances: List<ProductInstanceDto> = emptyList(),
    val materials: List<ProductionOrderMaterialDto> = emptyList(),
    val engineeringChanges: List<EngineeringChangeImpactDto> = emptyList(),
    val nonconformities: List<NonconformityDto> = emptyList(),
    val selectedInstanceDetail: ProductInstanceDetailDto? = null,
    val isInstanceDetailLoading: Boolean = false,
    val instanceTestResult: String = "GOOD",
    val instanceTestDecision: String = "PENDING",
    val instanceTestNotes: String = "",
    val instanceRemarkDescription: String = "",
    val instanceRemarkSeverity: String = "MINOR",
    val instanceSoftwareName: String = "",
    val instanceSoftwareVersion: String = "",
    val instanceSoftwareBuild: String = "",
    val projects: List<ProjectDto> = emptyList(),
    val models: List<ProductModelDto> = emptyList(),
    val revisions: List<BomRevisionSummaryDto> = emptyList(),
    val users: List<UserDto> = emptyList(),
    val statusFilter: ProductionOrderStatus? = null,
    val projectFilter: Long? = null,
    val isLoading: Boolean = false,
    val isOrdersLoading: Boolean = false,
    val isActionRunning: Boolean = false,
    // Права на справочники BOM могут отсутствовать у производственного
    // пользователя — тогда форма создания заказа недоступна не «молча».
    val modelsAccessDenied: Boolean = false,
    val operationError: ApiError? = null,
    val successMessage: String? = null,
    val activeDialog: ProductionDialog? = null,
    val createForm: CreateProductionOrderForm = CreateProductionOrderForm(),
    val completeForm: CompleteProductionForm = CompleteProductionForm(),
    val cancelReason: String = "",
    val skipReason: String = "",
    val noteReason: String = "",
    val pendingStageSkipId: Long? = null
)

sealed class ProductionDialog {
    data object CreateOrder : ProductionDialog()
    data object CancelOrder : ProductionDialog()
    data object CompleteOrder : ProductionDialog()
    data object HoldOrder : ProductionDialog()
    data class SkipStage(val stageId: Long, val stageName: String) : ProductionDialog()
    data class InstanceDetail(val instanceId: Long) : ProductionDialog()
    data object CreateEngineeringChange : ProductionDialog()
    data object CreateNonconformity : ProductionDialog()
}

data class CreateProductionOrderForm(
    val projectId: Long? = null,
    val modelId: Long? = null,
    val revisionId: Long? = null,
    val plannedQuantity: String = "1",
    val plannedStartAt: String = "",
    val plannedEndAt: String = "",
    val responsibleId: Long? = null,
    val departmentId: Long? = null,
    val priority: String = "NORMAL",
    val stageTypes: List<ProductionStageType?> = listOf(ProductionStageType.ASSEMBLY)
)

data class CompleteProductionForm(
    val actualQuantity: String = "",
    val serialNumbers: String = ""
)
