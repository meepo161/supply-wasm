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
        return wmsRepository.expectedReceiptLines(orderId)
            .map { it.toExpectedDto() }
            .filter { it.orderStatus in receiptAllowedOrderStatuses }
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

    // -----------------------------------------------------------------------
    // Проведение
    // -----------------------------------------------------------------------

    /**
     * Проведение атомарно: движения, остаток и статус в одной транзакции.
     * Повторное проведение и конкурентное изменение отклоняются.
     */
    fun postReceipt(id: Long, request: PostReceiptRequest, currentUser: CurrentUser): ReceiptDto {
        currentUser.requirePermission("wms", "post")
        return dsl.transactionResult(TransactionalCallable {
            val receipt = wmsRepository.lockReceiptById(id) ?: throw NotFoundException("receipt", id)
            if (receipt.version != request.version) {
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
            val posted = wmsRepository.postReceipt(id, request.version, currentUser.userId)
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
            auditService.log(
                currentUser.userId, "POST", "RECEIPT", id,
                mapOf(
                    "number" to receipt.number,
                    "lines" to lines.size,
                    "discrepancies" to lines.count { it.discrepancyKind != null }
                )
            )
            build(posted)
        })
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

    private fun build(receipt: ReceiptsRecord): ReceiptDto {
        val lines = wmsRepository.listReceiptLines(receipt.id)
        val links = wmsRepository.listReceiptLinks(receipt.id)
        val order = receipt.purchaseOrderId?.let { purchaseRepository.findOrderById(it) }
        val delivery = receipt.purchaseDeliveryId?.let { purchaseRepository.findDeliveryById(it) }
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
            supplierInvoiceNumber = receipt.supplierInvoiceId?.let { receipt.sourceDocumentNo }
        )
    }

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
    purchaseOrderLineId = this.purchaseOrderLineId
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
