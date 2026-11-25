package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.records.PurchaseDeliveriesRecord
import org.example.project.generated.jooq.tables.records.PurchaseOrderLinesRecord
import org.example.project.generated.jooq.tables.records.PurchaseOrdersRecord
import org.example.project.generated.jooq.tables.records.ReceiptLinesRecord
import org.example.project.generated.jooq.tables.records.ReceiptLinksRecord
import org.example.project.generated.jooq.tables.records.ReceiptsRecord
import org.example.project.model.CurrentUser
import org.example.project.realtime.NotificationEventBus
import org.example.project.repository.PurchaseOrderRepository
import org.example.project.repository.WmsRepository
import org.example.project.generated.jooq.tables.ReceiptLines.RECEIPT_LINES
import org.example.project.generated.jooq.tables.Receipts.RECEIPTS
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.TransactionalCallable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Приёмка на склад по разным основаниям (ТЗ §8.4–8.6, §9.3; WMS-PROC-001).
 *
 * Границы и правила:
 * - основание обязательно и перечислено явно: заказ поставщику, счёт,
 *   накладная/УПД, возврат, перемещение, начальный остаток, прочий документ;
 *   «просто приёмка без основания» невозможна;
 * - один документ-основание проводится один раз: уникальный индекс по
 *   (вид, номер документа) не даёт принять одну накладную дважды;
 * - приёмка по заказу уменьшает ожидаемый остаток и может быть частичной;
 *   несколько приёмок по одному заказу разрешены, остаток считает база;
 * - расхождения фиксируются по строке: недопоставка и излишек остаются
 *   видимыми; дальнейшее разбирательство по излишку — отдельный процесс;
 * - остаток меняется только при проведении и только в одной транзакции, после
 *   которого документ неизменяем (триггер `wms_guard_receipt_mutation`);
 * - уже проведённую приёмку можно связать с заказом или накладной, пришедшими
 *   позже: связь пишется в журнал `receipt_links`, остаток не пересоздаётся;
 * - контроль качества (WMS-37) и претензии — отдельные блоки.
 */
class ReceiptService(
    private val wmsRepository: WmsRepository,
    private val purchaseRepository: PurchaseOrderRepository,
    private val notificationService: NotificationService,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    // -----------------------------------------------------------------------
    // Чтение
    // -----------------------------------------------------------------------

    fun getReceipt(id: Long, currentUser: CurrentUser): ReceiptDto {
        currentUser.requirePermission("wms", "read")
        val receipt = wmsRepository.findReceiptById(id) ?: throw NotFoundException("receipt", id)
        return build(receipt)
    }

    fun listReceipts(
        warehouseId: Long?,
        purchaseOrderId: Long?,
        status: ReceiptStatus?,
        currentUser: CurrentUser
    ): List<ReceiptDto> {
        currentUser.requirePermission("wms", "read")
        val receipts = wmsRepository.listReceipts(warehouseId, purchaseOrderId, status?.name)
        if (receipts.isEmpty()) return emptyList()
        return receipts.map { build(it) }
    }

    /**
     * Ожидаемые к приёмке позиции: «заказано / поставлено / принято / осталось».
     * Данные читаются из view, а не пересчитываются на клиенте.
     */
    fun listExpectedReceipts(orderId: Long?, currentUser: CurrentUser): List<ExpectedReceiptLineDto> {
        currentUser.requirePermission("wms", "read")
        if (orderId != null) {
            purchaseRepository.findOrderById(orderId) ?: throw NotFoundException("purchase_order", orderId)
        }
        val base = wmsRepository.expectedReceiptLines(orderId)
            .map { it.toExpectedDto() }
            .filter { it.orderStatus in receiptAllowedOrderStatuses }
        if (base.isEmpty()) return emptyList()
        val enrichments = wmsRepository.expectedReceiptEnrichments(
            orderIds = base.map { it.orderId },
            orderLineIds = base.map { it.orderLineId },
            nomenclatureIds = base.map { it.nomenclatureId }
        )
        return base.map { row ->
            val orderInfo = enrichments.orders[row.orderId]
            val nomInfo = enrichments.nomenclature[row.nomenclatureId]
            row.copy(
                nomenclatureCode = row.nomenclatureCode ?: nomInfo?.first,
                nomenclatureName = row.nomenclatureName ?: nomInfo?.second,
                supplierName = orderInfo?.supplierName,
                demandNumbers = orderInfo?.demandNumbers.orEmpty(),
                isPaid = orderInfo?.isPaid ?: false,
                paidAt = orderInfo?.paidAt,
                offerId = orderInfo?.offerId,
                invoiceDocumentId = orderInfo?.invoiceDocumentId,
                unit = enrichments.unitsByOrderLine[row.orderLineId],
                invoiceNo = orderInfo?.invoiceNo,
                projectId = orderInfo?.projectId,
                projectNumber = orderInfo?.projectNumber,
                projectName = orderInfo?.projectName
            )
        }
    }

    // -----------------------------------------------------------------------
    // Создание приёмки
    // -----------------------------------------------------------------------

    fun createReceipt(request: CreateReceiptRequest, currentUser: CurrentUser): ReceiptDto {
        currentUser.requirePermission("wms", "post")
        if (request.lines.isEmpty()) {
            throw ValidationException("Receipt must have at least one line", mapOf("lines" to "required"))
        }
        val lineNos = request.lines.map { it.lineNo }
        if (lineNos.distinct().size != lineNos.size) {
            throw ValidationException("Duplicate line numbers", mapOf("lines" to "duplicate_line_no"))
        }
        val sourceDocumentNo = request.sourceDocumentNo?.trim()?.takeIf { it.isNotEmpty() }
        // Основание без документа допустимо только там, где документ не может
        // быть указан заранее: начальный остаток и прочий источник.
        val documentRequired = request.sourceType in setOf(
            ReceiptSourceType.PURCHASE_ORDER,
            ReceiptSourceType.INVOICE,
            ReceiptSourceType.DELIVERY_NOTE,
            ReceiptSourceType.RETURN,
            ReceiptSourceType.TRANSFER
        )
        if (documentRequired && sourceDocumentNo == null) {
            throw ValidationException(
                "Source document number is required for ${request.sourceType.name}",
                mapOf("sourceDocumentNo" to "required")
            )
        }
        // D-61: приёмку по счёту оформляет снабжение из карточки счёта поставщика;
        // склад не создаёт её вручную и не вводит номер счёта текстом.
        if (request.sourceType == ReceiptSourceType.INVOICE) {
            throw ValidationException(
                "Receipt by invoice is created by procurement from the supplier invoice",
                mapOf("sourceType" to "invoice_requires_supplier_invoice")
            )
        }
        if ((request.purchaseOrderId != null || request.purchaseDeliveryId != null) &&
            request.sourceType !in purchaseSourceTypes
        ) {
            throw ValidationException(
                "Purchase order can be referenced only by order, delivery note or invoice receipt",
                mapOf("sourceType" to "incompatible_with_order")
            )
        }
        val warehouse = wmsRepository.findWarehouseById(request.warehouseId)
            ?: throw NotFoundException("warehouse", request.warehouseId)
        if (warehouse.active != true) {
            throw ValidationException("Warehouse is inactive", mapOf("warehouseId" to "inactive"))
        }
        val expectedAt = parseDateTime(request.expectedAt)
        if (request.expectedAt != null && expectedAt == null) {
            throw ValidationException("Invalid expected date", mapOf("expectedAt" to "invalid"))
        }
        val documentDate = request.sourceDocumentDate?.let { parseDate(it, "sourceDocumentDate") }

        return dsl.transactionResult(TransactionalCallable {
            val delivery = request.purchaseDeliveryId?.let { deliveryId ->
                val found = purchaseRepository.findDeliveryById(deliveryId)
                    ?: throw NotFoundException("purchase_delivery", deliveryId)
                if (found.status != "REGISTERED") {
                    throw ConflictException("Delivery ${found.number} is cancelled")
                }
                found
            }
            val order = (request.purchaseOrderId ?: delivery?.orderId)?.let { orderId ->
                val found = purchaseRepository.lockOrderById(orderId)
                    ?: throw NotFoundException("purchase_order", orderId)
                validateOrderAcceptsReceipt(found)
                found
            }
            if (delivery != null && delivery.orderId != order?.id) {
                throw ValidationException(
                    "Delivery belongs to another purchase order",
                    mapOf("purchaseDeliveryId" to "other_order")
                )
            }
            if (sourceDocumentNo != null) {
                wmsRepository.findReceiptBySourceDocument(request.sourceType.name, sourceDocumentNo)
                    ?.let { existing ->
                        throw ConflictException(
                            "Receipt ${existing.number} is already registered for document $sourceDocumentNo"
                        )
                    }
            }
            val orderLines = order?.let { wmsRepository.listPurchaseOrderLines(it.id) } ?: emptyList()
            val prepared = request.lines.mapIndexed { index, line ->
                prepareLine(
                    index = index,
                    line = line,
                    warehouseId = request.warehouseId,
                    order = order,
                    orderLines = orderLines,
                    currentUser = currentUser
                )
            }
            val orderLineIds = prepared.mapNotNull { it.purchaseOrderLineId }
            if (orderLineIds.distinct().size != orderLineIds.size) {
                throw ValidationException(
                    "Each purchase order line may appear only once per receipt",
                    mapOf("lines" to "duplicate_order_line")
                )
            }

            val number = wmsRepository.generateReceiptNumber()
            val receipt = try {
                wmsRepository.createReceipt(
                    number = number,
                    sourceType = request.sourceType.name,
                    sourceRef = request.sourceRef?.trim(),
                    sourceDocumentNo = sourceDocumentNo,
                    sourceDocumentDate = documentDate,
                    purchaseOrderId = order?.id,
                    purchaseDeliveryId = delivery?.id,
                    warehouseId = request.warehouseId,
                    expectedAt = expectedAt,
                    note = request.note?.trim(),
                    createdBy = currentUser.userId
                )
            } catch (e: org.jooq.exception.DataAccessException) {
                if (e.sqlState() == "23505" && e.message?.contains("uq_receipts_source_document") == true) {
                    throw ConflictException("Receipt is already registered for document $sourceDocumentNo")
                }
                throw e
            }
            prepared.forEach { line ->
                wmsRepository.insertReceiptLine(
                    receiptId = receipt.id,
                    lineNo = line.lineNo,
                    nomenclatureId = line.nomenclatureId,
                    expectedQuantity = line.expectedQuantity,
                    receivedQuantity = line.receivedQuantity,
                    lotId = line.lotId,
                    locationId = line.locationId,
                    note = line.note,
                    purchaseOrderLineId = line.purchaseOrderLineId,
                    discrepancyKind = line.discrepancyKind,
                    discrepancyQuantity = line.discrepancyQuantity,
                    discrepancyReason = line.discrepancyReason
                )
            }
            auditService.log(
                currentUser.userId, "CREATE", "RECEIPT", receipt.id,
                mapOf(
                    "number" to receipt.number,
                    "sourceType" to receipt.sourceType,
                    "sourceDocumentNo" to sourceDocumentNo,
                    "purchaseOrderId" to order?.id,
                    "lines" to prepared.size,
                    "discrepancies" to prepared.count { it.discrepancyKind != null }
                )
            )
            build(receipt)
        })
    }

    private data class PreparedLine(
        val lineNo: Int,
        val nomenclatureId: Long,
        val expectedQuantity: BigDecimal,
        val receivedQuantity: BigDecimal,
        val lotId: Long?,
        val locationId: Long,
        val note: String?,
        val purchaseOrderLineId: Long?,
        val discrepancyKind: String?,
        val discrepancyQuantity: BigDecimal?,
        val discrepancyReason: String?
    )

    private fun prepareLine(
        index: Int,
        line: ReceiptLineInput,
        warehouseId: Long,
        order: PurchaseOrdersRecord?,
        orderLines: List<PurchaseOrderLinesRecord>,
        currentUser: CurrentUser
    ): PreparedLine {
        val nomenclature = wmsRepository.findNomenclatureById(line.nomenclatureId)
            ?: throw NotFoundException("nomenclature", line.nomenclatureId)
        if (nomenclature.archived == true) {
            throw ConflictException("Nomenclature is archived: ${nomenclature.code}")
        }
        val location = wmsRepository.findLocationById(line.locationId)
            ?: throw NotFoundException("storage_location", line.locationId)
        if (location.warehouseId != warehouseId) {
            throw ValidationException(
                "Location does not belong to the receipt warehouse",
                mapOf("lines[$index].locationId" to "other_warehouse")
            )
        }
        if (location.active != true) {
            throw ValidationException("Location is inactive", mapOf("lines[$index].locationId" to "inactive"))
        }
        val expected = parseQuantity(line.expectedQuantity, "lines[$index].expectedQuantity", allowZero = true)
        val received = line.receivedQuantity
            ?.let { parseQuantity(it, "lines[$index].receivedQuantity", allowZero = true) }
            ?: expected

        // Позиция заказа: количество, номенклатура и остаток по заказу.
        var purchaseOrderLineId = line.purchaseOrderLineId
        var effectiveExpected = expected
        if (order != null) {
            if (purchaseOrderLineId == null && orderLines.size == 1) {
                // Единственная позиция заказа — связываем без лишнего ввода.
                purchaseOrderLineId = orderLines.first().id
            }
            if (purchaseOrderLineId == null) {
                throw ValidationException(
                    "Purchase order line is required for an order receipt",
                    mapOf("lines[$index].purchaseOrderLineId" to "required")
                )
            }
            val orderLine = orderLines.firstOrNull { it.id == purchaseOrderLineId }
                ?: throw ValidationException(
                    "Order line does not belong to the receipt order",
                    mapOf("lines[$index].purchaseOrderLineId" to "not_in_order")
                )
            if (orderLine.nomenclatureId != nomenclature.id) {
                throw ValidationException(
                    "Order line contains another nomenclature",
                    mapOf("lines[$index].purchaseOrderLineId" to "nomenclature_mismatch")
                )
            }
            // Ожидаемое количество по заказу не придумывается клиентом:
            // берётся остаток заказа и принятые ранее приёмки.
            val accepted = wmsRepository.acceptedQuantityForOrderLine(orderLine.id)
            val declared = orderLine.quantity
            val remaining = (declared - accepted).max(BigDecimal.ZERO)
            effectiveExpected = remaining
            if (received > remaining) {
                throw ConflictException(
                    "Received quantity ${received.toPlainString()} exceeds the order line remainder " +
                        "${remaining.toPlainString()} (ordered ${declared.toPlainString()}, " +
                        "accepted ${accepted.toPlainString()})"
                )
            }
        }

        // Расхождение фиксируется, а не молча игнорируется.
        val discrepancyKind: String?
        val discrepancyQuantity: BigDecimal?
        when {
            received > effectiveExpected -> {
                discrepancyKind = ReceiptDiscrepancyKind.SURPLUS.name
                discrepancyQuantity = (received - effectiveExpected).setScale(4, RoundingMode.HALF_UP)
            }
            received < effectiveExpected -> {
                discrepancyKind = ReceiptDiscrepancyKind.SHORTAGE.name
                discrepancyQuantity = (effectiveExpected - received).setScale(4, RoundingMode.HALF_UP)
            }
            else -> {
                discrepancyKind = null
                discrepancyQuantity = null
            }
        }
        val discrepancyReason = line.discrepancyReason?.trim()?.takeIf { it.isNotEmpty() }
        if (discrepancyKind != null && discrepancyReason == null) {
            throw ValidationException(
                "Discrepancy requires a reason",
                mapOf("lines[$index].discrepancyReason" to "required")
            )
        }

        // Партия: для партионной номенклатуры номер обязателен.
        var lotId: Long? = null
        val lotNumber = line.lotNumber?.trim()?.takeIf { it.isNotEmpty() }
        if (nomenclature.lotTracked == true && lotNumber == null) {
            throw ValidationException(
                "Lot number is required for lot-tracked nomenclature",
                mapOf("lines[$index].lotNumber" to "required")
            )
        }
        if (lotNumber != null) {
            lotId = (wmsRepository.findLot(nomenclature.id, lotNumber)
                ?: wmsRepository.createLot(nomenclature.id, lotNumber, currentUser.userId)).id
        }

        return PreparedLine(
            lineNo = line.lineNo,
            nomenclatureId = nomenclature.id,
            expectedQuantity = effectiveExpected,
            receivedQuantity = received,
            lotId = lotId,
            locationId = location.id,
            note = line.note?.trim(),
            purchaseOrderLineId = purchaseOrderLineId,
            discrepancyKind = discrepancyKind,
            discrepancyQuantity = discrepancyQuantity,
            discrepancyReason = discrepancyReason
        )
    }

    private fun validateOrderAcceptsReceipt(order: PurchaseOrdersRecord) {
        if (order.status !in receiptAllowedOrderStatuses) {
            throw ConflictException(
                "Receipt can be registered only for a SENT, PARTIALLY_DELIVERED or DELIVERED order; " +
                    "order ${order.number} is ${order.status}"
            )
        }
    }

    // -----------------------------------------------------------------------
    // Поступление по счёту поставщика (D-61)
    // -----------------------------------------------------------------------

    data class InvoiceReceiptLine(
        val lineNo: Int,
        val nomenclatureId: Long,
        val quantity: BigDecimal,
        val purchaseOrderLineId: Long?
    )

    data class InvoiceReceiptDraft(
        val invoiceId: Long,
        val invoiceNumber: String,
        val invoiceDate: LocalDate,
        val purchaseOrderId: Long?,
        val warehouseId: Long,
        val expectedAt: String?,
        val note: String?,
        val lines: List<InvoiceReceiptLine>
    )

    /**
     * Черновик поступления по сверенному счёту. Вызывается только внутри транзакции
     * SupplierInvoiceService.createReceipt (право `invoices:manage` проверено там): ячейки и
     * фактическое количество вносит склад ([acceptLines]) перед проведением.
     * Ожидаемое количество — из счёта; превышение остатка заказа отклоняется.
     */
    fun createDraftForSupplierInvoice(draft: InvoiceReceiptDraft, currentUser: CurrentUser): ReceiptDto {
        val warehouse = wmsRepository.findWarehouseById(draft.warehouseId)
            ?: throw NotFoundException("warehouse", draft.warehouseId)
        if (warehouse.active != true) {
            throw ValidationException("Warehouse is inactive", mapOf("warehouseId" to "inactive"))
        }
        val expectedAt = parseDateTime(draft.expectedAt)
        if (draft.expectedAt != null && expectedAt == null) {
            throw ValidationException("Invalid expected date", mapOf("expectedAt" to "invalid"))
        }
        // Без собственной транзакции: вызывается внутри транзакции счёта, которая
        // блокирует счёт; вложенная транзакция закрыла бы её соединение.
        return run {
            val order = draft.purchaseOrderId?.let { orderId ->
                val found = purchaseRepository.lockOrderById(orderId)
                    ?: throw NotFoundException("purchase_order", orderId)
                validateOrderAcceptsReceipt(found)
                found
            }
            val orderLines = order?.let { wmsRepository.listPurchaseOrderLines(it.id) } ?: emptyList()
            draft.lines.forEach { line ->
                val nomenclature = wmsRepository.findNomenclatureById(line.nomenclatureId)
                    ?: throw NotFoundException("nomenclature", line.nomenclatureId)
                if (nomenclature.archived == true) {
                    throw ConflictException("Nomenclature is archived: ${nomenclature.code}")
                }
                line.purchaseOrderLineId?.let { orderLineId ->
                    val orderLine = orderLines.firstOrNull { it.id == orderLineId }
                        ?: throw ConflictException("Invoice line ${line.lineNo} refers to a line of another order")
                    val remaining = (orderLine.quantity - wmsRepository.acceptedQuantityForOrderLine(orderLineId))
                        .max(BigDecimal.ZERO)
                    if (line.quantity > remaining) {
                        throw ConflictException(
                            "Invoice line ${line.lineNo}: quantity ${line.quantity.toPlainString()} exceeds " +
                                "the order line remainder ${remaining.toPlainString()}"
                        )
                    }
                }
            }
            val receipt = wmsRepository.createReceipt(
                number = wmsRepository.generateReceiptNumber(),
                sourceType = ReceiptSourceType.INVOICE.name,
                sourceRef = null,
                sourceDocumentNo = draft.invoiceNumber,
                sourceDocumentDate = draft.invoiceDate,
                purchaseOrderId = order?.id,
                purchaseDeliveryId = null,
                warehouseId = draft.warehouseId,
                expectedAt = expectedAt,
                note = draft.note,
                createdBy = currentUser.userId
            )
            try {
                dsl.update(RECEIPTS)
                    .set(RECEIPTS.SUPPLIER_INVOICE_ID, draft.invoiceId)
                    .where(RECEIPTS.ID.eq(receipt.id))
                    .execute()
            } catch (e: org.jooq.exception.DataAccessException) {
                if (e.sqlState() == "23505") throw ConflictException("Receipt for this invoice already exists")
                throw e
            }
            draft.lines.sortedBy { it.lineNo }.forEach { line ->
                // Ячейку выбирает склад; по умолчанию «принято = ожидалось».
                wmsRepository.insertReceiptLine(
                    receiptId = receipt.id,
                    lineNo = line.lineNo,
                    nomenclatureId = line.nomenclatureId,
                    expectedQuantity = line.quantity,
                    receivedQuantity = line.quantity,
                    lotId = null,
                    locationId = null,
                    note = null,
                    purchaseOrderLineId = line.purchaseOrderLineId
                )
            }
            auditService.log(
                currentUser.userId, "CREATE", "RECEIPT", receipt.id,
                mapOf(
                    "number" to receipt.number,
                    "sourceType" to ReceiptSourceType.INVOICE.name,
                    "supplierInvoiceId" to draft.invoiceId,
                    "purchaseOrderId" to order?.id,
                    "lines" to draft.lines.size
                )
            )
            build(wmsRepository.findReceiptById(receipt.id)!!)
        }
    }

    /**
     * Склад вносит фактическое количество, ячейки, партии и причины расхождений
     * в черновик приёмки. Остаток не меняется до проведения.
     */
    fun acceptLines(id: Long, request: AcceptReceiptLinesRequest, currentUser: CurrentUser): ReceiptDto {
        currentUser.requirePermission("wms", "post")
        if (request.lines.isEmpty()) {
            throw ValidationException("No lines to accept", mapOf("lines" to "required"))
        }
        return dsl.transactionResult(TransactionalCallable {
            val receipt = wmsRepository.lockReceiptById(id) ?: throw NotFoundException("receipt", id)
            if (receipt.version != request.version) {
                throw ConflictException("Receipt was modified concurrently")
            }
            if (receipt.status != "DRAFT" && receipt.status != "IN_PROGRESS") {
                throw ConflictException("Receipt cannot be changed in status ${receipt.status}")
            }
            val lines = wmsRepository.listReceiptLines(id).associateBy { it.id }
            val orderLines = receipt.purchaseOrderId?.let { wmsRepository.listPurchaseOrderLines(it) } ?: emptyList()
            request.lines.forEachIndexed { index, input ->
                val line = lines[input.lineId]
                    ?: throw ValidationException("Line does not belong to the receipt", mapOf("lines[$index].lineId" to "not_in_receipt"))
                val received = parseQuantity(input.receivedQuantity, "lines[$index].receivedQuantity", allowZero = true)
                val location = wmsRepository.findLocationById(input.locationId)
                    ?: throw NotFoundException("storage_location", input.locationId)
                if (location.warehouseId != receipt.warehouseId) {
                    throw ValidationException("Location does not belong to the receipt warehouse", mapOf("lines[$index].locationId" to "other_warehouse"))
                }
                if (location.active != true) {
                    throw ValidationException("Location is inactive", mapOf("lines[$index].locationId" to "inactive"))
                }
                line.purchaseOrderLineId?.let { orderLineId ->
                    val orderLine = orderLines.firstOrNull { it.id == orderLineId }
                    if (orderLine != null) {
                        val remaining = (orderLine.quantity - wmsRepository.acceptedQuantityForOrderLine(orderLineId))
                            .max(BigDecimal.ZERO)
                        if (received > remaining) {
                            throw ConflictException(
                                "Received quantity ${received.toPlainString()} exceeds the order line remainder ${remaining.toPlainString()}"
                            )
                        }
                    }
                }
                val expected = line.expectedQuantity
                val kind: String?
                val diff: BigDecimal?
                when {
                    received > expected -> { kind = ReceiptDiscrepancyKind.SURPLUS.name; diff = received - expected }
                    received < expected -> { kind = ReceiptDiscrepancyKind.SHORTAGE.name; diff = expected - received }
                    else -> { kind = null; diff = null }
                }
                val reason = input.discrepancyReason?.trim()?.takeIf { it.isNotEmpty() }
                if (kind != null && reason == null) {
                    throw ValidationException("Discrepancy requires a reason", mapOf("lines[$index].discrepancyReason" to "required"))
                }
                val nomenclature = wmsRepository.findNomenclatureById(line.nomenclatureId)
                    ?: throw NotFoundException("nomenclature", line.nomenclatureId)
                val lotNumber = input.lotNumber?.trim()?.takeIf { it.isNotEmpty() }
                if (nomenclature.lotTracked == true && received.signum() > 0 && lotNumber == null) {
                    throw ValidationException("Lot number is required for lot-tracked nomenclature", mapOf("lines[$index].lotNumber" to "required"))
                }
                val lotId = lotNumber?.let {
                    (wmsRepository.findLot(nomenclature.id, it) ?: wmsRepository.createLot(nomenclature.id, it, currentUser.userId)).id
                }
                dsl.update(RECEIPT_LINES)
                    .set(RECEIPT_LINES.RECEIVED_QUANTITY, received)
                    .set(RECEIPT_LINES.LOCATION_ID, location.id)
                    .set(RECEIPT_LINES.LOT_ID, lotId)
                    .set(RECEIPT_LINES.DISCREPANCY_KIND, kind)
                    .set(RECEIPT_LINES.DISCREPANCY_QUANTITY, diff?.setScale(4, RoundingMode.HALF_UP))
                    .set(RECEIPT_LINES.DISCREPANCY_REASON, if (kind != null) reason else null)
                    .where(RECEIPT_LINES.ID.eq(line.id))
                    .execute()
            }
            val updated = dsl.update(RECEIPTS)
                .set(RECEIPTS.VERSION, RECEIPTS.VERSION.plus(1))
                .set(RECEIPTS.STATUS, "IN_PROGRESS")
                .where(RECEIPTS.ID.eq(id))
                .and(RECEIPTS.VERSION.eq(request.version))
                .execute()
            if (updated == 0) throw ConflictException("Receipt was modified concurrently")
            auditService.log(
                currentUser.userId, "ACCEPT_LINES", "RECEIPT", id,
                mapOf("lines" to request.lines.size)
            )
            build(wmsRepository.findReceiptById(id)!!)
        })
    }

    fun quickReceiveOrder(request: QuickReceiveOrderRequest, currentUser: CurrentUser): ReceiptDto {
        currentUser.requirePermission("wms", "post")
        val warehouse = wmsRepository.findWarehouseById(request.warehouseId)
            ?: throw NotFoundException("warehouse", request.warehouseId)
        if (warehouse.active != true) {
            throw ValidationException("Warehouse is inactive", mapOf("warehouseId" to "inactive"))
        }
        val location = wmsRepository.findLocationById(request.locationId)
            ?: throw NotFoundException("storage_location", request.locationId)
        if (location.warehouseId != warehouse.id) {
            throw ValidationException(
                "Location does not belong to the receipt warehouse",
                mapOf("locationId" to "other_warehouse")
            )
        }
        if (location.active != true) {
            throw ValidationException("Location is inactive", mapOf("locationId" to "inactive"))
        }

        val (postedDto, events) = dsl.transactionResult(TransactionalCallable {
            val order = purchaseRepository.lockOrderById(request.orderId)
                ?: throw NotFoundException("purchase_order", request.orderId)
            if (order.status !in setOf("SENT", "PARTIALLY_DELIVERED", "DELIVERED")) {
                throw ConflictException(
                    "Quick receive is only allowed for SENT, PARTIALLY_DELIVERED or DELIVERED order; " +
                        "order ${order.number} is ${order.status}"
                )
            }
            val existingDraft = wmsRepository.findDraftReceiptForOrder(order.id, warehouse.id)
            val draftLines = existingDraft?.let { wmsRepository.listReceiptLines(it.id) }.orEmpty()
            val canReuseDraft = existingDraft != null &&
                existingDraft.status == "DRAFT" &&
                draftLines.isNotEmpty() &&
                draftLines.all { it.locationId == null }
            if (canReuseDraft) {
                requireQuickReceivableNomenclature(draftLines.map { it.nomenclatureId })
                wmsRepository.prepareDraftLinesForQuickReceive(existingDraft!!.id, location.id)
                postReceiptInTx(existingDraft.id, existingDraft.version ?: 0L, currentUser)
            } else {
                val orderLines = wmsRepository.listPurchaseOrderLines(order.id)
                val remainingLines = orderLines.mapNotNull { orderLine ->
                    val accepted = wmsRepository.acceptedQuantityForOrderLine(orderLine.id)
                    val remaining = (orderLine.quantity - accepted).max(BigDecimal.ZERO)
                    if (remaining.signum() > 0) orderLine to remaining else null
                }
                if (remainingLines.isEmpty()) {
                    throw ConflictException("Order ${order.number} has no remaining quantity to receive")
                }
                requireQuickReceivableNomenclature(remainingLines.map { it.first.nomenclatureId })
                val sourceDocNo = nextOrderReceiptSourceDocNo(order.number)
                val receipt = wmsRepository.createReceipt(
                    number = wmsRepository.generateReceiptNumber(),
                    sourceType = ReceiptSourceType.PURCHASE_ORDER.name,
                    sourceRef = null,
                    sourceDocumentNo = sourceDocNo,
                    sourceDocumentDate = null,
                    purchaseOrderId = order.id,
                    purchaseDeliveryId = null,
                    warehouseId = warehouse.id,
                    expectedAt = null,
                    note = request.note?.trim()?.takeIf { it.isNotEmpty() },
                    createdBy = currentUser.userId
                )
                remainingLines.forEachIndexed { index, (orderLine, remaining) ->
                    wmsRepository.insertReceiptLine(
                        receiptId = receipt.id,
                        lineNo = index + 1,
                        nomenclatureId = orderLine.nomenclatureId,
                        expectedQuantity = remaining,
                        receivedQuantity = remaining,
                        lotId = null,
                        locationId = location.id,
                        note = null,
                        purchaseOrderLineId = orderLine.id
                    )
                }
                auditService.log(
                    currentUser.userId, "CREATE", "RECEIPT", receipt.id,
                    mapOf(
                        "number" to receipt.number,
                        "sourceType" to ReceiptSourceType.PURCHASE_ORDER.name,
                        "sourceDocumentNo" to sourceDocNo,
                        "purchaseOrderId" to order.id,
                        "lines" to remainingLines.size,
                        "quickReceive" to true
                    )
                )
                postReceiptInTx(receipt.id, receipt.version ?: 0L, currentUser)
            }
        })
        notificationService.publishEvents(events)
        return postedDto
    }

    fun receiveOrderItems(request: ReceiveOrderItemsRequest, currentUser: CurrentUser): ReceiptDto {
        currentUser.requirePermission("wms", "post")
        if (request.lines.isEmpty()) {
            throw ValidationException("Укажите хотя бы одну позицию к приёмке", mapOf("lines" to "required"))
        }
        val orderLineIds = request.lines.map { it.orderLineId }
        if (orderLineIds.distinct().size != orderLineIds.size) {
            throw ValidationException("Дублирующиеся строки заказа в приёмке", mapOf("lines" to "duplicate_order_line"))
        }
        val warehouse = wmsRepository.findWarehouseById(request.warehouseId)
            ?: throw NotFoundException("warehouse", request.warehouseId)
        if (warehouse.active != true) {
            throw ValidationException("Warehouse is inactive", mapOf("warehouseId" to "inactive"))
        }
        val location = wmsRepository.findLocationById(request.locationId)
            ?: throw NotFoundException("storage_location", request.locationId)
        if (location.warehouseId != warehouse.id) {
            throw ValidationException(
                "Location does not belong to the receipt warehouse",
                mapOf("locationId" to "other_warehouse")
            )
        }
        if (location.active != true) {
            throw ValidationException("Location is inactive", mapOf("locationId" to "inactive"))
        }
        val parsedLines = request.lines.mapIndexed { index, input ->
            input.orderLineId to parseQuantity(input.receivedQuantity, "lines[$index].receivedQuantity", allowZero = true)
        }
        val positiveInputs = parsedLines.filter { it.second.signum() > 0 }
        if (positiveInputs.isEmpty()) {
            throw ValidationException(
                "Укажите принятое количество больше нуля хотя бы по одной позиции",
                mapOf("lines" to "nothing_received")
            )
        }

        val (postedDto, events) = dsl.transactionResult(TransactionalCallable {
            val order = purchaseRepository.lockOrderById(request.orderId)
                ?: throw NotFoundException("purchase_order", request.orderId)
            if (order.status !in receiptAllowedOrderStatuses) {
                throw ConflictException(
                    "Приёмка разрешена только для заказов в статусе SENT, PARTIALLY_DELIVERED или DELIVERED; " +
                        "текущий статус: ${order.status}"
                )
            }
            val orderLinesById = wmsRepository.listPurchaseOrderLines(order.id).associateBy { it.id }
            val prepared = positiveInputs.mapIndexed { idx, (orderLineId, qty) ->
                val orderLine = orderLinesById[orderLineId]
                    ?: throw ValidationException(
                        "Строка заказа $orderLineId не принадлежит заказу ${order.number}",
                        mapOf("lines[$idx].orderLineId" to "not_in_order")
                    )
                val acceptedBefore = wmsRepository.acceptedQuantityForOrderLine(orderLine.id)
                val remaining = (orderLine.quantity - acceptedBefore).max(BigDecimal.ZERO)
                if (qty > remaining) {
                    throw ConflictException(
                        "Принимаемое количество (${qty.stripTrailingZeros().toPlainString()}) превышает непринятый остаток " +
                            "(${remaining.stripTrailingZeros().toPlainString()}) по строке ${orderLine.lineNo}"
                    )
                }
                orderLine to qty
            }
            requireQuickReceivableNomenclature(prepared.map { it.first.nomenclatureId })
            val sourceDocNo = nextOrderReceiptSourceDocNo(order.number)
            val receipt = wmsRepository.createReceipt(
                number = wmsRepository.generateReceiptNumber(),
                sourceType = ReceiptSourceType.PURCHASE_ORDER.name,
                sourceRef = null,
                sourceDocumentNo = sourceDocNo,
                sourceDocumentDate = null,
                purchaseOrderId = order.id,
                purchaseDeliveryId = null,
                warehouseId = warehouse.id,
                expectedAt = null,
                note = request.note?.trim()?.takeIf { it.isNotEmpty() },
                createdBy = currentUser.userId
            )
            prepared.forEachIndexed { index, (orderLine, qty) ->
                wmsRepository.insertReceiptLine(
                    receiptId = receipt.id,
                    lineNo = index + 1,
                    nomenclatureId = orderLine.nomenclatureId,
                    expectedQuantity = qty,
                    receivedQuantity = qty,
                    lotId = null,
                    locationId = location.id,
                    note = null,
                    purchaseOrderLineId = orderLine.id
                )
            }
            auditService.log(
                currentUser.userId, "CREATE", "RECEIPT", receipt.id,
                mapOf(
                    "number" to receipt.number,
                    "sourceType" to ReceiptSourceType.PURCHASE_ORDER.name,
                    "sourceDocumentNo" to sourceDocNo,
                    "purchaseOrderId" to order.id,
                    "lines" to prepared.size,
                    "receiveOrderItems" to true
                )
            )
            postReceiptInTx(receipt.id, receipt.version ?: 0L, currentUser)
        })
        notificationService.publishEvents(events)
        return postedDto
    }

    private fun requireQuickReceivableNomenclature(nomenclatureIds: Collection<Long>) {
        nomenclatureIds.distinct().forEach { nomId ->
            val nom = wmsRepository.findNomenclatureById(nomId)
                ?: throw NotFoundException("nomenclature", nomId)
            if (nom.lotTracked == true || nom.serialTracked == true) {
                throw ConflictException(
                    "Позиция ${nom.code} требует ввода номера партии или серийного номера — " +
                        "используйте «Принять частично / с расхождением»"
                )
            }
        }
    }

    private fun nextOrderReceiptSourceDocNo(orderNumber: String): String {
        if (wmsRepository.findReceiptBySourceDocument(ReceiptSourceType.PURCHASE_ORDER.name, orderNumber) == null) {
            return orderNumber
        }
        var n = 2
        while (wmsRepository.findReceiptBySourceDocument(ReceiptSourceType.PURCHASE_ORDER.name, "$orderNumber-R$n") != null) {
            n++
        }
        return "$orderNumber-R$n"
    }

    // -----------------------------------------------------------------------
    // Проведение
    // -----------------------------------------------------------------------

    /**
     * Проведение атомарно: движения, остаток и статус в одной транзакции.
     * Повторное проведение и конкурентное изменение отклоняются.
     */
    fun postReceipt(id: Long, request: PostReceiptRequest, currentUser: CurrentUser): ReceiptDto {
        currentUser.requirePermission("wms", "post")
        val (dto, events) = dsl.transactionResult(TransactionalCallable {
            postReceiptInTx(id, request.version, currentUser)
        })
        notificationService.publishEvents(events)
        return dto
    }

    private fun postReceiptInTx(
        id: Long,
        expectedVersion: Long,
        currentUser: CurrentUser
    ): Pair<ReceiptDto, List<NotificationEventBus.Event>> {
        val receipt = wmsRepository.lockReceiptById(id) ?: throw NotFoundException("receipt", id)
        if (receipt.version != expectedVersion) {
            throw ConflictException("Receipt was modified concurrently")
        }
        if (receipt.status == "POSTED") {
            throw ConflictException("Receipt is already posted")
        }
        if (receipt.status != "DRAFT" && receipt.status != "IN_PROGRESS") {
            throw ConflictException("Receipt cannot be posted from status ${receipt.status}")
        }
        val lines = wmsRepository.listReceiptLines(id)
        if (lines.isEmpty()) {
            throw ValidationException("Receipt has no lines", mapOf("lines" to "required"))
        }
        if (lines.none { it.receivedQuantity.signum() > 0 }) {
            throw ValidationException(
                "Receipt has nothing received; zero posting would create a false document",
                mapOf("lines" to "nothing_received")
            )
        }
        // Поступление от снабжения приходит без ячеек: проводить его можно
        // только после того, как склад разместил каждую принятую строку.
        lines.firstOrNull { it.receivedQuantity.signum() > 0 && it.locationId == null }?.let { line ->
            throw ValidationException(
                "Storage location is required for line ${line.lineNo} before posting",
                mapOf("lines" to "location_required")
            )
        }

        // Приёмка по заказу проверяется ещё раз при проведении: между
        // созданием черновика и проведением могла пройти другая приёмка.
        val orderIds = lines.mapNotNull { it.purchaseOrderLineId }
        if (receipt.purchaseOrderId != null || orderIds.isNotEmpty()) {
            verifyOrderRemainders(receipt, lines)
        }

        lines.filter { it.receivedQuantity.signum() > 0 }.forEach { line ->
            val nomenclature = wmsRepository.findNomenclatureById(line.nomenclatureId)
                ?: throw NotFoundException("nomenclature", line.nomenclatureId)
            if (nomenclature.archived == true) {
                throw ConflictException("Nomenclature is archived: ${nomenclature.code}")
            }
            wmsRepository.insertMovement(
                movementType = "RECEIPT",
                nomenclatureId = line.nomenclatureId,
                lotId = line.lotId,
                serialId = null,
                fromLocationId = null,
                toLocationId = line.locationId,
                quantity = line.receivedQuantity,
                documentType = "RECEIPT",
                documentId = receipt.id,
                note = "Приёмка ${receipt.number}",
                createdBy = currentUser.userId
            )
            wmsRepository.increasePhysical(
                warehouseId = receipt.warehouseId,
                locationId = line.locationId!!,
                nomenclatureId = line.nomenclatureId,
                lotId = line.lotId,
                serialId = null,
                quantity = line.receivedQuantity
            )
        }

        // Авторезерв под проект выполняется до перевода приёмки в POSTED,
        // т. к. триггер trg_receipt_lines_posted_guard запрещает менять строки после проведения.
        if (receipt.purchaseOrderId != null || orderIds.isNotEmpty()) {
            autoReserveForProjectReceiptInTx(receipt, lines, currentUser)
        }

        val posted = wmsRepository.postReceipt(id, expectedVersion, currentUser.userId)
            ?: throw ConflictException("Receipt was modified concurrently")

        // Приёмка по поставке закрывает соответствующие количества поставки:
        // связь «поставка 1:N приёмки» и статус заказа пересчитываются.
        receipt.purchaseDeliveryId?.let { deliveryId ->
            recalculateDeliveryAcceptance(deliveryId, currentUser)
        }
        receipt.purchaseOrderId?.let { orderId ->
            purchaseRepository.insertOrderHistory(
                orderId, "RECEIPT_POSTED", null, null,
                "Приёмка ${receipt.number}", currentUser.userId
            )
        }
        val notifications = if (receipt.purchaseOrderId != null || orderIds.isNotEmpty()) {
            syncOrderAndDemandAfterReceipt(posted, lines, currentUser)
        } else {
            emptyList()
        }
        auditService.log(
            currentUser.userId, "POST", "RECEIPT", id,
            mapOf(
                "number" to receipt.number,
                "lines" to lines.size,
                "discrepancies" to lines.count { it.discrepancyKind != null }
            )
        )
        return build(posted) to notifications
    }

    private fun syncOrderAndDemandAfterReceipt(
        posted: ReceiptsRecord,
        receiptLines: List<ReceiptLinesRecord>,
        currentUser: CurrentUser
    ): List<NotificationEventBus.Event> {
        val affectedOrderLineIds = receiptLines.mapNotNull { it.purchaseOrderLineId }.distinct()
        val lineOrderIds = mutableSetOf<Long>()
        posted.purchaseOrderId?.let { lineOrderIds += it }

        affectedOrderLineIds.forEach { orderLineId ->
            val orderLine = purchaseRepository.lockOrderLineById(orderLineId) ?: return@forEach
            lineOrderIds += orderLine.orderId
            val acceptedTotal = wmsRepository.acceptedQuantityForOrderLine(orderLineId, null)
            val newDelivered = orderLine.deliveredQuantity.max(acceptedTotal).min(orderLine.quantity)
            if (newDelivered.compareTo(orderLine.deliveredQuantity) != 0) {
                purchaseRepository.updateDeliveredQuantity(orderLine.id, newDelivered)
            }
        }

        val affectedOrderIds = lineOrderIds.toList()
        affectedOrderIds.forEach { orderId ->
            val order = purchaseRepository.lockOrderById(orderId) ?: return@forEach
            val allOrderLines = purchaseRepository.listOrderLines(orderId)
            if (order.status in listOf("SENT", "PARTIALLY_DELIVERED")) {
                val allDelivered = allOrderLines.isNotEmpty() && allOrderLines.all { it.deliveredQuantity >= it.quantity }
                val anyDelivered = allOrderLines.any { it.deliveredQuantity > BigDecimal.ZERO }
                val newStatus = when {
                    allDelivered -> "DELIVERED"
                    anyDelivered && order.status == "SENT" -> "PARTIALLY_DELIVERED"
                    else -> null
                }
                if (newStatus != null && newStatus != order.status) {
                    purchaseRepository.updateOrderStatus(
                        id = order.id,
                        expectedVersion = order.version,
                        fromStatuses = listOf("SENT", "PARTIALLY_DELIVERED"),
                        newStatus = newStatus,
                        updatedBy = currentUser.userId
                    )
                    purchaseRepository.insertOrderHistory(
                        order.id, "RECEIPT_DELIVERED", order.status, newStatus,
                        "Приёмка ${posted.number}", currentUser.userId
                    )
                }
            }
        }

        val demandLineIds = wmsRepository.findDemandLineIdsForOrderLines(affectedOrderLineIds)
        val touchedDemandIds = mutableSetOf<Long>()
        touchedDemandIds += wmsRepository.findDemandIdsForOrders(affectedOrderIds)

        demandLineIds.forEach { demandLineId ->
            val demandLine = wmsRepository.lockDemandLineById(demandLineId) ?: return@forEach
            touchedDemandIds += demandLine.demandId
            if (demandLine.status == "CANCELLED") return@forEach
            val acceptedForDemandLine = wmsRepository.acceptedQuantityForDemandLine(demandLineId)
            val newCovered = (demandLine.stockReservedQuantity + acceptedForDemandLine).min(demandLine.quantity)
            val newLineStatus = when {
                newCovered >= demandLine.quantity -> "COVERED"
                newCovered > BigDecimal.ZERO -> "PARTIALLY_COVERED"
                else -> "OPEN"
            }
            if (newCovered.compareTo(demandLine.coveredQuantity) != 0 || newLineStatus != demandLine.status) {
                wmsRepository.updateDemandLineCoverage(demandLine.id, newCovered, newLineStatus)
                wmsRepository.insertDemandLineHistory(
                    demandLineId = demandLine.id,
                    action = "COVERAGE_CHANGED",
                    quantityBefore = demandLine.coveredQuantity,
                    quantityAfter = newCovered,
                    fromStatus = demandLine.status,
                    toStatus = newLineStatus,
                    reason = "Приёмка ${posted.number}",
                    changedBy = currentUser.userId
                )
            }
        }

        val events = mutableListOf<NotificationEventBus.Event>()
        touchedDemandIds.forEach { demandId ->
            val demand = wmsRepository.lockDemandById(demandId) ?: return@forEach
            if (demand.status != "CANCELLED") {
                val demandLines = wmsRepository.listDemandLinesByDemand(demandId)
                    .filter { it.status != "CANCELLED" }
                val allCovered = demandLines.isNotEmpty() && demandLines.all { it.status == "COVERED" }
                val anyCovered = demandLines.any { it.coveredQuantity > BigDecimal.ZERO }
                val newDemandStatus = when {
                    allCovered -> "COVERED"
                    anyCovered -> "PARTIALLY_COVERED"
                    else -> demand.status
                }
                if (newDemandStatus != demand.status) {
                    wmsRepository.updateDemandStatusAfterReceipt(demand.id, newDemandStatus, currentUser.userId)
                    wmsRepository.insertDemandHistory(
                        demandId = demand.id,
                        action = "STATUS_CHANGED",
                        fromStatus = demand.status,
                        toStatus = newDemandStatus,
                        reason = "Приёмка ${posted.number}",
                        changedBy = currentUser.userId
                    )
                }
            }
            val recipients = (listOf(demand.initiatorId) + wmsRepository.findOrderProcurementRecipients(demandId, affectedOrderIds))
                .distinct()
                .filter { it != currentUser.userId }
            if (recipients.isNotEmpty()) {
                events += notificationService.createSystemNotifications(
                    recipientIds = recipients,
                    type = "DEMAND_RECEIPT_POSTED",
                    entityType = "DEMAND",
                    entityId = demand.id,
                    title = "Товар по заявке ${demand.number} принят на склад (${posted.number})"
                )
            }
        }
        return events
    }

    /**
     * Остаток по заказу пересчитывается на момент проведения: сумма всех
     * проведённых приёмок по позиции не может превысить заказанное.
     */
    private fun verifyOrderRemainders(receipt: ReceiptsRecord, lines: List<ReceiptLinesRecord>) {
        val byOrderLine = lines
            .filter { it.purchaseOrderLineId != null && it.receivedQuantity.signum() > 0 }
            .groupBy { it.purchaseOrderLineId!! }
        byOrderLine.forEach { (orderLineId, receiptLines) ->
            val orderLine = purchaseRepository.lockOrderLineById(orderLineId)
                ?: throw NotFoundException("purchase_order_line", orderLineId)
            val currentReceiptId = receipt.id
            val acceptedBefore = wmsRepository.acceptedQuantityForOrderLine(orderLineId, currentReceiptId)
            val incoming = receiptLines.fold(BigDecimal.ZERO) { total, line ->
                total + line.receivedQuantity
            }
            val total = acceptedBefore + incoming
            if (total > orderLine.quantity) {
                throw ConflictException(
                    "Acceptance would exceed the ordered quantity for line ${orderLine.lineNo}: " +
                        "ordered ${orderLine.quantity.toPlainString()}, " +
                        "accepted ${acceptedBefore.toPlainString()}, " +
                        "incoming ${incoming.toPlainString()}"
                )
            }
        }
    }

    /**
     * После проведения приёмки по поставке согласуем количества поставки:
     * принятое по приёмкам не может превышать количество поставки, а заказ
     * переходит в DELIVERED/PARTIALLY_DELIVERED по фактически принятому.
     */
    private fun recalculateDeliveryAcceptance(deliveryId: Long, currentUser: CurrentUser) {
        val delivery: PurchaseDeliveriesRecord = purchaseRepository.lockDeliveryById(deliveryId)
            ?: throw NotFoundException("purchase_delivery", deliveryId)
        if (!deliveryAcceptedWithinLimits(delivery)) {
            throw ConflictException(
                "Accepted quantity exceeds the delivered quantity of delivery ${delivery.number}"
            )
        }
        purchaseRepository.insertOrderHistory(
            delivery.orderId, "DELIVERY_ACCEPTED", null, null,
            "Приёмка по поставке ${delivery.number}", currentUser.userId
        )
    }

    private fun deliveryAcceptedWithinLimits(delivery: PurchaseDeliveriesRecord): Boolean {
        val accepted = wmsRepository.acceptedQuantitiesForDelivery(delivery.id)
        if (accepted.isEmpty()) return true
        val deliveryLines = purchaseRepository.listDeliveryLines(delivery.id).associateBy { it.orderLineId }
        return accepted.all { (orderLineId, quantity) ->
            val delivered = deliveryLines[orderLineId]?.quantity ?: BigDecimal.ZERO
            quantity <= delivered
        }
    }

    // -----------------------------------------------------------------------
    // Поздняя связь
    // -----------------------------------------------------------------------

    /**
     * Связывание уже проведённой приёмки с заказом или накладной, которые
     * появились позже. Остаток не пересоздаётся: движения документа не
     * дублируются, меняется только учётная связь, и это видно в журнале.
     */
    fun linkReceipt(id: Long, request: LinkReceiptRequest, currentUser: CurrentUser): ReceiptDto {
        currentUser.requirePermission("wms", "post")
        if (request.reason.isBlank()) {
            throw ValidationException("Link reason is required", mapOf("reason" to "required"))
        }
        if (request.purchaseOrderId == null &&
            request.purchaseDeliveryId == null &&
            request.sourceDocumentNo.isNullOrBlank()
        ) {
            throw ValidationException(
                "Link must reference a purchase order, a delivery or a document number",
                mapOf("purchaseOrderId" to "required")
            )
        }
        return dsl.transactionResult(TransactionalCallable {
            val receipt = wmsRepository.lockReceiptById(id) ?: throw NotFoundException("receipt", id)
            if (receipt.version != request.version) {
                throw ConflictException("Receipt was modified concurrently")
            }
            if (receipt.status != "POSTED") {
                throw ConflictException("Only a posted receipt can be linked later")
            }
            // Ссылка на заказ и поставку допустима только для приёмки по закупке:
            // начальный остаток, возврат и перемещение к заказу поставщику не относятся.
            if ((request.purchaseOrderId != null || request.purchaseDeliveryId != null) &&
                ReceiptSourceType.valueOf(receipt.sourceType) !in purchaseSourceTypes
            ) {
                throw ValidationException(
                    "Only a receipt based on a purchase order, invoice or delivery note can be linked to an order",
                    mapOf("purchaseOrderId" to "wrong_source_type")
                )
            }
            // Повторная привязка тех же документов запрещена: связь пишется один раз.
            val existing = wmsRepository.listReceiptLinks(id)
            val alreadyLinked = existing.any { link ->
                (request.purchaseOrderId != null && link.purchaseOrderId == request.purchaseOrderId) ||
                    (request.purchaseDeliveryId != null && link.purchaseDeliveryId == request.purchaseDeliveryId)
            }
            if (alreadyLinked) {
                throw ConflictException("Receipt is already linked to these documents")
            }
            if (receipt.purchaseOrderId != null && request.purchaseOrderId != null) {
                throw ConflictException("Receipt already has a purchase order link")
            }
            if (receipt.purchaseDeliveryId != null && request.purchaseDeliveryId != null) {
                throw ConflictException("Receipt already has a delivery link")
            }
            if (receipt.sourceDocumentNo != null && !request.sourceDocumentNo.isNullOrBlank()) {
                throw ConflictException("Receipt already has a source document number")
            }
            val order = request.purchaseOrderId?.let { orderId ->
                purchaseRepository.findOrderById(orderId)
                    ?: throw NotFoundException("purchase_order", orderId)
            }
            val delivery = request.purchaseDeliveryId?.let { deliveryId ->
                purchaseRepository.findDeliveryById(deliveryId)
                    ?: throw NotFoundException("purchase_delivery", deliveryId)
            }
            if (order != null && delivery != null && delivery.orderId != order.id) {
                throw ValidationException(
                    "Delivery belongs to another purchase order",
                    mapOf("purchaseDeliveryId" to "other_order")
                )
            }
            val documentNo = request.sourceDocumentNo?.trim()?.takeIf { it.isNotEmpty() }
            if (documentNo != null) {
                if (documentNo == receipt.sourceDocumentNo) {
                    throw ConflictException("Receipt is already linked to document $documentNo")
                }
                wmsRepository.findReceiptBySourceDocument(receipt.sourceType, documentNo)
                    ?.takeIf { it.id != receipt.id }
                    ?.let { other ->
                        throw ConflictException(
                            "Document $documentNo is already used by receipt ${other.number}"
                        )
                    }
            }
            val updated = wmsRepository.updateReceiptLinks(
                id = id,
                expectedVersion = request.version,
                purchaseOrderId = order?.id,
                purchaseDeliveryId = delivery?.id,
                sourceDocumentNo = documentNo,
                updatedBy = currentUser.userId
            ) ?: throw ConflictException("Receipt was modified concurrently")
            wmsRepository.insertReceiptLink(
                receiptId = id,
                purchaseOrderId = order?.id,
                purchaseDeliveryId = delivery?.id,
                sourceDocumentNo = documentNo,
                reason = request.reason.trim(),
                linkedBy = currentUser.userId
            )
            order?.let {
                purchaseRepository.insertOrderHistory(
                    it.id, "RECEIPT_LINKED", null, null,
                    "Приёмка ${receipt.number}: ${request.reason.trim()}", currentUser.userId
                )
            }
            auditService.log(
                currentUser.userId, "LINK", "RECEIPT", id,
                mapOf(
                    "number" to receipt.number,
                    "purchaseOrderId" to order?.id,
                    "purchaseDeliveryId" to delivery?.id,
                    "documentNo" to documentNo,
                    "reason" to request.reason.trim()
                )
            )
            build(updated)
        })
    }

    // -----------------------------------------------------------------------
    // Служебное
    // -----------------------------------------------------------------------

    private fun autoReserveForProjectReceiptInTx(
        receipt: ReceiptsRecord,
        lines: List<ReceiptLinesRecord>,
        currentUser: CurrentUser
    ) {
        val orderLineContexts = wmsRepository.findOrderLineProjectContext(lines.mapNotNull { it.purchaseOrderLineId })
        val fallbackOrderContext = receipt.purchaseOrderId?.let { wmsRepository.findOrderProjectContext(it) }
        lines.filter { it.receivedQuantity.signum() > 0 }.forEach { line ->
            val ctx = line.purchaseOrderLineId?.let { orderLineContexts[it] } ?: fallbackOrderContext ?: return@forEach
            val locationId = line.locationId ?: return@forEach
            val location = wmsRepository.findLocationById(locationId) ?: return@forEach
            if (location.purpose == "QUARANTINE" || location.purpose == "DEFECT") return@forEach
            val totalRequired = wmsRepository.projectRequiredTotal(ctx.projectId, line.nomenclatureId)
            val currentReserved = wmsRepository.projectReservedTotal(ctx.projectId, line.nomenclatureId)
            val needToReserve = (totalRequired - currentReserved).max(BigDecimal.ZERO)
            if (needToReserve.signum() <= 0) return@forEach
            val balance = wmsRepository.lockBalance(
                warehouseId = receipt.warehouseId,
                locationId = locationId,
                nomenclatureId = line.nomenclatureId,
                lotId = line.lotId,
                serialId = null
            ) ?: return@forEach
            val freeInBalance = (balance.physicalQty - balance.reservedQty).max(BigDecimal.ZERO)
            val autoReserveQty = line.receivedQuantity.min(needToReserve).min(freeInBalance)
            if (autoReserveQty.signum() <= 0) return@forEach
            if (wmsRepository.addReserved(balance.id, autoReserveQty) == 0) {
                throw ConflictException("Stock balance was modified concurrently")
            }
            val reasonNote = "Авторезерв под проект при приёмке ${receipt.number}"
            val reservation = wmsRepository.createReservation(
                number = wmsRepository.generateReservationNumber(),
                reasonType = "PROJECT",
                projectId = ctx.projectId,
                taskId = null,
                reasonNote = reasonNote,
                nomenclatureId = line.nomenclatureId,
                warehouseId = receipt.warehouseId,
                locationId = locationId,
                lotId = line.lotId,
                serialId = null,
                quantity = autoReserveQty,
                ownerId = ctx.initiatorId,
                expiresAt = null,
                createdBy = currentUser.userId
            )
            wmsRepository.insertReservationHistory(
                reservationId = reservation.id,
                action = "CREATED",
                fromStatus = null,
                toStatus = "ACTIVE",
                quantityBefore = null,
                quantityAfter = autoReserveQty,
                reason = reasonNote,
                changedBy = currentUser.userId
            )
            wmsRepository.updateReceiptLineAutoReserved(line.id, autoReserveQty)
            auditService.log(
                currentUser.userId, "AUTO_RESERVE", "RESERVATION", reservation.id,
                mapOf(
                    "number" to reservation.number,
                    "projectId" to ctx.projectId,
                    "receiptId" to receipt.id,
                    "nomenclatureId" to line.nomenclatureId,
                    "quantity" to autoReserveQty.stripTrailingZeros().toPlainString()
                )
            )
        }
    }

    private fun build(receipt: ReceiptsRecord): ReceiptDto {
        val lines = wmsRepository.listReceiptLines(receipt.id)
        val links = wmsRepository.listReceiptLinks(receipt.id)
        val order = receipt.purchaseOrderId?.let { purchaseRepository.findOrderById(it) }
        val delivery = receipt.purchaseDeliveryId?.let { purchaseRepository.findDeliveryById(it) }
        val projectReservations = if (receipt.status == "POSTED" &&
            (receipt.purchaseOrderId != null || lines.any { it.purchaseOrderLineId != null })
        ) {
            buildProjectReservationSummaries(receipt, lines)
        } else {
            emptyList()
        }
        return ReceiptDto(
            id = receipt.id,
            number = receipt.number,
            sourceType = ReceiptSourceType.valueOf(receipt.sourceType),
            sourceRef = receipt.sourceRef,
            sourceDocumentNo = receipt.sourceDocumentNo,
            sourceDocumentDate = receipt.sourceDocumentDate?.toString(),
            purchaseOrderId = receipt.purchaseOrderId,
            purchaseOrderNumber = order?.number,
            purchaseDeliveryId = receipt.purchaseDeliveryId,
            purchaseDeliveryNumber = delivery?.number,
            warehouseId = receipt.warehouseId,
            status = ReceiptStatus.valueOf(receipt.status ?: "DRAFT"),
            expectedAt = receipt.expectedAt?.toString(),
            note = receipt.note,
            postedAt = receipt.postedAt?.toString(),
            postedBy = receipt.postedBy,
            createdAt = receipt.createdAt?.toString(),
            createdBy = receipt.createdBy,
            version = receipt.version ?: 0,
            lines = lines.map { it.toDto() },
            links = links.map { link ->
                link.toDto().copy(
                    purchaseOrderNumber = link.purchaseOrderId?.let { purchaseRepository.findOrderById(it)?.number },
                    purchaseDeliveryNumber = link.purchaseDeliveryId?.let { purchaseRepository.findDeliveryById(it)?.number }
                )
            },
            hasDiscrepancies = lines.any { it.discrepancyKind != null },
            supplierInvoiceId = receipt.supplierInvoiceId,
            supplierInvoiceNumber = receipt.supplierInvoiceId?.let { receipt.sourceDocumentNo },
            projectReservations = projectReservations
        )
    }

    private fun buildProjectReservationSummaries(
        receipt: ReceiptsRecord,
        lines: List<ReceiptLinesRecord>
    ): List<ReceiptProjectReservationSummaryDto> {
        val orderLineContexts = wmsRepository.findOrderLineProjectContext(lines.mapNotNull { it.purchaseOrderLineId })
        val fallbackOrderContext = receipt.purchaseOrderId?.let { wmsRepository.findOrderProjectContext(it) }
        val orderIdsFromLines = lines.mapNotNull { it.purchaseOrderLineId }
            .mapNotNull { purchaseRepository.findOrderLineById(it)?.orderId }
        val relatedOrderIds = (listOfNotNull(receipt.purchaseOrderId) + orderIdsFromLines).distinct()
        val relatedOrderLines = relatedOrderIds.flatMap { wmsRepository.listPurchaseOrderLines(it) }

        data class GroupKey(val projectId: Long, val nomenclatureId: Long)
        val grouped = linkedMapOf<GroupKey, Pair<WmsRepository.OrderLineProjectContext, MutableList<ReceiptLinesRecord>>>()
        lines.filter { it.receivedQuantity.signum() > 0 }.forEach { line ->
            val ctx = line.purchaseOrderLineId?.let { orderLineContexts[it] } ?: fallbackOrderContext ?: return@forEach
            val key = GroupKey(ctx.projectId, line.nomenclatureId)
            grouped.getOrPut(key) { ctx to mutableListOf() }.second.add(line)
        }
        return grouped.map { (key, pair) ->
            val (ctx, groupLines) = pair
            val nom = wmsRepository.findNomenclatureById(key.nomenclatureId)
            val receivedNow = groupLines.fold(BigDecimal.ZERO) { acc, l -> acc + l.receivedQuantity }
            val autoReservedNow = groupLines.fold(BigDecimal.ZERO) { acc, l -> acc + l.autoReservedQuantity }
            val totalReservedForProject = wmsRepository.projectReservedTotal(key.projectId, key.nomenclatureId)
            val totalRequiredForProject = wmsRepository.projectRequiredTotal(key.projectId, key.nomenclatureId)
            val stillAwaitingOnOrder = relatedOrderLines
                .filter { it.nomenclatureId == key.nomenclatureId }
                .fold(BigDecimal.ZERO) { acc, ol ->
                    val accepted = wmsRepository.acceptedQuantityForOrderLine(ol.id)
                    acc + (ol.quantity - accepted).max(BigDecimal.ZERO)
                }
            ReceiptProjectReservationSummaryDto(
                projectId = ctx.projectId,
                projectNumber = ctx.projectNumber,
                projectName = ctx.projectName,
                nomenclatureId = key.nomenclatureId,
                nomenclatureCode = nom?.code ?: "",
                nomenclatureName = nom?.name ?: "",
                unit = nom?.unit ?: "",
                receivedNow = fmtReceiptQty(receivedNow),
                autoReservedNow = fmtReceiptQty(autoReservedNow),
                totalReservedForProject = fmtReceiptQty(totalReservedForProject),
                totalRequiredForProject = fmtReceiptQty(totalRequiredForProject),
                stillAwaitingOnOrder = fmtReceiptQty(stillAwaitingOnOrder)
            )
        }
    }

    private fun fmtReceiptQty(value: BigDecimal): String =
        if (value.compareTo(BigDecimal.ZERO) == 0) "0"
        else value.stripTrailingZeros().toPlainString()

    private fun parseQuantity(value: String, field: String, allowZero: Boolean): BigDecimal =
        try {
            val parsed = BigDecimal(value.trim().replace(" ", "").replace(',', '.')).setScale(4, RoundingMode.HALF_UP)
            if (parsed.signum() < 0 || (!allowZero && parsed.signum() == 0)) {
                throw ValidationException("Quantity is invalid", mapOf(field to "not_positive"))
            }
            parsed
        } catch (_: NumberFormatException) {
            throw ValidationException("Invalid decimal value", mapOf(field to "format"))
        }

    private fun parseDate(value: String, field: String): LocalDate =
        try {
            LocalDate.parse(value.trim())
        } catch (_: DateTimeParseException) {
            throw ValidationException("Invalid ISO date", mapOf(field to "format"))
        }

    private fun parseDateTime(value: String?): OffsetDateTime? {
        if (value.isNullOrBlank()) return null
        return try {
            OffsetDateTime.parse(value.trim())
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private companion object {
        val receiptAllowedOrderStatuses = setOf("SENT", "PARTIALLY_DELIVERED", "DELIVERED")

        /** Основания, которым разрешено ссылаться на заказ поставщику. */
        val purchaseSourceTypes = setOf(
            ReceiptSourceType.PURCHASE_ORDER,
            ReceiptSourceType.DELIVERY_NOTE,
            ReceiptSourceType.INVOICE
        )
    }
}

internal fun ReceiptLinesRecord.toDto(): ReceiptLineDto = ReceiptLineDto(
    id = this.id,
    lineNo = this.lineNo,
    nomenclatureId = this.nomenclatureId,
    expectedQuantity = this.expectedQuantity.toPlainString(),
    receivedQuantity = this.receivedQuantity.toPlainString(),
    lotId = this.lotId,
    locationId = this.locationId,
    note = this.note,
    discrepancyKind = this.discrepancyKind?.let { ReceiptDiscrepancyKind.valueOf(it) },
    discrepancyQuantity = this.discrepancyQuantity?.toPlainString(),
    discrepancyReason = this.discrepancyReason,
    purchaseOrderLineId = this.purchaseOrderLineId,
    autoReservedQuantity = if (this.autoReservedQuantity.compareTo(BigDecimal.ZERO) == 0) {
        "0"
    } else {
        this.autoReservedQuantity.stripTrailingZeros().toPlainString()
    }
)

internal fun ReceiptLinksRecord.toDto(): ReceiptLinkDto = ReceiptLinkDto(
    id = this.id,
    receiptId = this.receiptId,
    purchaseOrderId = this.purchaseOrderId,
    purchaseDeliveryId = this.purchaseDeliveryId,
    sourceDocumentNo = this.sourceDocumentNo,
    reason = this.reason,
    linkedBy = this.linkedBy,
    linkedAt = this.linkedAt?.toString()
)

internal fun Record.toExpectedDto(): ExpectedReceiptLineDto = ExpectedReceiptLineDto(
    orderId = this.get("order_id", Long::class.java),
    orderNumber = this.get("order_number", String::class.java),
    orderStatus = this.get("order_status", String::class.java),
    orderLineId = this.get("order_line_id", Long::class.java),
    lineNo = this.get("line_no", Int::class.java),
    nomenclatureId = this.get("nomenclature_id", Long::class.java),
    orderedQuantity = this.get("ordered_quantity", BigDecimal::class.java).toPlainString(),
    deliveredQuantity = this.get("delivered_quantity", BigDecimal::class.java).toPlainString(),
    acceptedQuantity = this.get("accepted_quantity", BigDecimal::class.java).toPlainString(),
    expectedRemaining = this.get("expected_remaining", BigDecimal::class.java).max(BigDecimal.ZERO).toPlainString(),
    notAcceptedQuantity = this.get("not_accepted_quantity", BigDecimal::class.java).max(BigDecimal.ZERO).toPlainString()
)
