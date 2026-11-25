package org.example.project.repository

import org.example.project.generated.jooq.tables.Demands.DEMANDS
import org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
import org.example.project.generated.jooq.tables.PurchaseDeliveries.PURCHASE_DELIVERIES
import org.example.project.generated.jooq.tables.PurchaseDeliveryLines.PURCHASE_DELIVERY_LINES
import org.example.project.generated.jooq.tables.PurchaseIdempotencyKeys.PURCHASE_IDEMPOTENCY_KEYS
import org.example.project.generated.jooq.tables.PurchaseOrderDemands.PURCHASE_ORDER_DEMANDS
import org.example.project.generated.jooq.tables.PurchaseOrderHistory.PURCHASE_ORDER_HISTORY
import org.example.project.generated.jooq.tables.PurchaseOrderLines.PURCHASE_ORDER_LINES
import org.example.project.generated.jooq.tables.PurchaseOrders.PURCHASE_ORDERS
import org.example.project.generated.jooq.tables.SupplierOfferLines.SUPPLIER_OFFER_LINES
import org.example.project.generated.jooq.tables.SupplierOffers.SUPPLIER_OFFERS
import org.example.project.generated.jooq.tables.Suppliers.SUPPLIERS
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.generated.jooq.tables.records.PurchaseDeliveriesRecord
import org.example.project.generated.jooq.tables.records.PurchaseDeliveryLinesRecord
import org.example.project.generated.jooq.tables.records.PurchaseIdempotencyKeysRecord
import org.example.project.generated.jooq.tables.records.PurchaseOrderHistoryRecord
import org.example.project.generated.jooq.tables.records.PurchaseOrderLinesRecord
import org.example.project.generated.jooq.tables.records.PurchaseOrdersRecord
import org.example.project.generated.jooq.tables.records.SupplierOffersRecord
import org.example.project.generated.jooq.tables.records.SuppliersRecord
import org.jooq.Condition
import org.jooq.DSLContext
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

class PurchaseOrderRepository(private val dsl: DSLContext) {

    data class NewOrderLine(
        val lineNo: Int,
        val nomenclatureId: Long,
        val quantity: BigDecimal,
        val unit: String,
        val unitPrice: BigDecimal,
        val vatRate: BigDecimal,
        val offerLineId: Long?,
        val note: String?
    )

    data class NewDeliveryLine(
        val orderLineId: Long,
        val quantity: BigDecimal,
        val note: String?
    )

    // --- Заказы ---

    fun findOrderById(id: Long): PurchaseOrdersRecord? =
        dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(id)).fetchOne()

    fun lockOrderById(id: Long): PurchaseOrdersRecord? =
        dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.ID.eq(id)).forUpdate().fetchOne()

    fun findOrderByOfferId(offerId: Long): PurchaseOrdersRecord? =
        dsl.selectFrom(PURCHASE_ORDERS).where(PURCHASE_ORDERS.OFFER_ID.eq(offerId)).fetchOne()

    fun listOrders(
        supplierId: Long?,
        status: String?,
        responsibleId: Long?
    ): List<PurchaseOrdersRecord> {
        val conditions = mutableListOf<Condition>()
        supplierId?.let { conditions += PURCHASE_ORDERS.SUPPLIER_ID.eq(it) }
        status?.let { conditions += PURCHASE_ORDERS.STATUS.eq(it) }
        responsibleId?.let { conditions += PURCHASE_ORDERS.RESPONSIBLE_ID.eq(it) }
        return dsl.selectFrom(PURCHASE_ORDERS)
            .where(conditions)
            .orderBy(PURCHASE_ORDERS.ID.desc())
            .fetch()
    }

    fun generateNumber(): String {
        dsl.execute("SELECT pg_advisory_xact_lock(?)", 20_000_000L + 27)
        val year = LocalDate.now().year
        val count = dsl.fetchCount(PURCHASE_ORDERS, PURCHASE_ORDERS.NUMBER.like("PO-$year-%"))
        return "PO-$year-${(count + 1).toString().padStart(5, '0')}"
    }

    fun generateDeliveryNumber(): String {
        dsl.execute("SELECT pg_advisory_xact_lock(?)", 20_000_000L + 28)
        val year = LocalDate.now().year
        val count = dsl.fetchCount(PURCHASE_DELIVERIES, PURCHASE_DELIVERIES.NUMBER.like("PD-$year-%"))
        return "PD-$year-${(count + 1).toString().padStart(5, '0')}"
    }

    fun insertOrder(
        number: String,
        supplierId: Long,
        offerId: Long,
        currency: String,
        deliveryAddress: String?,
        paymentTerms: String?,
        contractNo: String?,
        invoiceNo: String?,
        note: String?,
        responsibleId: Long,
        expectedAt: LocalDate?,
        createdBy: Long,
        status: String = "DRAFT",
        approvedBy: Long? = null,
        approvedAt: OffsetDateTime? = null,
        orderedAt: OffsetDateTime? = null,
        paidAt: OffsetDateTime? = null,
        paidBy: Long? = null
    ): PurchaseOrdersRecord {
        val record = dsl.newRecord(PURCHASE_ORDERS)
        record.number = number
        record.supplierId = supplierId
        record.offerId = offerId
        record.status = status
        record.currency = currency
        record.deliveryAddress = deliveryAddress
        record.paymentTerms = paymentTerms
        record.contractNo = contractNo
        record.invoiceNo = invoiceNo
        record.note = note
        record.responsibleId = responsibleId
        record.expectedAt = expectedAt
        record.approvedBy = approvedBy
        record.approvedAt = approvedAt
        record.orderedAt = orderedAt
        record.paidAt = paidAt
        record.paidBy = paidBy
        record.createdBy = createdBy
        record.store()
        return findOrderById(record.id)!!
    }

    fun markOrderPaid(orderId: Long, paidBy: Long, now: OffsetDateTime): PurchaseOrdersRecord? {
        val order = lockOrderById(orderId) ?: return null
        val set = mutableMapOf<org.jooq.Field<*>, Any?>()
        if (order.status in listOf("DRAFT", "PENDING_APPROVAL", "APPROVED")) {
            set[PURCHASE_ORDERS.STATUS] = "SENT"
            if (order.approvedBy == null) set[PURCHASE_ORDERS.APPROVED_BY] = paidBy
            if (order.approvedAt == null) set[PURCHASE_ORDERS.APPROVED_AT] = now
            if (order.orderedAt == null) set[PURCHASE_ORDERS.ORDERED_AT] = now
            set[PURCHASE_ORDERS.REAPPROVAL_REQUIRED] = false
            set[PURCHASE_ORDERS.REAPPROVAL_REASON] = null
        }
        if (order.paidAt == null) {
            set[PURCHASE_ORDERS.PAID_AT] = now
            set[PURCHASE_ORDERS.PAID_BY] = paidBy
        }
        set[PURCHASE_ORDERS.UPDATED_AT] = now
        set[PURCHASE_ORDERS.UPDATED_BY] = paidBy
        set[PURCHASE_ORDERS.VERSION] = PURCHASE_ORDERS.VERSION.plus(1)
        dsl.update(PURCHASE_ORDERS).set(set)
            .where(PURCHASE_ORDERS.ID.eq(orderId))
            .execute()
        return findOrderById(orderId)
    }

    fun placeOrderSent(
        orderId: Long,
        actorId: Long,
        now: OffsetDateTime,
        expectedAt: LocalDate? = null,
        invoiceNo: String? = null,
        paymentTerms: String? = null,
        deliveryAddress: String? = null,
        note: String? = null,
        markPaid: Boolean = false
    ): PurchaseOrdersRecord? {
        val order = lockOrderById(orderId) ?: return null
        val set = mutableMapOf<org.jooq.Field<*>, Any?>()
        if (order.status in listOf("DRAFT", "PENDING_APPROVAL", "APPROVED")) {
            set[PURCHASE_ORDERS.STATUS] = "SENT"
            if (order.approvedBy == null) set[PURCHASE_ORDERS.APPROVED_BY] = actorId
            if (order.approvedAt == null) set[PURCHASE_ORDERS.APPROVED_AT] = now
            if (order.orderedAt == null) set[PURCHASE_ORDERS.ORDERED_AT] = now
            set[PURCHASE_ORDERS.REAPPROVAL_REQUIRED] = false
            set[PURCHASE_ORDERS.REAPPROVAL_REASON] = null
        }
        expectedAt?.let { set[PURCHASE_ORDERS.EXPECTED_AT] = it }
        invoiceNo?.let { set[PURCHASE_ORDERS.INVOICE_NO] = it }
        paymentTerms?.let { set[PURCHASE_ORDERS.PAYMENT_TERMS] = it }
        deliveryAddress?.let { set[PURCHASE_ORDERS.DELIVERY_ADDRESS] = it }
        note?.let { set[PURCHASE_ORDERS.NOTE] = it }
        if (markPaid && order.paidAt == null) {
            set[PURCHASE_ORDERS.PAID_AT] = now
            set[PURCHASE_ORDERS.PAID_BY] = actorId
        }
        set[PURCHASE_ORDERS.UPDATED_AT] = now
        set[PURCHASE_ORDERS.UPDATED_BY] = actorId
        set[PURCHASE_ORDERS.VERSION] = PURCHASE_ORDERS.VERSION.plus(1)
        dsl.update(PURCHASE_ORDERS).set(set)
            .where(PURCHASE_ORDERS.ID.eq(orderId))
            .execute()
        return findOrderById(orderId)
    }

    fun insertOrderLines(orderId: Long, lines: List<NewOrderLine>, createdBy: Long) {
        lines.forEach { line ->
            dsl.insertInto(PURCHASE_ORDER_LINES)
                .set(PURCHASE_ORDER_LINES.ORDER_ID, orderId)
                .set(PURCHASE_ORDER_LINES.LINE_NO, line.lineNo)
                .set(PURCHASE_ORDER_LINES.NOMENCLATURE_ID, line.nomenclatureId)
                .set(PURCHASE_ORDER_LINES.QUANTITY, line.quantity)
                .set(PURCHASE_ORDER_LINES.UNIT, line.unit)
                .set(PURCHASE_ORDER_LINES.UNIT_PRICE, line.unitPrice)
                .set(PURCHASE_ORDER_LINES.VAT_RATE, line.vatRate)
                .set(PURCHASE_ORDER_LINES.OFFER_LINE_ID, line.offerLineId)
                .set(PURCHASE_ORDER_LINES.NOTE, line.note)
                .set(PURCHASE_ORDER_LINES.CREATED_BY, createdBy)
                .execute()
        }
    }

    fun listOrderLines(orderId: Long): List<PurchaseOrderLinesRecord> =
        dsl.selectFrom(PURCHASE_ORDER_LINES)
            .where(PURCHASE_ORDER_LINES.ORDER_ID.eq(orderId))
            .orderBy(PURCHASE_ORDER_LINES.LINE_NO.asc())
            .fetch()

    fun listOrderLinesForOrders(orderIds: List<Long>): List<PurchaseOrderLinesRecord> =
        if (orderIds.isEmpty()) emptyList()
        else dsl.selectFrom(PURCHASE_ORDER_LINES)
            .where(PURCHASE_ORDER_LINES.ORDER_ID.`in`(orderIds))
            .orderBy(PURCHASE_ORDER_LINES.ORDER_ID.asc(), PURCHASE_ORDER_LINES.LINE_NO.asc())
            .fetch()

    fun findOrderLineById(id: Long): PurchaseOrderLinesRecord? =
        dsl.selectFrom(PURCHASE_ORDER_LINES).where(PURCHASE_ORDER_LINES.ID.eq(id)).fetchOne()

    fun findOfferLineById(id: Long) =
        dsl.selectFrom(SUPPLIER_OFFER_LINES).where(SUPPLIER_OFFER_LINES.ID.eq(id)).fetchOne()

    fun lockOrderLineById(id: Long): PurchaseOrderLinesRecord? =
        dsl.selectFrom(PURCHASE_ORDER_LINES).where(PURCHASE_ORDER_LINES.ID.eq(id)).forUpdate().fetchOne()

    fun updateDeliveredQuantity(lineId: Long, deliveredQuantity: BigDecimal) {
        dsl.update(PURCHASE_ORDER_LINES)
            .set(PURCHASE_ORDER_LINES.DELIVERED_QUANTITY, deliveredQuantity)
            .where(PURCHASE_ORDER_LINES.ID.eq(lineId))
            .execute()
    }

    fun updateOrderLineQuantity(lineId: Long, quantity: BigDecimal) {
        dsl.update(PURCHASE_ORDER_LINES)
            .set(PURCHASE_ORDER_LINES.QUANTITY, quantity)
            .where(PURCHASE_ORDER_LINES.ID.eq(lineId))
            .execute()
    }

    fun updateOrderStatus(
        id: Long,
        expectedVersion: Long,
        fromStatuses: List<String>,
        newStatus: String,
        approvedBy: Long? = null,
        approvedAt: OffsetDateTime? = null,
        approvalNote: String? = null,
        orderedAt: OffsetDateTime? = null,
        reapprovalRequired: Boolean? = null,
        reapprovalReason: String? = null,
        updatedBy: Long
    ): PurchaseOrdersRecord? {
        val set = mutableMapOf<org.jooq.Field<*>, Any?>()
        set[PURCHASE_ORDERS.STATUS] = newStatus
        set[PURCHASE_ORDERS.VERSION] = PURCHASE_ORDERS.VERSION.plus(1)
        set[PURCHASE_ORDERS.UPDATED_AT] = OffsetDateTime.now()
        set[PURCHASE_ORDERS.UPDATED_BY] = updatedBy
        if (approvedBy != null) {
            set[PURCHASE_ORDERS.APPROVED_BY] = approvedBy
            set[PURCHASE_ORDERS.APPROVED_AT] = approvedAt
            set[PURCHASE_ORDERS.APPROVAL_NOTE] = approvalNote
        }
        if (orderedAt != null) {
            set[PURCHASE_ORDERS.ORDERED_AT] = orderedAt
        }
        if (reapprovalRequired != null) {
            set[PURCHASE_ORDERS.REAPPROVAL_REQUIRED] = reapprovalRequired
            set[PURCHASE_ORDERS.REAPPROVAL_REASON] = reapprovalReason
        }
        val changed = dsl.update(PURCHASE_ORDERS).set(set)
            .where(
                PURCHASE_ORDERS.ID.eq(id)
                    .and(PURCHASE_ORDERS.VERSION.eq(expectedVersion))
                    .and(PURCHASE_ORDERS.STATUS.`in`(fromStatuses))
            )
            .execute()
        if (changed == 0) return null
        return findOrderById(id)
    }

    fun updateOrderDetails(
        id: Long,
        expectedVersion: Long,
        deliveryAddress: String?,
        paymentTerms: String?,
        contractNo: String?,
        invoiceNo: String?,
        expectedAt: LocalDate?,
        note: String?,
        responsibleId: Long?,
        reapprovalRequired: Boolean?,
        reapprovalReason: String?,
        updatedBy: Long
    ): PurchaseOrdersRecord? {
        val set = mutableMapOf<org.jooq.Field<*>, Any?>()
        deliveryAddress?.let { set[PURCHASE_ORDERS.DELIVERY_ADDRESS] = it }
        paymentTerms?.let { set[PURCHASE_ORDERS.PAYMENT_TERMS] = it }
        contractNo?.let { set[PURCHASE_ORDERS.CONTRACT_NO] = it }
        invoiceNo?.let { set[PURCHASE_ORDERS.INVOICE_NO] = it }
        expectedAt?.let { set[PURCHASE_ORDERS.EXPECTED_AT] = it }
        note?.let { set[PURCHASE_ORDERS.NOTE] = it }
        responsibleId?.let { set[PURCHASE_ORDERS.RESPONSIBLE_ID] = it }
        if (reapprovalRequired != null) {
            set[PURCHASE_ORDERS.REAPPROVAL_REQUIRED] = reapprovalRequired
            set[PURCHASE_ORDERS.REAPPROVAL_REASON] = reapprovalReason
        }
        set[PURCHASE_ORDERS.UPDATED_AT] = OffsetDateTime.now()
        set[PURCHASE_ORDERS.UPDATED_BY] = updatedBy
        set[PURCHASE_ORDERS.VERSION] = PURCHASE_ORDERS.VERSION.plus(1)
        val changed = dsl.update(PURCHASE_ORDERS).set(set)
            .where(PURCHASE_ORDERS.ID.eq(id).and(PURCHASE_ORDERS.VERSION.eq(expectedVersion)))
            .execute()
        if (changed == 0) return null
        return findOrderById(id)
    }

    fun insertOrderHistory(
        orderId: Long,
        action: String,
        fromStatus: String?,
        toStatus: String?,
        reason: String?,
        changedBy: Long
    ) {
        dsl.insertInto(PURCHASE_ORDER_HISTORY)
            .set(PURCHASE_ORDER_HISTORY.ORDER_ID, orderId)
            .set(PURCHASE_ORDER_HISTORY.ACTION, action)
            .set(PURCHASE_ORDER_HISTORY.FROM_STATUS, fromStatus)
            .set(PURCHASE_ORDER_HISTORY.TO_STATUS, toStatus)
            .set(PURCHASE_ORDER_HISTORY.REASON, reason)
            .set(PURCHASE_ORDER_HISTORY.CHANGED_BY, changedBy)
            .execute()
    }

    fun listOrderHistory(orderId: Long): List<PurchaseOrderHistoryRecord> =
        dsl.selectFrom(PURCHASE_ORDER_HISTORY)
            .where(PURCHASE_ORDER_HISTORY.ORDER_ID.eq(orderId))
            .orderBy(PURCHASE_ORDER_HISTORY.ID.asc())
            .fetch()

    // --- Связь с потребностями ---

    fun linkDemands(orderId: Long, demandIds: List<Long>, createdBy: Long) {
        demandIds.distinct().forEach { demandId ->
            dsl.insertInto(PURCHASE_ORDER_DEMANDS)
                .set(PURCHASE_ORDER_DEMANDS.PURCHASE_ORDER_ID, orderId)
                .set(PURCHASE_ORDER_DEMANDS.DEMAND_ID, demandId)
                .set(PURCHASE_ORDER_DEMANDS.CREATED_BY, createdBy)
                .onConflictDoNothing()
                .execute()
        }
    }

    fun listDemandIds(orderId: Long): List<Long> =
        dsl.select(PURCHASE_ORDER_DEMANDS.DEMAND_ID)
            .from(PURCHASE_ORDER_DEMANDS)
            .where(PURCHASE_ORDER_DEMANDS.PURCHASE_ORDER_ID.eq(orderId))
            .fetch(PURCHASE_ORDER_DEMANDS.DEMAND_ID)

    fun demandNumbers(demandIds: Collection<Long>): Map<Long, String> {
        if (demandIds.isEmpty()) return emptyMap()
        return dsl.select(DEMANDS.ID, DEMANDS.NUMBER)
            .from(DEMANDS)
            .where(DEMANDS.ID.`in`(demandIds))
            .fetch()
            .associate { it.get(DEMANDS.ID)!! to it.get(DEMANDS.NUMBER)!! }
    }

    fun listDemandIdsForOrders(orderIds: List<Long>): Map<Long, List<Long>> {
        if (orderIds.isEmpty()) return emptyMap()
        return dsl.select(
            PURCHASE_ORDER_DEMANDS.PURCHASE_ORDER_ID,
            PURCHASE_ORDER_DEMANDS.DEMAND_ID
        )
            .from(PURCHASE_ORDER_DEMANDS)
            .where(PURCHASE_ORDER_DEMANDS.PURCHASE_ORDER_ID.`in`(orderIds))
            .fetch()
            .groupBy({ it.value1() }, { it.value2() })
    }

    fun listOrderIdsForDemand(demandId: Long): List<Long> =
        dsl.select(PURCHASE_ORDER_DEMANDS.PURCHASE_ORDER_ID)
            .from(PURCHASE_ORDER_DEMANDS)
            .where(PURCHASE_ORDER_DEMANDS.DEMAND_ID.eq(demandId))
            .fetch(PURCHASE_ORDER_DEMANDS.PURCHASE_ORDER_ID)

    fun listOrdersForDemand(demandId: Long): List<PurchaseOrdersRecord> {
        val orderIds = listOrderIdsForDemand(demandId)
        if (orderIds.isEmpty()) return emptyList()
        return dsl.selectFrom(PURCHASE_ORDERS)
            .where(PURCHASE_ORDERS.ID.`in`(orderIds))
            .orderBy(PURCHASE_ORDERS.ID.desc())
            .fetch()
    }

    fun findUserNames(userIds: Collection<Long>): Map<Long, String> {
        val ids = userIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        return dsl.select(USERS.ID, USERS.FULL_NAME)
            .from(USERS)
            .where(USERS.ID.`in`(ids))
            .fetch()
            .associate { it.get(USERS.ID)!! to it.get(USERS.FULL_NAME)!! }
    }

    fun findOfferInvoiceDocumentIds(offerIds: Collection<Long>): Map<Long, Long?> {
        val ids = offerIds.distinct()
        if (ids.isEmpty()) return emptyMap()
        return dsl.select(SUPPLIER_OFFERS.ID, SUPPLIER_OFFERS.INVOICE_DOCUMENT_ID)
            .from(SUPPLIER_OFFERS)
            .where(SUPPLIER_OFFERS.ID.`in`(ids))
            .fetch()
            .associate { it.get(SUPPLIER_OFFERS.ID)!! to it.get(SUPPLIER_OFFERS.INVOICE_DOCUMENT_ID) }
    }

    fun findDemandById(id: Long) = dsl.selectFrom(DEMANDS).where(DEMANDS.ID.eq(id)).fetchOne()

    fun findSupplierById(id: Long): SuppliersRecord? =
        dsl.selectFrom(SUPPLIERS).where(SUPPLIERS.ID.eq(id)).fetchOne()

    fun findOfferById(id: Long): SupplierOffersRecord? =
        dsl.selectFrom(SUPPLIER_OFFERS).where(SUPPLIER_OFFERS.ID.eq(id)).fetchOne()

    fun lockOfferById(id: Long): SupplierOffersRecord? =
        dsl.selectFrom(SUPPLIER_OFFERS).where(SUPPLIER_OFFERS.ID.eq(id)).forUpdate().fetchOne()

    fun findNomenclatureById(id: Long) =
        dsl.selectFrom(NOMENCLATURE).where(NOMENCLATURE.ID.eq(id)).fetchOne()

    fun orderTotals(orderId: Long) =
        dsl.fetchOne(
            "SELECT net_total, vat_total, gross_total, ordered_quantity, delivered_quantity " +
                "FROM purchase_order_totals WHERE order_id = ?",
            orderId
        )

    fun orderTotalsBatch(orderIds: List<Long>): Map<Long, org.jooq.Record> {
        if (orderIds.isEmpty()) return emptyMap()
        val placeholders = orderIds.joinToString(",") { "?" }
        return dsl.fetch(
            "SELECT order_id, net_total, vat_total, gross_total, ordered_quantity, delivered_quantity " +
                "FROM purchase_order_totals WHERE order_id IN ($placeholders)",
            *orderIds.toTypedArray()
        ).associateBy { it.get("order_id", Long::class.java) }
    }

    // --- Поставки ---

    fun findDeliveryById(id: Long): PurchaseDeliveriesRecord? =
        dsl.selectFrom(PURCHASE_DELIVERIES).where(PURCHASE_DELIVERIES.ID.eq(id)).fetchOne()

    fun lockDeliveryById(id: Long): PurchaseDeliveriesRecord? =
        dsl.selectFrom(PURCHASE_DELIVERIES).where(PURCHASE_DELIVERIES.ID.eq(id)).forUpdate().fetchOne()

    fun listDeliveries(orderId: Long?): List<PurchaseDeliveriesRecord> {
        val conditions = mutableListOf<Condition>()
        orderId?.let { conditions += PURCHASE_DELIVERIES.ORDER_ID.eq(it) }
        return dsl.selectFrom(PURCHASE_DELIVERIES)
            .where(conditions)
            .orderBy(PURCHASE_DELIVERIES.ID.desc())
            .fetch()
    }

    fun insertDelivery(
        number: String,
        orderId: Long,
        deliveredAt: LocalDate,
        deliveryNote: String?,
        sourceDocument: String?,
        note: String?,
        createdBy: Long
    ): PurchaseDeliveriesRecord {
        val record = dsl.newRecord(PURCHASE_DELIVERIES)
        record.number = number
        record.orderId = orderId
        record.status = "REGISTERED"
        record.deliveredAt = deliveredAt
        record.deliveryNote = deliveryNote
        record.sourceDocument = sourceDocument
        record.note = note
        record.createdBy = createdBy
        record.store()
        return findDeliveryById(record.id)!!
    }

    fun insertDeliveryLines(deliveryId: Long, lines: List<NewDeliveryLine>, createdBy: Long) {
        lines.forEach { line ->
            dsl.insertInto(PURCHASE_DELIVERY_LINES)
                .set(PURCHASE_DELIVERY_LINES.DELIVERY_ID, deliveryId)
                .set(PURCHASE_DELIVERY_LINES.ORDER_LINE_ID, line.orderLineId)
                .set(PURCHASE_DELIVERY_LINES.QUANTITY, line.quantity)
                .set(PURCHASE_DELIVERY_LINES.NOTE, line.note)
                .set(PURCHASE_DELIVERY_LINES.CREATED_BY, createdBy)
                .execute()
        }
    }

    fun listDeliveryLines(deliveryId: Long): List<PurchaseDeliveryLinesRecord> =
        dsl.selectFrom(PURCHASE_DELIVERY_LINES)
            .where(PURCHASE_DELIVERY_LINES.DELIVERY_ID.eq(deliveryId))
            .orderBy(PURCHASE_DELIVERY_LINES.ID.asc())
            .fetch()

    fun listDeliveryLinesForDeliveries(deliveryIds: List<Long>): List<PurchaseDeliveryLinesRecord> =
        if (deliveryIds.isEmpty()) emptyList()
        else dsl.selectFrom(PURCHASE_DELIVERY_LINES)
            .where(PURCHASE_DELIVERY_LINES.DELIVERY_ID.`in`(deliveryIds))
            .orderBy(PURCHASE_DELIVERY_LINES.ID.asc())
            .fetch()

    fun markDeliveryCancelled(
        id: Long,
        expectedVersion: Long,
        updatedBy: Long
    ): PurchaseDeliveriesRecord? {
        val changed = dsl.update(PURCHASE_DELIVERIES)
            .set(PURCHASE_DELIVERIES.STATUS, "CANCELLED")
            .set(PURCHASE_DELIVERIES.VERSION, PURCHASE_DELIVERIES.VERSION.plus(1))
            .set(PURCHASE_DELIVERIES.UPDATED_AT, OffsetDateTime.now())
            .set(PURCHASE_DELIVERIES.UPDATED_BY, updatedBy)
            .where(
                PURCHASE_DELIVERIES.ID.eq(id)
                    .and(PURCHASE_DELIVERIES.VERSION.eq(expectedVersion))
                    .and(PURCHASE_DELIVERIES.STATUS.eq("REGISTERED"))
            )
            .execute()
        if (changed == 0) return null
        return findDeliveryById(id)
    }

    /** Сумма зарегистрированных (не отменённых) поставок по позиции заказа. */
    fun deliveredQuantityForLine(orderLineId: Long): BigDecimal =
        dsl.fetchOne(
            """
            SELECT COALESCE(SUM(dl.quantity), 0) AS total
            FROM purchase_delivery_lines dl
                     JOIN purchase_deliveries d ON d.id = dl.delivery_id
            WHERE dl.order_line_id = ? AND d.status = 'REGISTERED'
            """.trimIndent(),
            orderLineId
        )?.get("total", BigDecimal::class.java) ?: BigDecimal.ZERO

    // --- Идемпотентность ---

    fun findIdempotency(
        userId: Long,
        operation: String,
        key: String
    ): PurchaseIdempotencyKeysRecord? =
        dsl.selectFrom(PURCHASE_IDEMPOTENCY_KEYS)
            .where(
                PURCHASE_IDEMPOTENCY_KEYS.USER_ID.eq(userId)
                    .and(PURCHASE_IDEMPOTENCY_KEYS.OPERATION.eq(operation))
                    .and(PURCHASE_IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY.eq(key))
            )
            .fetchOne()

    fun insertIdempotency(
        userId: Long,
        operation: String,
        key: String,
        requestHash: String,
        entityType: String,
        entityId: Long,
        responseStatus: Int,
        responseBody: String
    ) {
        dsl.insertInto(PURCHASE_IDEMPOTENCY_KEYS)
            .set(PURCHASE_IDEMPOTENCY_KEYS.USER_ID, userId)
            .set(PURCHASE_IDEMPOTENCY_KEYS.OPERATION, operation)
            .set(PURCHASE_IDEMPOTENCY_KEYS.IDEMPOTENCY_KEY, key)
            .set(PURCHASE_IDEMPOTENCY_KEYS.REQUEST_HASH, requestHash)
            .set(PURCHASE_IDEMPOTENCY_KEYS.ENTITY_TYPE, entityType)
            .set(PURCHASE_IDEMPOTENCY_KEYS.ENTITY_ID, entityId)
            .set(PURCHASE_IDEMPOTENCY_KEYS.RESPONSE_STATUS, responseStatus)
            .set(PURCHASE_IDEMPOTENCY_KEYS.RESPONSE_BODY, responseBody)
            .execute()
    }
}
