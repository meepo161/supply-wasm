package org.example.project.ui.demand

import org.example.project.data.AttachmentFileData
import org.example.project.dto.ApiError
import org.example.project.dto.BomRevisionSummaryDto
import org.example.project.dto.DemandDetailDto
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.DemandPriority
import org.example.project.dto.DemandSourceType
import org.example.project.dto.DemandStatus
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ProductModelDto
import org.example.project.dto.ProjectDto
import org.example.project.dto.SupplierDto

data class DemandUiState(
    val demands: List<DemandHeaderDto> = emptyList(),
    val selectedDemand: DemandDetailDto? = null,
    val projects: List<ProjectDto> = emptyList(),
    val models: List<ProductModelDto> = emptyList(),
    val revisions: List<BomRevisionSummaryDto> = emptyList(),
    val nomenclature: List<NomenclatureDto> = emptyList(),
    val suppliers: List<SupplierDto> = emptyList(),
    val offersCountByDemand: Map<Long, Int> = emptyMap(),
    val offersCountLoaded: Boolean = false,
    val stageFilter: DemandStageFilter = DemandStageFilter.ALL,
    val projectFilterId: Long? = null,
    val priorityFilter: DemandPriorityFilter = DemandPriorityFilter.ALL,
    val sortMode: DemandSortMode = DemandSortMode.NEWEST,
    val searchQuery: String = "",
    val currentPage: DemandPage = DemandPage.LIST,
    val isLoading: Boolean = false,
    val isActionRunning: Boolean = false,
    val operationError: ApiError? = null,
    val successMessage: String? = null,
    val activeDialog: DemandDialog? = null,
    val createDemandForm: CreateDemandForm = CreateDemandForm(),
    val createOfferForm: DemandCreateOfferForm = DemandCreateOfferForm(),
    val cancelReason: String = "",
    val paymentExpectedAt: String = "",
    val paymentNote: String = ""
)

enum class DemandStageFilter(val label: String) {
    ALL("Все"),
    WAITING_OFFERS("Ждут предложений"),
    WAITING_SELECTION("Ждут выбора ведущего"),
    WAITING_PAYMENT("Ждут заказа"),
    IN_TRANSIT("В пути на склад"),
    CLOSED("На складе / Закрыты"),
    CANCELLED("Отменённые")
}

enum class DemandPriorityFilter(val label: String) {
    ALL("Все приоритеты"),
    URGENT("Срочные / Крит."),
    AT_RISK("Горят / просрочены")
}

enum class DemandSortMode(val label: String) {
    NEWEST("Сначала новые"),
    REQUIRED_DATE("По сроку"),
    PRIORITY("По приоритету"),
    AMOUNT_DESC("По сумме")
}

enum class DemandPage(val title: String) {
    LIST("Снабжение (Заявки)"),
    DETAIL("Пульт заявки")
}

sealed class DemandDialog {
    data object CreateDemand : DemandDialog()
    data object CancelDemand : DemandDialog()
    data object CreateOffer : DemandDialog()
}

data class CreateDemandForm(
    val sourceType: DemandSourceType = DemandSourceType.MANUAL,
    val projectId: Long? = null,
    val useBomForProject: Boolean = false,
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

data class DemandOfferLineForm(
    val demandLineId: Long,
    val nomenclatureId: Long,
    val label: String,
    val unit: String,
    val deficit: String,
    val quantity: String,
    val unitPrice: String = "",
    val vatRate: String = "20",
    val included: Boolean = true
)

data class DemandCreateOfferForm(
    val demandId: Long? = null,
    val demandNumber: String = "",
    val supplierId: Long? = null,
    val deliveryDays: String = "",
    val paymentTerms: String = "",
    val validUntil: String = "",
    val note: String = "",
    val pendingInvoiceFile: AttachmentFileData? = null,
    val invoiceDocumentId: Long? = null,
    val invoiceFileName: String? = null,
    val invoiceNumber: String = "",
    val invoiceDate: String = "",
    val lines: List<DemandOfferLineForm> = emptyList()
) {
    val hasInvoiceFile: Boolean get() = pendingInvoiceFile != null || invoiceDocumentId != null
    val invoiceFileLabel: String? get() = pendingInvoiceFile?.fileName ?: invoiceFileName
}

fun DemandStatus.canSubmit(): Boolean = this == DemandStatus.DRAFT
fun DemandStatus.canApprove(): Boolean = this == DemandStatus.PENDING_APPROVAL
fun DemandStatus.canCancel(): Boolean =
    this == DemandStatus.DRAFT || this == DemandStatus.PENDING_APPROVAL || this == DemandStatus.APPROVED
