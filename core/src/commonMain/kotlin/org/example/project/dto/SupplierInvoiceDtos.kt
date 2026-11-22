package org.example.project.dto

import kotlinx.serialization.Serializable

// Счёт поставщика (D-61, D-62). Суммы и количества передаются строками,
// чтобы сохранить точность NUMERIC; суммы — как напечатаны в документе.

@Serializable
enum class SupplierInvoiceStatus {
    DRAFT,
    CHECKED,
    CANCELLED
}

@Serializable
enum class LeadTimeKind {
    WORKING,
    CALENDAR
}

/** Важность замечания сверки счёта. */
@Serializable
enum class InvoiceIssueSeverity {
    /** Подтвердить сверку нельзя: счёт нужно исправить или вернуть поставщику. */
    ERROR,

    /** Подтвердить можно только с письменным обоснованием. */
    REQUIRES_REASON,

    /** Информация для снабженца; подтверждению не мешает. */
    WARNING
}

@Serializable
data class SupplierInvoiceLineInput(
    val lineNo: Int,
    val supplierItemCode: String? = null,
    val supplierItemName: String,
    val nomenclatureId: Long? = null,
    val purchaseOrderLineId: Long? = null,
    val quantity: String,
    val unit: String,
    val unitPrice: String,
    /** `null` — «Без НДС». */
    val vatRate: String? = "20",
    val vatAmount: String,
    val lineTotal: String
)

/** Реквизиты счёта, которые снабженец переносит из загруженного файла. */
@Serializable
data class SupplierInvoiceInput(
    val number: String,
    val invoiceDate: String,
    val supplierId: Long,
    val purchaseOrderId: Long? = null,
    /** Документ DMS типа INVOICE с загруженным файлом счёта — обязателен. */
    val documentId: Long,
    val supplierInn: String? = null,
    val supplierKpp: String? = null,
    val currency: String = "RUB",
    val pricesIncludeVat: Boolean = false,
    val netTotal: String,
    val vatTotal: String,
    val grossTotal: String,
    val validUntil: String? = null,
    val paymentTerms: String? = null,
    val prepaymentPct: String? = null,
    val leadTimeDays: Int? = null,
    val leadTimeKind: LeadTimeKind? = null,
    val deliveryTerms: String? = null,
    val paymentPurpose: String? = null,
    val supplierManager: String? = null,
    val note: String? = null,
    val lines: List<SupplierInvoiceLineInput> = emptyList()
)

@Serializable
data class UpdateSupplierInvoiceRequest(
    val invoice: SupplierInvoiceInput,
    val version: Long
)

@Serializable
data class SupplierInvoiceLineDto(
    val id: Long,
    val lineNo: Int,
    val supplierItemCode: String? = null,
    val supplierItemName: String,
    val nomenclatureId: Long? = null,
    val nomenclatureCode: String? = null,
    val nomenclatureName: String? = null,
    val purchaseOrderLineId: Long? = null,
    val quantity: String,
    val unit: String,
    val unitPrice: String,
    val vatRate: String? = null,
    val vatAmount: String,
    val lineTotal: String
)

@Serializable
data class SupplierInvoiceDto(
    val id: Long,
    val number: String,
    val invoiceDate: String,
    val supplierId: Long,
    val supplierName: String? = null,
    val purchaseOrderId: Long? = null,
    val purchaseOrderNumber: String? = null,
    val documentId: Long,
    val documentNumber: String? = null,
    val fileName: String? = null,
    val supplierInn: String? = null,
    val supplierKpp: String? = null,
    val currency: String = "RUB",
    val pricesIncludeVat: Boolean = false,
    val netTotal: String,
    val vatTotal: String,
    val grossTotal: String,
    val validUntil: String? = null,
    /** Срок действия истёк (на сервере, по текущей дате). */
    val expired: Boolean = false,
    val paymentTerms: String? = null,
    val prepaymentPct: String? = null,
    val leadTimeDays: Int? = null,
    val leadTimeKind: LeadTimeKind? = null,
    val deliveryTerms: String? = null,
    val paymentPurpose: String? = null,
    val supplierManager: String? = null,
    val note: String? = null,
    val status: SupplierInvoiceStatus = SupplierInvoiceStatus.DRAFT,
    val acceptedDiscrepancyReason: String? = null,
    val checkedBy: Long? = null,
    val checkedAt: String? = null,
    val cancelReason: String? = null,
    /** Поступление на склад, оформленное по счёту (действующее, не отменённое). */
    val receiptId: Long? = null,
    val receiptNumber: String? = null,
    val receiptStatus: ReceiptStatus? = null,
    val createdBy: Long,
    val createdAt: String? = null,
    val version: Long = 0,
    val lines: List<SupplierInvoiceLineDto> = emptyList()
)

@Serializable
data class SupplierInvoiceIssueDto(
    val code: String,
    val severity: InvoiceIssueSeverity,
    val lineNo: Int? = null,
    val message: String
)

@Serializable
data class SupplierInvoiceCheckDto(
    val invoiceId: Long,
    val issues: List<SupplierInvoiceIssueDto>,
    /** Нет ошибок: сверку можно подтвердить (возможно, с обоснованием). */
    val canConfirm: Boolean,
    val requiresReason: Boolean
)

@Serializable
data class ConfirmSupplierInvoiceCheckRequest(
    val version: Long,
    /** Обязательно, если есть замечания уровня REQUIRES_REASON. */
    val acceptReason: String? = null
)

@Serializable
data class SupplierInvoiceActionRequest(
    val version: Long
)

@Serializable
data class CancelSupplierInvoiceRequest(
    val version: Long,
    val reason: String
)

/** Снабжение оформляет поступление на склад по сверенному счёту. */
@Serializable
data class CreateReceiptFromInvoiceRequest(
    val warehouseId: Long,
    val version: Long,
    val expectedAt: String? = null,
    val note: String? = null
)

@Serializable
data class SupplierInvoiceHistoryDto(
    val id: Long,
    val action: String,
    val fromStatus: String? = null,
    val toStatus: String? = null,
    val reason: String? = null,
    val changedBy: Long,
    val changedAt: String? = null
)

/** Склад вносит фактическое количество и ячейки в поступление перед проведением. */
@Serializable
data class AcceptReceiptLineInput(
    val lineId: Long,
    val receivedQuantity: String,
    val locationId: Long,
    val lotNumber: String? = null,
    val discrepancyReason: String? = null
)

@Serializable
data class AcceptReceiptLinesRequest(
    val version: Long,
    val lines: List<AcceptReceiptLineInput>
)
