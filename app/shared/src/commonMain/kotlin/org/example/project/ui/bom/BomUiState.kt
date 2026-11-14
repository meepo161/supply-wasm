package org.example.project.ui.bom

import org.example.project.dto.ApiError
import org.example.project.dto.BomExplodeReportDto
import org.example.project.dto.BomLineDto
import org.example.project.dto.BomRevisionDto
import org.example.project.dto.BomRevisionStatus
import org.example.project.dto.BomRevisionSummaryDto
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ProductModelDto
import org.example.project.dto.ProductModelStatus
import org.example.project.dto.UserOptionDto
import org.example.project.dto.WarehouseDto

data class BomUiState(
    val models: List<ProductModelDto> = emptyList(),
    val selectedModel: ProductModelDto? = null,
    val revisions: List<BomRevisionSummaryDto> = emptyList(),
    val selectedRevision: BomRevisionDto? = null,
    val revisionLines: List<BomLineDto> = emptyList(),
    val explodeReport: BomExplodeReportDto? = null,
    val nomenclature: List<NomenclatureDto> = emptyList(),
    val warehouses: List<WarehouseDto> = emptyList(),
    val users: List<UserOptionDto> = emptyList(),
    val currentPage: BomPage = BomPage.MODELS,
    val isLoading: Boolean = false,
    val operationError: ApiError? = null,
    val activeDialog: BomDialog? = null,
    val createModelForm: CreateProductModelForm = CreateProductModelForm(),
    val editModelForm: CreateProductModelForm = CreateProductModelForm(),
    val createRevisionForm: CreateRevisionForm = CreateRevisionForm(),
    val copyRevisionForm: CopyRevisionForm = CopyRevisionForm(),
    val createLineForm: CreateLineForm = CreateLineForm(),
    val editLineForm: EditLineForm = EditLineForm(),
    val explodeForm: ExplodeForm = ExplodeForm()
)

enum class BomPage(val title: String) {
    MODELS("Модели изделий"),
    MODEL_DETAIL("Ревизии"),
    REVISION_DETAIL("Карточка ревизии"),
    EXPLODE("Разузлование")
}

sealed class BomDialog {
    data object CreateModel : BomDialog()
    data class EditModel(val model: ProductModelDto) : BomDialog()
    data object CreateRevision : BomDialog()
    data class CopyRevision(val revision: BomRevisionSummaryDto) : BomDialog()
    data object CreateLine : BomDialog()
    data class EditLine(val line: BomLineDto) : BomDialog()
    data object Explode : BomDialog()
}

data class CreateProductModelForm(
    val code: String = "",
    val name: String = "",
    val description: String = "",
    val status: ProductModelStatus = ProductModelStatus.DEVELOPMENT,
    val designerId: Long? = null,
    val technologistId: Long? = null
)

data class CreateRevisionForm(
    val revisionNumber: String = "",
    val changeReason: String = ""
)

data class CopyRevisionForm(
    val revisionNumber: String = "",
    val reason: String = ""
)

data class CreateLineForm(
    val parentLineId: String = "",
    val parentNomenclatureId: Long? = null,
    val childNomenclatureId: Long? = null,
    val quantity: String = "",
    val unit: String = "",
    val position: String = "",
    val level: String = "1",
    val mandatory: Boolean = true,
    val substituteOfLineId: String = "",
    val consumptionRate: String = "",
    val lossPct: String = "0"
)

data class EditLineForm(
    val lineId: Long = 0,
    val quantity: String = "",
    val unit: String = "",
    val position: String = "",
    val level: String = "1",
    val mandatory: Boolean = true,
    val consumptionRate: String = "",
    val lossPct: String = "0"
)

data class ExplodeForm(
    val releaseQuantity: String = "1",
    val warehouseId: Long? = null
)

fun BomRevisionStatus.isReadOnly(): Boolean = this != BomRevisionStatus.DRAFT
