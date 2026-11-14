package org.example.project.dto

import kotlinx.serialization.Serializable

// Количества передаются строками, чтобы сохранить точность NUMERIC(19,4)
// без потерь при сериализации (инвариант 20 ТЗ).

@Serializable
enum class ProductModelStatus {
    DEVELOPMENT,
    PILOT_PRODUCTION,
    SERIAL_PRODUCTION,
    DISCONTINUED
}

@Serializable
enum class BomRevisionStatus {
    DRAFT,
    APPROVED,
    ACTIVE,
    REPLACED,
    CANCELLED
}

@Serializable
data class ProductModelDto(
    val id: Long,
    val code: String,
    val name: String,
    val description: String? = null,
    val status: ProductModelStatus = ProductModelStatus.DEVELOPMENT,
    val designerId: Long? = null,
    val technologistId: Long? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0
)

@Serializable
data class CreateProductModelRequest(
    val code: String,
    val name: String,
    val description: String? = null,
    val status: ProductModelStatus? = null,
    val designerId: Long? = null,
    val technologistId: Long? = null
)

@Serializable
data class UpdateProductModelRequest(
    val name: String? = null,
    val description: String? = null,
    val status: ProductModelStatus? = null,
    val designerId: Long? = null,
    val technologistId: Long? = null,
    val version: Long
)

@Serializable
data class BomRevisionDto(
    val id: Long,
    val productModelId: Long,
    val revisionNumber: String,
    val status: BomRevisionStatus = BomRevisionStatus.DRAFT,
    val changeReason: String? = null,
    val approvedBy: Long? = null,
    val approvedAt: String? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0
)

@Serializable
data class BomRevisionSummaryDto(
    val id: Long,
    val revisionNumber: String,
    val status: BomRevisionStatus = BomRevisionStatus.DRAFT,
    val approvedBy: Long? = null,
    val approvedAt: String? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0
)

@Serializable
data class CreateBomRevisionRequest(
    val revisionNumber: String,
    val changeReason: String? = null
)

@Serializable
data class CopyBomRevisionRequest(
    val revisionNumber: String,
    val reason: String
)

@Serializable
data class ApproveBomRevisionRequest(
    val version: Long
)

@Serializable
data class TransitionBomRevisionRequest(
    val version: Long
)

@Serializable
data class BomLineDto(
    val id: Long,
    val revisionId: Long,
    val parentLineId: Long? = null,
    val parentNomenclatureId: Long,
    val childNomenclatureId: Long,
    val quantity: String,
    val unit: String,
    val position: String? = null,
    val level: Int = 1,
    val mandatory: Boolean = true,
    val substituteOfLineId: Long? = null,
    val consumptionRate: String? = null,
    val lossPct: String = "0",
    val createdAt: String? = null,
    val createdBy: Long
)

@Serializable
data class CreateBomLineRequest(
    val parentLineId: Long? = null,
    val parentNomenclatureId: Long,
    val childNomenclatureId: Long,
    val quantity: String,
    val unit: String,
    val position: String? = null,
    val level: Int? = null,
    val mandatory: Boolean? = null,
    val substituteOfLineId: Long? = null,
    val consumptionRate: String? = null,
    val lossPct: String? = null
)

@Serializable
data class UpdateBomLineRequest(
    val quantity: String? = null,
    val unit: String? = null,
    val position: String? = null,
    val level: Int? = null,
    val mandatory: Boolean? = null,
    val consumptionRate: String? = null,
    val lossPct: String? = null
)

@Serializable
data class BomExplodeRequest(
    val releaseQuantity: String,
    val warehouseId: Long? = null
)

@Serializable
data class BomExplodeLineDto(
    val id: Long,
    val lineId: Long,
    val parentLineId: Long? = null,
    val level: Int,
    val nomenclatureId: Long,
    val code: String,
    val name: String,
    val unit: String,
    val quantityPerParent: String,
    val parentQuantity: String,
    val requiredQuantity: String,
    val consumptionRate: String? = null,
    val lossPct: String = "0"
)

@Serializable
data class BomMaterialSummaryDto(
    val nomenclatureId: Long,
    val code: String,
    val name: String,
    val unit: String,
    val totalRequired: String,
    val stockAvailable: String,
    val stockReserved: String,
    val expectedReceipt: String,
    val deficit: String,
    val recommendedPurchase: String
)

@Serializable
data class BomExplodeReportDto(
    val revisionId: Long,
    val revisionNumber: String,
    val productModelId: Long,
    val releaseQuantity: String,
    val lines: List<BomExplodeLineDto> = emptyList(),
    val summary: List<BomMaterialSummaryDto> = emptyList()
)
