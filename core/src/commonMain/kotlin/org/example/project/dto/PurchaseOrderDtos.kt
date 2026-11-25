package org.example.project.dto

import kotlinx.serialization.Serializable

// Заказ поставщику и частичные поставки (ТЗ §9.3–9.8; SUP-15, SUP-16).
// Суммы и количества — строки: NUMERIC(19,4) нельзя терять в Double.

@Serializable
enum class PurchaseOrderStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    SENT,
    PARTIALLY_DELIVERED,
    DELIVERED,
    CLOSED,
    CANCELLED
}

@Serializable
data class PurchaseOrderLineDto(
    val id: Long,
    val lineNo: Int,
    val nomenclatureId: Long,
    val nomenclatureCode: String? = null,
    val nomenclatureName: String? = null,
    val quantity: String,
    val unit: String,
    val unitPrice: String,
    val vatRate: String,
    val lineTotal: String,
    val deliveredQuantity: String,
    val remainingQuantity: String,
    val offerLineId: Long? = null,
    val note: String? = null
)

@Serializable
data class PurchaseOrderTotalsDto(
    val netTotal: String,
    val vatTotal: String,
    val grossTotal: String,
    val orderedQuantity: String,
    val deliveredQuantity: String
)

@Serializable
data class PurchaseOrderDto(
    val id: Long,
    val number: String,
    val supplierId: Long,
    val supplierName: String? = null,
    val offerId: Long,
    val offerNumber: String? = null,
    val status: PurchaseOrderStatus = PurchaseOrderStatus.DRAFT,
    val currency: String = "RUB",
    val deliveryAddress: String? = null,
    val paymentTerms: String? = null,
    val contractNo: String? = null,
    val invoiceNo: String? = null,
    val note: String? = null,
    val responsibleId: Long,
    val orderedAt: String? = null,
    val expectedAt: String? = null,
    val approvedBy: Long? = null,
    val approvedAt: String? = null,
    val approvalNote: String? = null,
    val reapprovalRequired: Boolean = false,
    val reapprovalReason: String? = null,
    val demandIds: List<Long> = emptyList(),
    /** Номера потребностей в том же порядке, что и [demandIds]. */
    val demandNumbers: List<String> = emptyList(),
    val createdAt: String? = null,
    val createdBy: Long,
    val updatedAt: String? = null,
    val updatedBy: Long? = null,
    val version: Long = 0,
    val lines: List<PurchaseOrderLineDto> = emptyList(),
    val totals: PurchaseOrderTotalsDto? = null,
    val paidAt: String? = null,
    val paidBy: Long? = null,
    val paidByName: String? = null,
    val invoiceDocumentId: Long? = null
)

/**
 * Создание заказа из выбранного предложения. Позиции по умолчанию берутся из
 * предложения; изменение количества относительно предложения — существенное
 * изменение условий и требует обоснования.
 */
@Serializable
data class CreatePurchaseOrderRequest(
    val offerId: Long,
    val demandIds: List<Long> = emptyList(),
    val deliveryAddress: String? = null,
    val paymentTerms: String? = null,
    val contractNo: String? = null,
    val invoiceNo: String? = null,
    val expectedAt: String? = null,
    val note: String? = null,
    val responsibleId: Long? = null,
    /** Необязательное переопределение позиций: только уменьшение количества. */
    val lines: List<CreatePurchaseOrderLineInput>? = null,
    val idempotencyKey: String? = null
)

@Serializable
data class CreatePurchaseOrderLineInput(
    val offerLineId: Long,
    val quantity: String
)

@Serializable
data class UpdatePurchaseOrderLineInput(
    val orderLineId: Long,
    val quantity: String
)

@Serializable
data class UpdatePurchaseOrderRequest(
    val deliveryAddress: String? = null,
    val paymentTerms: String? = null,
    val contractNo: String? = null,
    val invoiceNo: String? = null,
    val expectedAt: String? = null,
    val note: String? = null,
    val responsibleId: Long? = null,
    val lines: List<UpdatePurchaseOrderLineInput>? = null,
    /** Обязательно, если изменение существенное: условие повторного согласования. */
    val changeReason: String? = null,
    val version: Long
)

@Serializable
data class PurchaseOrderActionRequest(
    val version: Long,
    val note: String? = null,
    val idempotencyKey: String? = null
)

@Serializable
data class CancelPurchaseOrderRequest(
    val version: Long,
    val reason: String,
    val idempotencyKey: String? = null
)

@Serializable
data class PurchaseOrderHistoryDto(
    val id: Long,
    val action: String,
    val fromStatus: String? = null,
    val toStatus: String? = null,
    val reason: String? = null,
    val changedBy: Long,
    val changedAt: String? = null
)

// ---------------------------------------------------------------------------
// Частичные поставки
// ---------------------------------------------------------------------------

@Serializable
enum class PurchaseDeliveryStatus {
    REGISTERED,
    CANCELLED
}

@Serializable
data class PurchaseDeliveryLineDto(
    val id: Long,
    val orderLineId: Long,
    val nomenclatureId: Long? = null,
    val nomenclatureName: String? = null,
    val quantity: String,
    val note: String? = null
)

@Serializable
data class PurchaseDeliveryDto(
    val id: Long,
    val number: String,
    val orderId: Long,
    val orderNumber: String? = null,
    val status: PurchaseDeliveryStatus = PurchaseDeliveryStatus.REGISTERED,
    val deliveredAt: String,
    val deliveryNote: String? = null,
    val sourceDocument: String? = null,
    val note: String? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val lines: List<PurchaseDeliveryLineDto> = emptyList()
)

@Serializable
data class CreatePurchaseDeliveryRequest(
    val deliveredAt: String,
    val deliveryNote: String? = null,
    val sourceDocument: String? = null,
    val note: String? = null,
    val lines: List<CreatePurchaseDeliveryLineInput>,
    val idempotencyKey: String? = null
)

@Serializable
data class CreatePurchaseDeliveryLineInput(
    val orderLineId: Long,
    val quantity: String,
    val note: String? = null
)

@Serializable
data class CancelPurchaseDeliveryRequest(
    val version: Long,
    val reason: String,
    val idempotencyKey: String? = null
)
