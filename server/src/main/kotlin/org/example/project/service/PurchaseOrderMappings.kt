package org.example.project.service

import org.example.project.dto.PurchaseDeliveryDto
import org.example.project.dto.PurchaseDeliveryLineDto
import org.example.project.dto.PurchaseDeliveryStatus
import org.example.project.dto.PurchaseOrderDto
import org.example.project.dto.PurchaseOrderLineDto
import org.example.project.dto.PurchaseOrderStatus
import org.example.project.dto.PurchaseOrderTotalsDto
import org.example.project.generated.jooq.tables.records.PurchaseDeliveriesRecord
import org.example.project.generated.jooq.tables.records.PurchaseDeliveryLinesRecord
import org.example.project.generated.jooq.tables.records.PurchaseOrderLinesRecord
import org.example.project.generated.jooq.tables.records.PurchaseOrdersRecord
import java.math.BigDecimal
import java.math.RoundingMode
import org.jooq.Record

/** Остаток к поставке считает база; здесь он только читается из того же расчёта. */
internal fun remaining(quantity: BigDecimal, delivered: BigDecimal): BigDecimal =
    (quantity - delivered).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP)

internal fun PurchaseOrderLinesRecord.toDto(
    nomenclatureCode: String? = null,
    nomenclatureName: String? = null
): PurchaseOrderLineDto = PurchaseOrderLineDto(
    id = this.id,
    lineNo = this.lineNo,
    nomenclatureId = this.nomenclatureId,
    nomenclatureCode = nomenclatureCode,
    nomenclatureName = nomenclatureName,
    quantity = this.quantity.toPlainString(),
    unit = this.unit,
    unitPrice = this.unitPrice.toPlainString(),
    vatRate = this.vatRate.toPlainString(),
    lineTotal = this.quantity.multiply(this.unitPrice).toPlainString(),
    deliveredQuantity = this.deliveredQuantity.toPlainString(),
    remainingQuantity = remaining(this.quantity, this.deliveredQuantity).toPlainString(),
    offerLineId = this.offerLineId,
    note = this.note
)

internal fun PurchaseOrdersRecord.toDto(
    supplierName: String?,
    offerNumber: String?,
    demandIds: List<Long>,
    lines: List<PurchaseOrderLineDto>,
    totals: Record?,
    demandNumbers: List<String> = emptyList(),
    paidByName: String? = null,
    invoiceDocumentId: Long? = null
): PurchaseOrderDto = PurchaseOrderDto(
    id = this.id,
    number = this.number,
    supplierId = this.supplierId,
    supplierName = supplierName,
    offerId = this.offerId,
    offerNumber = offerNumber,
    status = PurchaseOrderStatus.valueOf(this.status),
    currency = this.currency,
    deliveryAddress = this.deliveryAddress,
    paymentTerms = this.paymentTerms,
    contractNo = this.contractNo,
    invoiceNo = this.invoiceNo,
    note = this.note,
    responsibleId = this.responsibleId,
    orderedAt = this.orderedAt?.toString(),
    expectedAt = this.expectedAt?.toString(),
    approvedBy = this.approvedBy,
    approvedAt = this.approvedAt?.toString(),
    approvalNote = this.approvalNote,
    reapprovalRequired = this.reapprovalRequired,
    reapprovalReason = this.reapprovalReason,
    demandIds = demandIds,
    demandNumbers = demandNumbers,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    updatedAt = this.updatedAt?.toString(),
    updatedBy = this.updatedBy,
    version = this.version,
    lines = lines,
    totals = totals?.let {
        PurchaseOrderTotalsDto(
            netTotal = (it.get("net_total", BigDecimal::class.java) ?: BigDecimal.ZERO).toPlainString(),
            vatTotal = (it.get("vat_total", BigDecimal::class.java) ?: BigDecimal.ZERO).toPlainString(),
            grossTotal = (it.get("gross_total", BigDecimal::class.java) ?: BigDecimal.ZERO).toPlainString(),
            orderedQuantity = (it.get("ordered_quantity", BigDecimal::class.java) ?: BigDecimal.ZERO).toPlainString(),
            deliveredQuantity = (it.get("delivered_quantity", BigDecimal::class.java) ?: BigDecimal.ZERO).toPlainString()
        )
    },
    paidAt = this.paidAt?.toString(),
    paidBy = this.paidBy,
    paidByName = paidByName,
    invoiceDocumentId = invoiceDocumentId
)

internal fun PurchaseDeliveriesRecord.toDto(
    orderNumber: String?,
    lines: List<PurchaseDeliveryLineDto>
): PurchaseDeliveryDto = PurchaseDeliveryDto(
    id = this.id,
    number = this.number,
    orderId = this.orderId,
    orderNumber = orderNumber,
    status = PurchaseDeliveryStatus.valueOf(this.status),
    deliveredAt = this.deliveredAt.toString(),
    deliveryNote = this.deliveryNote,
    sourceDocument = this.sourceDocument,
    note = this.note,
    createdAt = this.createdAt?.toString(),
    createdBy = this.createdBy,
    version = this.version,
    lines = lines
)

internal fun PurchaseDeliveryLinesRecord.toDto(nomenclatureId: Long?, nomenclatureName: String?): PurchaseDeliveryLineDto =
    PurchaseDeliveryLineDto(
        id = this.id,
        orderLineId = this.orderLineId,
        nomenclatureId = nomenclatureId,
        nomenclatureName = nomenclatureName,
        quantity = this.quantity.toPlainString(),
        note = this.note
    )
