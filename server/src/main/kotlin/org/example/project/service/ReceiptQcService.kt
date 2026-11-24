package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.ReceiptLines.RECEIPT_LINES
import org.example.project.generated.jooq.tables.Receipts.RECEIPTS
import org.example.project.generated.jooq.tables.WmsReceiptQc.WMS_RECEIPT_QC
import org.example.project.generated.jooq.tables.WmsReceiptQcLines.WMS_RECEIPT_QC_LINES
import org.example.project.model.CurrentUser
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * Контроль качества при приёмке на склад (WMS-37, §8.5 ТЗ).
 */
class ReceiptQcService(
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    fun getOrInit(receiptId: Long, currentUser: CurrentUser): ReceiptQcDto {
        currentUser.requirePermission("wms", "read")
        val receipt = dsl.selectFrom(RECEIPTS).where(RECEIPTS.ID.eq(receiptId)).fetchOne()
            ?: throw NotFoundException("receipt", receiptId)
        val existing = dsl.selectFrom(WMS_RECEIPT_QC).where(WMS_RECEIPT_QC.RECEIPT_ID.eq(receiptId)).fetchOne()
        if (existing != null) {
            return loadDto(existing.id)
        }
        val lines = dsl.selectFrom(RECEIPT_LINES)
            .where(RECEIPT_LINES.RECEIPT_ID.eq(receiptId))
            .orderBy(RECEIPT_LINES.LINE_NO.asc())
            .fetch()
        return dsl.transactionResult(TransactionalCallable {
            val qc = dsl.insertInto(WMS_RECEIPT_QC)
                .set(WMS_RECEIPT_QC.RECEIPT_ID, receiptId)
                .set(WMS_RECEIPT_QC.STATUS, "PENDING")
                .set(WMS_RECEIPT_QC.CREATED_BY, currentUser.userId)
                .returning()
                .fetchOne()!!
            for (line in lines) {
                val qty = line.receivedQuantity ?: line.expectedQuantity
                dsl.insertInto(WMS_RECEIPT_QC_LINES)
                    .set(WMS_RECEIPT_QC_LINES.QC_ID, qc.id)
                    .set(WMS_RECEIPT_QC_LINES.RECEIPT_LINE_ID, line.id)
                    .set(WMS_RECEIPT_QC_LINES.ACCEPTED_QTY, qty)
                    .set(WMS_RECEIPT_QC_LINES.QUARANTINE_QTY, BigDecimal.ZERO)
                    .set(WMS_RECEIPT_QC_LINES.DEFECT_QTY, BigDecimal.ZERO)
                    .execute()
            }
            auditService.log(currentUser.userId, "QC_INIT", "RECEIPT", receipt.id)
            loadDto(qc.id)
        })
    }

    fun inspect(receiptId: Long, request: InspectReceiptQcRequest, currentUser: CurrentUser): ReceiptQcDto {
        currentUser.requirePermission("wms", "post")
        val receipt = dsl.selectFrom(RECEIPTS).where(RECEIPTS.ID.eq(receiptId)).fetchOne()
            ?: throw NotFoundException("receipt", receiptId)
        if (receipt.status != "POSTED") {
            throw ConflictException("Quality control is allowed only for POSTED receipts")
        }
        if (request.lines.isEmpty()) {
            throw ValidationException("QC lines are required", mapOf("lines" to "required"))
        }
        val qc = dsl.selectFrom(WMS_RECEIPT_QC).where(WMS_RECEIPT_QC.RECEIPT_ID.eq(receiptId)).fetchOne()
            ?: throw NotFoundException("receipt_qc", receiptId)
        if (qc.status != "PENDING") {
            throw ConflictException("Quality control is already completed")
        }

        val receiptLines = dsl.selectFrom(RECEIPT_LINES)
            .where(RECEIPT_LINES.RECEIPT_ID.eq(receiptId))
            .fetch()
            .associateBy { it.id }

        var totalAccepted = BigDecimal.ZERO
        var totalRejected = BigDecimal.ZERO
        val parsedLines = request.lines.map { input ->
            val rLine = receiptLines[input.receiptLineId]
                ?: throw ValidationException("Unknown receiptLineId: ${input.receiptLineId}")
            val received = rLine.receivedQuantity ?: rLine.expectedQuantity
            val acc = parseNonNegative(input.acceptedQty, "acceptedQty")
            val quar = parseNonNegative(input.quarantineQty, "quarantineQty")
            val def = parseNonNegative(input.defectQty, "defectQty")
            if (acc + quar + def != received) {
                throw ValidationException(
                    "Sum of accepted + quarantine + defect must equal received quantity ($received)",
                    mapOf("lines" to "sum_mismatch")
                )
            }
            if ((quar > BigDecimal.ZERO || def > BigDecimal.ZERO) && input.defectReason.isNullOrBlank()) {
                throw ValidationException("Defect/quarantine reason is required", mapOf("defectReason" to "required"))
            }
            totalAccepted += acc
            totalRejected += quar + def
            Triple(input, acc, quar to def)
        }

        val nextStatus = when {
            totalRejected.compareTo(BigDecimal.ZERO) == 0 -> ReceiptQcStatus.PASSED
            totalAccepted.compareTo(BigDecimal.ZERO) == 0 -> ReceiptQcStatus.FAILED
            else -> ReceiptQcStatus.PARTIAL
        }

        return dsl.transactionResult(TransactionalCallable {
            for ((input, acc, quarDef) in parsedLines) {
                dsl.update(WMS_RECEIPT_QC_LINES)
                    .set(WMS_RECEIPT_QC_LINES.ACCEPTED_QTY, acc)
                    .set(WMS_RECEIPT_QC_LINES.QUARANTINE_QTY, quarDef.first)
                    .set(WMS_RECEIPT_QC_LINES.DEFECT_QTY, quarDef.second)
                    .set(WMS_RECEIPT_QC_LINES.DEFECT_REASON, input.defectReason?.trim())
                    .where(WMS_RECEIPT_QC_LINES.QC_ID.eq(qc.id))
                    .and(WMS_RECEIPT_QC_LINES.RECEIPT_LINE_ID.eq(input.receiptLineId))
                    .execute()
            }
            dsl.update(WMS_RECEIPT_QC)
                .set(WMS_RECEIPT_QC.STATUS, nextStatus.name)
                .set(WMS_RECEIPT_QC.INSPECTOR_ID, currentUser.userId)
                .set(WMS_RECEIPT_QC.INSPECTED_AT, OffsetDateTime.now())
                .set(WMS_RECEIPT_QC.COMMENT, request.comment?.trim())
                .set(WMS_RECEIPT_QC.VERSION, WMS_RECEIPT_QC.VERSION.plus(1))
                .where(WMS_RECEIPT_QC.ID.eq(qc.id))
                .execute()

            auditService.log(
                currentUser.userId,
                "QC_INSPECT",
                "RECEIPT",
                receiptId,
                mapOf("status" to nextStatus.name, "qcId" to qc.id)
            )
            loadDto(qc.id)
        })
    }

    private fun loadDto(qcId: Long): ReceiptQcDto {
        val qc = dsl.selectFrom(WMS_RECEIPT_QC).where(WMS_RECEIPT_QC.ID.eq(qcId)).fetchOne()!!
        val lines = dsl.select(
            WMS_RECEIPT_QC_LINES.ID,
            WMS_RECEIPT_QC_LINES.RECEIPT_LINE_ID,
            RECEIPT_LINES.NOMENCLATURE_ID,
            NOMENCLATURE.CODE,
            NOMENCLATURE.NAME,
            RECEIPT_LINES.RECEIVED_QUANTITY,
            RECEIPT_LINES.EXPECTED_QUANTITY,
            WMS_RECEIPT_QC_LINES.ACCEPTED_QTY,
            WMS_RECEIPT_QC_LINES.QUARANTINE_QTY,
            WMS_RECEIPT_QC_LINES.DEFECT_QTY,
            WMS_RECEIPT_QC_LINES.DEFECT_REASON
        )
            .from(WMS_RECEIPT_QC_LINES)
            .join(RECEIPT_LINES).on(RECEIPT_LINES.ID.eq(WMS_RECEIPT_QC_LINES.RECEIPT_LINE_ID))
            .join(NOMENCLATURE).on(NOMENCLATURE.ID.eq(RECEIPT_LINES.NOMENCLATURE_ID))
            .where(WMS_RECEIPT_QC_LINES.QC_ID.eq(qcId))
            .orderBy(RECEIPT_LINES.LINE_NO.asc())
            .fetch { row ->
                val received = row.get(RECEIPT_LINES.RECEIVED_QUANTITY) ?: row.get(RECEIPT_LINES.EXPECTED_QUANTITY)!!
                ReceiptQcLineDto(
                    id = row.get(WMS_RECEIPT_QC_LINES.ID)!!,
                    receiptLineId = row.get(WMS_RECEIPT_QC_LINES.RECEIPT_LINE_ID)!!,
                    nomenclatureId = row.get(RECEIPT_LINES.NOMENCLATURE_ID)!!,
                    nomenclatureCode = row.get(NOMENCLATURE.CODE)!!,
                    nomenclatureName = row.get(NOMENCLATURE.NAME)!!,
                    receivedQty = received.stripTrailingZeros().toPlainString(),
                    acceptedQty = row.get(WMS_RECEIPT_QC_LINES.ACCEPTED_QTY)!!.stripTrailingZeros().toPlainString(),
                    quarantineQty = row.get(WMS_RECEIPT_QC_LINES.QUARANTINE_QTY)!!.stripTrailingZeros().toPlainString(),
                    defectQty = row.get(WMS_RECEIPT_QC_LINES.DEFECT_QTY)!!.stripTrailingZeros().toPlainString(),
                    defectReason = row.get(WMS_RECEIPT_QC_LINES.DEFECT_REASON)
                )
            }
        return ReceiptQcDto(
            id = qc.id,
            receiptId = qc.receiptId,
            status = ReceiptQcStatus.valueOf(qc.status),
            inspectorId = qc.inspectorId,
            inspectedAt = qc.inspectedAt?.toString(),
            comment = qc.comment,
            version = qc.version ?: 0,
            lines = lines
        )
    }

    private fun parseNonNegative(raw: String, field: String): BigDecimal {
        val value = raw.trim().replace(" ", "").replace(',', '.').toBigDecimalOrNull()
            ?: throw ValidationException("Invalid number for $field", mapOf(field to "invalid"))
        if (value < BigDecimal.ZERO) throw ValidationException("$field must be >= 0", mapOf(field to "negative"))
        return value
    }
}
