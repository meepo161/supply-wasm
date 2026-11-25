package org.example.project.dto

import kotlinx.serialization.Serializable

// Количества передаются строками, чтобы сохранить точность NUMERIC(19,4)
// без потерь при сериализации (инвариант 20 ТЗ).

@Serializable
enum class DemandSourceType {
    PROJECT,
    STOCKPILE,
    MANUAL
}

@Serializable
enum class DemandStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    IN_PROCUREMENT,
    PARTIALLY_COVERED,
    COVERED,
    CANCELLED
}

@Serializable
enum class DemandPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

@Serializable
data class DemandHeaderDto(
    val id: Long,
    val number: String,
    val sourceType: DemandSourceType,
    val projectId: Long? = null,
    val stockpileNote: String? = null,
    val initiatorId: Long,
    val requiredDate: String? = null,
    val priority: DemandPriority = DemandPriority.NORMAL,
    val status: DemandStatus = DemandStatus.DRAFT,
    val approvedBy: Long? = null,
    val approvedAt: String? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val sourceBomRevisionId: Long? = null,
    val bomReleaseQuantity: String? = null,
    val bomRevisionVersion: Long? = null,
    val idempotencyKey: String? = null,
    /** Только в списке: число позиций, состав и количество для колонок реестра. */
    val lineCount: Int = 0,
    val itemsSummary: String? = null,
    val quantitySummary: String? = null,
    val initiatorName: String? = null,
    val selectedSupplierName: String? = null,
    val selectedGrossTotal: String? = null,
    val isPaid: Boolean = false,
    val paidAt: String? = null,
    val projectName: String? = null,
    val projectNumber: String? = null,
    val nextActorRole: String? = null,
    val nextActionHint: String? = null,
    val stallReason: String? = null,
    val hasReturnedOffers: Boolean = false
)

@Serializable
data class DemandLineDto(
    val id: Long,
    val demandId: Long,
    val lineNo: Int,
    val nomenclatureId: Long,
    val nomenclatureCode: String? = null,
    val nomenclatureName: String? = null,
    val quantity: String,
    val unit: String,
    val coveredQuantity: String = "0",
    val stockReservedQuantity: String = "0",
    val deficitQuantity: String? = null,
    val sourceBomRevisionId: Long? = null,
    val status: DemandLineStatus = DemandLineStatus.OPEN,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0
)

@Serializable
enum class DemandLineStatus {
    OPEN,
    PARTIALLY_COVERED,
    COVERED,
    CANCELLED
}

@Serializable
data class DemandLinkedReceiptDto(
    val id: Long,
    val number: String,
    val status: String,
    val warehouseId: Long,
    val warehouseName: String? = null,
    val postedAt: String? = null,
    val expectedAt: String? = null
)

@Serializable
data class MarkDemandPaidRequest(
    val paymentNote: String? = null,
    val expectedAt: String? = null,
    val idempotencyKey: String? = null
)

@Serializable
data class DemandDetailDto(
    val header: DemandHeaderDto,
    val lines: List<DemandLineDto> = emptyList(),
    /** Только в карточке: сколько предложений и заказов связано с потребностью. */
    val offersCount: Int = 0,
    val ordersCount: Int = 0,
    val offers: List<SupplierOfferDto> = emptyList(),
    val orders: List<PurchaseOrderDto> = emptyList(),
    val receipts: List<DemandLinkedReceiptDto> = emptyList(),
    val stockByNomenclature: Map<Long, String> = emptyMap()
)

@Serializable
data class DemandLineInput(
    val lineNo: Int,
    val nomenclatureId: Long,
    val quantity: String,
    val unit: String
)

@Serializable
data class CreateDemandRequest(
    val sourceType: DemandSourceType,
    val projectId: Long? = null,
    val stockpileNote: String? = null,
    val requiredDate: String? = null,
    val priority: DemandPriority? = DemandPriority.NORMAL,
    val idempotencyKey: String? = null,
    val lines: List<DemandLineInput> = emptyList()
)

@Serializable
data class CreateDemandFromBomRequest(
    val projectId: Long,
    val revisionId: Long,
    val releaseQuantity: String,
    val requiredDate: String? = null,
    val priority: DemandPriority? = DemandPriority.NORMAL,
    val idempotencyKey: String? = null
)

@Serializable
data class SubmitDemandRequest(
    val version: Long
)

@Serializable
data class ApproveDemandRequest(
    val version: Long
)

@Serializable
data class CancelDemandRequest(
    val version: Long,
    val reason: String
)

@Serializable
data class DemandCoverageLineDto(
    val lineNo: Int,
    val nomenclatureId: Long,
    val unit: String,
    val requiredQuantity: String,
    val stockAvailable: String,
    val projectReservations: String,
    val expectedReceipts: String,
    val deficit: String,
    val recommendedPurchase: String
)

@Serializable
data class DemandCoverageDto(
    val demandId: Long,
    val lines: List<DemandCoverageLineDto> = emptyList()
)

@Serializable
data class ReserveDemandStockRequest(
    val quantity: String? = null,
    val warehouseId: Long? = null
)

@Serializable
data class PlaceDemandOrderRequest(
    val expectedAt: String? = null,
    val invoiceNo: String? = null,
    val paymentTerms: String? = null,
    val deliveryAddress: String? = null,
    val note: String? = null,
    val markPaid: Boolean = true
)

@Serializable
data class HandoffProjectSpecRequest(
    val items: List<DemandLineInput> = emptyList(),
    val revisionId: Long? = null,
    val releaseQuantity: String? = null,
    val requiredDate: String? = null,
    val note: String? = null,
    val idempotencyKey: String? = null
)

@Serializable
data class ProjectSupplyLineDto(
    val demandId: Long? = null,
    val demandNumber: String? = null,
    val demandLineId: Long? = null,
    val nomenclatureId: Long,
    val nomenclatureCode: String,
    val nomenclatureName: String,
    val unit: String,
    val requiredQuantity: String,
    val warehouseFreeQuantity: String,
    val reservedQuantity: String,
    val orderedQuantity: String,
    val receivedQuantity: String,
    val awaitingDeliveryQuantity: String,
    val unprocuredDeficitQuantity: String,
    val remainingToEnsureQuantity: String,
    val offersCount: Int = 0,
    val returnedOffersCount: Int = 0,
    val selectedOfferId: Long? = null,
    val orderId: Long? = null,
    val orderNumber: String? = null,
    val supplierName: String? = null,
    val stageLabel: String,
    val nextActorRole: String,
    val nextActionHint: String,
    val isStalled: Boolean = false,
    val stallReason: String? = null
)

@Serializable
data class ProjectSupplyOverviewDto(
    val projectId: Long,
    val projectNumber: String,
    val projectName: String,
    val lines: List<ProjectSupplyLineDto> = emptyList(),
    val demands: List<DemandDetailDto> = emptyList()
)
