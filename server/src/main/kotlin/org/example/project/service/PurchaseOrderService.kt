package org.example.project.service

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.records.PurchaseOrdersRecord
import org.example.project.generated.jooq.tables.records.SupplierOffersRecord
import org.example.project.model.CurrentUser
import org.example.project.realtime.NotificationEventBus
import org.example.project.repository.PurchaseOrderRepository
import org.example.project.repository.SupplierRepository
import org.jooq.DSLContext
import org.jooq.TransactionalCallable
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/**
 * Заказ поставщику и частичные поставки (ТЗ §9.3–9.8, §28.2; SUP-15, SUP-16).
 *
 * Границы:
 * - заказ создаётся только из ВЫБРАННОГО предложения (D-28): обойти выбор
 *   инициатора через прямой заказ нельзя;
 * - заказ оформляет уполномоченный снабженец с правом `suppliers:order`
 *   после согласования (переход SENT требует APPROVED);
 * - оплата, счета-фактуры и претензии не реализуются;
 * - пороги согласования не придумываются: §9.7 — открытый вопрос, поэтому
 *   фиксируется только факт согласования и его автор;
 * - существенное изменение (количество, цена, срок) после согласования
 *   сбрасывает заказ в повторное согласование;
 * - поставки частичные: количества «заказано / поставлено / осталось» считает база.
 */
class PurchaseOrderService(
    private val purchaseRepository: PurchaseOrderRepository,
    private val supplierRepository: SupplierRepository,
    private val notificationService: NotificationService,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    data class CommandResponse(val status: HttpStatusCode, val body: String)

    private val json = Json { encodeDefaults = true }

    // -----------------------------------------------------------------------
    // Чтение
    // -----------------------------------------------------------------------

    fun listOrders(
        supplierId: Long?,
        status: PurchaseOrderStatus?,
        responsibleId: Long?,
        currentUser: CurrentUser
    ): List<PurchaseOrderDto> {
        currentUser.requirePermission("suppliers", "read")
        val orders = purchaseRepository.listOrders(supplierId, status?.name, responsibleId)
        if (orders.isEmpty()) return emptyList()
        return buildMany(orders)
    }

    fun getOrder(id: Long, currentUser: CurrentUser): PurchaseOrderDto {
        currentUser.requirePermission("suppliers", "read")
        val order = purchaseRepository.findOrderById(id) ?: throw NotFoundException("purchase_order", id)
        return build(order)
    }

    internal fun listOrdersForDemandInternal(demandId: Long): List<PurchaseOrderDto> {
        val orders = purchaseRepository.listOrdersForDemand(demandId)
        if (orders.isEmpty()) return emptyList()
        return buildMany(orders)
    }

    fun listOrderHistory(id: Long, currentUser: CurrentUser): List<PurchaseOrderHistoryDto> {
        currentUser.requirePermission("suppliers", "read")
        purchaseRepository.findOrderById(id) ?: throw NotFoundException("purchase_order", id)
        return purchaseRepository.listOrderHistory(id).map {
            PurchaseOrderHistoryDto(
                id = it.id!!,
                action = it.action,
                fromStatus = it.fromStatus,
                toStatus = it.toStatus,
                reason = it.reason,
                changedBy = it.changedBy,
                changedAt = it.changedAt?.toString()
            )
        }
    }

    // -----------------------------------------------------------------------
    // Создание
    // -----------------------------------------------------------------------

    fun createOrder(request: CreatePurchaseOrderRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("suppliers", "order")
        val payload = json.encodeToString(CreatePurchaseOrderRequest.serializer(), request)
        return executeIdempotent(
            "PURCHASE_ORDER_CREATE", request.idempotencyKey, payload, HttpStatusCode.Created, currentUser
        ) {
                val offer = purchaseRepository.lockOfferById(request.offerId)
                    ?: throw NotFoundException("supplier_offer", request.offerId)
                // D-28: заказ возможен только по предложению, выбранному инициатором.
                if (offer.status != "SELECTED") {
                    throw ConflictException(
                        "Purchase order requires a SELECTED offer; offer ${offer.number} is ${offer.status}"
                    )
                }
                purchaseRepository.findOrderByOfferId(offer.id)?.let { existing ->
                    throw ConflictException(
                        "Offer ${offer.number} already produced purchase order ${existing.number}"
                    )
                }
                val supplier = purchaseRepository.findSupplierById(offer.supplierId)
                    ?: throw NotFoundException("supplier", offer.supplierId)
                if (supplier.verificationStatus == "REJECTED") {
                    throw ConflictException("Supplier ${supplier.number} is rejected")
                }
                if (supplier.archived) {
                    throw ConflictException("Supplier ${supplier.number} is archived")
                }
                val expectedAt = request.expectedAt?.let { parseDate(it, "expectedAt") }
                if (expectedAt != null && expectedAt.isBefore(LocalDate.now())) {
                    throw ValidationException(
                        "Expected delivery date is in the past",
                        mapOf("expectedAt" to "in_past")
                    )
                }
                val demands = resolveDemands(request, offer)
                val offerLines = supplierRepository.listOfferLines(offer.id)
                if (offerLines.isEmpty()) {
                    throw ConflictException("Offer ${offer.number} has no lines")
                }
                val normalized = normalizeLines(offerLines, request.lines)
                val number = purchaseRepository.generateNumber()
                val order = purchaseRepository.insertOrder(
                    number = number,
                    supplierId = offer.supplierId,
                    offerId = offer.id,
                    currency = offer.currency,
                    deliveryAddress = request.deliveryAddress?.trim(),
                    paymentTerms = (request.paymentTerms ?: offer.paymentTerms)?.trim(),
                    contractNo = request.contractNo?.trim(),
                    invoiceNo = request.invoiceNo?.trim(),
                    note = request.note?.trim(),
                    responsibleId = request.responsibleId ?: currentUser.userId,
                    expectedAt = expectedAt,
                    createdBy = currentUser.userId
                )
                purchaseRepository.insertOrderLines(
                    order.id,
                    normalized.mapIndexed { index, line ->
                        PurchaseOrderRepository.NewOrderLine(
                            lineNo = index + 1,
                            nomenclatureId = line.nomenclatureId,
                            quantity = line.quantity,
                            unit = line.unit,
                            unitPrice = line.unitPrice,
                            vatRate = line.vatRate,
                            offerLineId = line.offerLineId,
                            note = null
                        )
                    },
                    currentUser.userId
                )
                purchaseRepository.linkDemands(order.id, demands, currentUser.userId)
                purchaseRepository.insertOrderHistory(
                    order.id, "CREATED", null, "DRAFT", null, currentUser.userId
                )
                auditService.log(
                    currentUser.userId, "CREATE", "PURCHASE_ORDER", order.id,
                    mapOf(
                        "number" to number,
                        "supplierId" to offer.supplierId,
                        "offerNumber" to offer.number,
                        "demandIds" to demands
                    )
                )
                order.id to json.encodeToString(PurchaseOrderDto.serializer(), build(order))
        }
    }

    // -----------------------------------------------------------------------
    // Изменение и повторное согласование
    // -----------------------------------------------------------------------

    fun updateOrder(id: Long, request: UpdatePurchaseOrderRequest, currentUser: CurrentUser): PurchaseOrderDto {
        currentUser.requirePermission("suppliers", "order")
        return dsl.transactionResult(TransactionalCallable {
            val order = purchaseRepository.lockOrderById(id)
                ?: throw NotFoundException("purchase_order", id)
            if (order.version != request.version) {
                throw ConflictException("Purchase order was modified concurrently, reload it")
            }
            if (order.status in setOf("CANCELLED", "CLOSED", "DELIVERED", "PARTIALLY_DELIVERED")) {
                throw ConflictException("Purchase order cannot be changed in status ${order.status}")
            }
            val expectedAt = request.expectedAt?.let { parseDate(it, "expectedAt") }
            var significant = false
            var reasons = mutableListOf<String>()

            request.lines?.let { lines ->
                applyLineChanges(order, lines, reasons)
                significant = significant || reasons.isNotEmpty()
            }
            if (request.expectedAt != null && expectedAt != order.expectedAt) {
                significant = true
                reasons += "изменён срок поставки"
            }
            if (request.paymentTerms != null && request.paymentTerms != order.paymentTerms) {
                significant = true
                reasons += "изменены условия оплаты"
            }
            if (request.deliveryAddress != null && request.deliveryAddress != order.deliveryAddress) {
                significant = true
                reasons += "изменён адрес доставки"
            }
            if (significant && request.changeReason.isNullOrBlank()) {
                throw ValidationException(
                    "Significant change requires a reason: ${reasons.joinToString("; ")}",
                    mapOf("changeReason" to "required", "changes" to reasons.joinToString("; "))
                )
            }
            // Повторное согласование нужно, только если заказ уже был согласован
            // и существенные условия поменялись. Черновик согласовать нечего.
            val needsReapproval = significant && order.status != "DRAFT"
            val updated = purchaseRepository.updateOrderDetails(
                id = id,
                expectedVersion = request.version,
                deliveryAddress = request.deliveryAddress?.trim(),
                paymentTerms = request.paymentTerms?.trim(),
                contractNo = request.contractNo?.trim(),
                invoiceNo = request.invoiceNo?.trim(),
                expectedAt = expectedAt,
                note = request.note?.trim(),
                responsibleId = request.responsibleId,
                reapprovalRequired = if (needsReapproval) true else null,
                reapprovalReason = if (needsReapproval) {
                    request.changeReason?.trim().orEmpty() + " (" + reasons.joinToString("; ") + ")"
                } else {
                    null
                },
                updatedBy = currentUser.userId
            ) ?: throw ConflictException("Purchase order was modified concurrently, reload it")
            if (needsReapproval) {
                // Возвращаем в согласование: заказ нельзя отправить поставщику
                // с изменёнными после согласования условиями.
                val reverted = purchaseRepository.updateOrderStatus(
                    id = id,
                    expectedVersion = updated.version,
                    fromStatuses = listOf("APPROVED", "SENT"),
                    newStatus = "PENDING_APPROVAL",
                    updatedBy = currentUser.userId
                )
                purchaseRepository.insertOrderHistory(
                    id, "REAPPROVAL_REQUIRED", order.status, "PENDING_APPROVAL",
                    request.changeReason?.trim(), currentUser.userId
                )
                auditService.log(
                    currentUser.userId, "REAPPROVAL_REQUIRED", "PURCHASE_ORDER", id,
                    mapOf("number" to order.number, "changes" to reasons.joinToString("; "))
                )
                return@TransactionalCallable build(reverted ?: purchaseRepository.findOrderById(id)!!)
            }
            purchaseRepository.insertOrderHistory(
                id, "UPDATED", order.status, updated.status, null, currentUser.userId
            )
            auditService.log(
                currentUser.userId, "UPDATE", "PURCHASE_ORDER", id,
                mapOf("number" to order.number, "significant" to significant)
            )
            build(updated)
        })
    }

    // -----------------------------------------------------------------------
    // Жизненный цикл
    // -----------------------------------------------------------------------

    fun submitForApproval(id: Long, request: PurchaseOrderActionRequest, currentUser: CurrentUser): CommandResponse =
        transition(
            id, request, currentUser, "SUBMIT",
            fromStatuses = listOf("DRAFT", "APPROVED"),
            newStatus = "PENDING_APPROVAL",
            requirePermission = "suppliers", permissionAction = "order"
        ) { }

    fun approve(id: Long, request: PurchaseOrderActionRequest, currentUser: CurrentUser): CommandResponse {
        // Согласование — отдельное полномочие: заказчик не согласует сам себе.
        currentUser.requirePermission("suppliers", "approve_order")
        return transition(
            id, request, currentUser, "APPROVE",
            fromStatuses = listOf("PENDING_APPROVAL"),
            newStatus = "APPROVED",
            requirePermission = "suppliers", permissionAction = "approve_order"
        ) { order ->
            if (order.createdBy == currentUser.userId &&
                !currentUser.hasPermission("suppliers", "approve_order_any")
            ) {
                throw ForbiddenException(
                    "Purchase order approval requires suppliers:approve_order_any when approving own order"
                )
            }
        }
    }

    fun send(id: Long, request: PurchaseOrderActionRequest, currentUser: CurrentUser): CommandResponse =
        transition(
            id, request, currentUser, "SEND",
            fromStatuses = listOf("APPROVED"),
            newStatus = "SENT",
            requirePermission = "suppliers", permissionAction = "order"
        ) { order ->
            if (order.approvedAt == null) {
                throw ConflictException("Purchase order must be approved before sending")
            }
            if (order.reapprovalRequired) {
                throw ConflictException(
                    "Purchase order requires re-approval: ${order.reapprovalReason}"
                )
            }
        }

    fun close(id: Long, request: PurchaseOrderActionRequest, currentUser: CurrentUser): CommandResponse =
        transition(
            id, request, currentUser, "CLOSE",
            fromStatuses = listOf("DELIVERED", "PARTIALLY_DELIVERED"),
            newStatus = "CLOSED",
            requirePermission = "suppliers", permissionAction = "order"
        ) { }

    fun cancel(id: Long, request: CancelPurchaseOrderRequest, currentUser: CurrentUser): CommandResponse {
        currentUser.requirePermission("suppliers", "order")
        if (request.reason.isBlank()) {
            throw ValidationException("Cancel reason is required", mapOf("reason" to "required"))
        }
        val payload = json.encodeToString(CancelPurchaseOrderRequest.serializer(), request)
        return executeIdempotent(
            "PURCHASE_ORDER_CANCEL", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser
        ) {
                val order = purchaseRepository.findOrderById(id)
                    ?: throw NotFoundException("purchase_order", id)
                val cancelled = purchaseRepository.updateOrderStatus(
                    id = id,
                    expectedVersion = request.version,
                    fromStatuses = listOf("DRAFT", "PENDING_APPROVAL", "APPROVED", "SENT"),
                    newStatus = "CANCELLED",
                    updatedBy = currentUser.userId
                ) ?: throw ConflictException(
                    "Purchase order can be cancelled only before delivery starts"
                )
                purchaseRepository.insertOrderHistory(
                    id, "CANCELLED", order.status, "CANCELLED", request.reason.trim(), currentUser.userId
                )
                auditService.log(
                    currentUser.userId, "CANCEL", "PURCHASE_ORDER", id,
                    mapOf("number" to order.number, "reason" to request.reason.trim())
                )
                id to json.encodeToString(PurchaseOrderDto.serializer(), build(cancelled))
        }
    }

    fun markOrderPaid(id: Long, paymentNote: String?, currentUser: CurrentUser): PurchaseOrderDto {
        currentUser.requirePermission("suppliers", "order")
        var pendingEvents: List<NotificationEventBus.Event> = emptyList()
        val updated = dsl.transactionResult(TransactionalCallable {
            val order = purchaseRepository.lockOrderById(id)
                ?: throw NotFoundException("purchase_order", id)
            if (order.status == "CANCELLED") {
                throw ConflictException("Заказ ${order.number} отменён")
            }
            val wasAlreadyPaid = order.paidAt != null
            val note = paymentNote?.trim()?.takeIf { it.isNotEmpty() }
            val saved = purchaseRepository.markOrderPaid(order.id, currentUser.userId, OffsetDateTime.now())!!
            if (!wasAlreadyPaid || order.status != saved.status) {
                purchaseRepository.insertOrderHistory(
                    saved.id, "PAID", order.status, saved.status, note, currentUser.userId
                )
                auditService.log(
                    currentUser.userId, "PURCHASE_ORDER_PAID", "PURCHASE_ORDER", saved.id,
                    mapOf("number" to saved.number, "note" to note)
                )
            }
            if (!wasAlreadyPaid) {
                val demandIds = purchaseRepository.listDemandIds(saved.id).ifEmpty {
                    listOfNotNull(purchaseRepository.findOfferById(saved.offerId)?.demandId)
                }
                val demands = demandIds.mapNotNull { purchaseRepository.findDemandById(it) }
                val events = mutableListOf<NotificationEventBus.Event>()
                val wmsPosters = notificationService.findUserIdsWithPermission("wms", "post")
                    .filter { it != currentUser.userId }
                demands.forEach { d ->
                    if (d.initiatorId != currentUser.userId) {
                        events += notificationService.createSystemNotifications(
                            recipientIds = listOf(d.initiatorId),
                            type = "DEMAND_PAID",
                            entityType = "DEMAND",
                            entityId = d.id,
                            title = "Заявка ${d.number} оплачена (заказ ${saved.number}), ожидается приход на склад"
                        )
                    }
                    if (wmsPosters.isNotEmpty()) {
                        events += notificationService.createSystemNotifications(
                            recipientIds = wmsPosters,
                            type = "PURCHASE_ORDER_PAID",
                            entityType = "PURCHASE_ORDER",
                            entityId = saved.id,
                            title = "Оплачен заказ ${saved.number} по заявке ${d.number} — ожидается поставка"
                        )
                    }
                }
                pendingEvents = events
            }
            build(saved)
        })
        notificationService.publishEvents(pendingEvents)
        return updated
    }

    internal fun markOfferPaidAndEnsureSentOrder(
        demandId: Long,
        demandNumber: String,
        demandInitiatorId: Long,
        offer: SupplierOffersRecord,
        request: MarkDemandPaidRequest,
        currentUser: CurrentUser
    ): PurchaseOrdersRecord {
        var pendingEvents: List<NotificationEventBus.Event> = emptyList()
        val order = dsl.transactionResult(TransactionalCallable {
            val lockedOffer = purchaseRepository.lockOfferById(offer.id)
                ?: throw NotFoundException("supplier_offer", offer.id)
            val existing = purchaseRepository.findOrderByOfferId(lockedOffer.id)
            val now = OffsetDateTime.now()
            val note = request.paymentNote?.trim()?.takeIf { it.isNotEmpty() }
            var wasAlreadyPaid = false
            val resultOrder = if (existing == null) {
                val offerLines = supplierRepository.listOfferLines(lockedOffer.id)
                if (offerLines.isEmpty()) {
                    throw ConflictException("Offer ${lockedOffer.number} has no lines")
                }
                val expectedAt = request.expectedAt?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { parseDate(it, "expectedAt") }
                    ?: lockedOffer.deliveryDays?.let { LocalDate.now().plusDays(it.toLong()) }
                val number = purchaseRepository.generateNumber()
                val created = purchaseRepository.insertOrder(
                    number = number,
                    supplierId = lockedOffer.supplierId,
                    offerId = lockedOffer.id,
                    currency = lockedOffer.currency,
                    deliveryAddress = null,
                    paymentTerms = lockedOffer.paymentTerms,
                    contractNo = null,
                    invoiceNo = lockedOffer.invoiceNumber,
                    note = note ?: lockedOffer.note,
                    responsibleId = currentUser.userId,
                    expectedAt = expectedAt,
                    createdBy = currentUser.userId,
                    status = "SENT",
                    approvedBy = currentUser.userId,
                    approvedAt = now,
                    orderedAt = now,
                    paidAt = now,
                    paidBy = currentUser.userId
                )
                purchaseRepository.insertOrderLines(
                    created.id,
                    offerLines.mapIndexed { index, line ->
                        PurchaseOrderRepository.NewOrderLine(
                            lineNo = index + 1,
                            nomenclatureId = line.nomenclatureId,
                            quantity = line.quantity,
                            unit = line.unit,
                            unitPrice = line.unitPrice,
                            vatRate = line.vatRate,
                            offerLineId = line.id,
                            note = line.note
                        )
                    },
                    currentUser.userId
                )
                purchaseRepository.linkDemands(created.id, listOf(demandId), currentUser.userId)
                purchaseRepository.insertOrderHistory(
                    created.id, "PAID", null, "SENT", note ?: "Оплачено по заявке $demandNumber", currentUser.userId
                )
                auditService.log(
                    currentUser.userId, "PURCHASE_ORDER_PAID", "PURCHASE_ORDER", created.id,
                    mapOf(
                        "number" to created.number,
                        "demandId" to demandId,
                        "offerId" to lockedOffer.id,
                        "note" to note
                    )
                )
                created
            } else {
                val locked = purchaseRepository.lockOrderById(existing.id)!!
                if (locked.status == "CANCELLED") {
                    throw ConflictException("Заказ ${locked.number} отменён")
                }
                wasAlreadyPaid = locked.paidAt != null
                val updated = purchaseRepository.markOrderPaid(locked.id, currentUser.userId, now)!!
                purchaseRepository.linkDemands(updated.id, listOf(demandId), currentUser.userId)
                if (!wasAlreadyPaid || locked.status != updated.status) {
                    purchaseRepository.insertOrderHistory(
                        updated.id, "PAID", locked.status, updated.status, note, currentUser.userId
                    )
                    auditService.log(
                        currentUser.userId, "PURCHASE_ORDER_PAID", "PURCHASE_ORDER", updated.id,
                        mapOf("number" to updated.number, "demandId" to demandId, "note" to note)
                    )
                }
                updated
            }

            if (!wasAlreadyPaid) {
                val events = mutableListOf<NotificationEventBus.Event>()
                if (demandInitiatorId != currentUser.userId) {
                    events += notificationService.createSystemNotifications(
                        recipientIds = listOf(demandInitiatorId),
                        type = "DEMAND_PAID",
                        entityType = "DEMAND",
                        entityId = demandId,
                        title = "Заявка $demandNumber оплачена (заказ ${resultOrder.number}), ожидается приход на склад"
                    )
                }
                val wmsPosters = notificationService.findUserIdsWithPermission("wms", "post")
                    .filter { it != currentUser.userId }
                if (wmsPosters.isNotEmpty()) {
                    events += notificationService.createSystemNotifications(
                        recipientIds = wmsPosters,
                        type = "PURCHASE_ORDER_PAID",
                        entityType = "PURCHASE_ORDER",
                        entityId = resultOrder.id,
                        title = "Оплачен заказ ${resultOrder.number} по заявке $demandNumber — ожидается поставка"
                    )
                }
                pendingEvents = events
            }
            resultOrder
        })
        notificationService.publishEvents(pendingEvents)
        return order
    }

    internal fun placeOrderForDemandOffer(
        demandId: Long,
        demandNumber: String,
        demandInitiatorId: Long,
        offer: SupplierOffersRecord,
        request: PlaceDemandOrderRequest,
        currentUser: CurrentUser
    ): PurchaseOrdersRecord {
        var pendingEvents: List<NotificationEventBus.Event> = emptyList()
        val expectedAt = request.expectedAt?.trim()?.takeIf { it.isNotEmpty() }?.let { parseDate(it, "expectedAt") }
        val invoiceNo = request.invoiceNo?.trim()?.takeIf { it.isNotEmpty() }
        val paymentTerms = request.paymentTerms?.trim()?.takeIf { it.isNotEmpty() }
        val deliveryAddress = request.deliveryAddress?.trim()?.takeIf { it.isNotEmpty() }
        val note = request.note?.trim()?.takeIf { it.isNotEmpty() }

        val order = dsl.transactionResult(TransactionalCallable {
            val lockedOffer = purchaseRepository.lockOfferById(offer.id)
                ?: throw NotFoundException("supplier_offer", offer.id)
            val existing = purchaseRepository.findOrderByOfferId(lockedOffer.id)
            val now = OffsetDateTime.now()
            val resolvedExpectedAt = expectedAt ?: lockedOffer.deliveryDays?.let { LocalDate.now().plusDays(it.toLong()) }
            var shouldNotify = false
            val resultOrder = if (existing == null) {
                val offerLines = supplierRepository.listOfferLines(lockedOffer.id)
                if (offerLines.isEmpty()) {
                    throw ConflictException("Offer ${lockedOffer.number} has no lines")
                }
                val number = purchaseRepository.generateNumber()
                val created = purchaseRepository.insertOrder(
                    number = number,
                    supplierId = lockedOffer.supplierId,
                    offerId = lockedOffer.id,
                    currency = lockedOffer.currency,
                    deliveryAddress = deliveryAddress,
                    paymentTerms = paymentTerms ?: lockedOffer.paymentTerms,
                    contractNo = null,
                    invoiceNo = invoiceNo ?: lockedOffer.invoiceNumber,
                    note = note ?: lockedOffer.note,
                    responsibleId = currentUser.userId,
                    expectedAt = resolvedExpectedAt,
                    createdBy = currentUser.userId,
                    status = "SENT",
                    approvedBy = currentUser.userId,
                    approvedAt = now,
                    orderedAt = now,
                    paidAt = if (request.markPaid) now else null,
                    paidBy = if (request.markPaid) currentUser.userId else null
                )
                purchaseRepository.insertOrderLines(
                    created.id,
                    offerLines.mapIndexed { index, line ->
                        PurchaseOrderRepository.NewOrderLine(
                            lineNo = index + 1,
                            nomenclatureId = line.nomenclatureId,
                            quantity = line.quantity,
                            unit = line.unit,
                            unitPrice = line.unitPrice,
                            vatRate = line.vatRate,
                            offerLineId = line.id,
                            note = line.note
                        )
                    },
                    currentUser.userId
                )
                purchaseRepository.linkDemands(created.id, listOf(demandId), currentUser.userId)
                purchaseRepository.insertOrderHistory(
                    created.id,
                    if (request.markPaid) "PAID" else "SEND",
                    null,
                    "SENT",
                    note ?: "Заказ оформлен по заявке $demandNumber",
                    currentUser.userId
                )
                auditService.log(
                    currentUser.userId, "PLACE_ORDER", "PURCHASE_ORDER", created.id,
                    mapOf(
                        "number" to created.number,
                        "demandId" to demandId,
                        "offerId" to lockedOffer.id,
                        "markPaid" to request.markPaid
                    )
                )
                shouldNotify = true
                created
            } else {
                val locked = purchaseRepository.lockOrderById(existing.id)!!
                if (locked.status == "CANCELLED") {
                    throw ConflictException("Заказ ${locked.number} отменён")
                }
                val wasSent = locked.status in setOf("SENT", "PARTIALLY_DELIVERED", "DELIVERED", "CLOSED")
                val wasPaid = locked.paidAt != null
                val updated = purchaseRepository.placeOrderSent(
                    orderId = locked.id,
                    actorId = currentUser.userId,
                    now = now,
                    expectedAt = expectedAt,
                    invoiceNo = invoiceNo,
                    paymentTerms = paymentTerms,
                    deliveryAddress = deliveryAddress,
                    note = note,
                    markPaid = request.markPaid
                )!!
                purchaseRepository.linkDemands(updated.id, listOf(demandId), currentUser.userId)
                val statusChanged = locked.status != updated.status
                val paidNow = !wasPaid && updated.paidAt != null
                if (statusChanged || paidNow || expectedAt != null || invoiceNo != null || paymentTerms != null || deliveryAddress != null || note != null) {
                    purchaseRepository.insertOrderHistory(
                        updated.id,
                        if (paidNow) "PAID" else "SEND",
                        locked.status,
                        updated.status,
                        note ?: "Оформлен заказ по заявке $demandNumber",
                        currentUser.userId
                    )
                    auditService.log(
                        currentUser.userId, "PLACE_ORDER", "PURCHASE_ORDER", updated.id,
                        mapOf("number" to updated.number, "demandId" to demandId, "markPaid" to request.markPaid)
                    )
                }
                shouldNotify = !wasSent || paidNow
                updated
            }

            if (shouldNotify) {
                val events = mutableListOf<NotificationEventBus.Event>()
                if (demandInitiatorId != currentUser.userId) {
                    events += notificationService.createSystemNotifications(
                        recipientIds = listOf(demandInitiatorId),
                        type = if (resultOrder.paidAt != null) "DEMAND_PAID" else "DEMAND_ORDER_PLACED",
                        entityType = "DEMAND",
                        entityId = demandId,
                        title = "По заявке $demandNumber оформлен заказ ${resultOrder.number} — ожидается поставка на склад"
                    )
                }
                val wmsPosters = notificationService.findUserIdsWithPermission("wms", "post")
                    .filter { it != currentUser.userId }
                if (wmsPosters.isNotEmpty()) {
                    events += notificationService.createSystemNotifications(
                        recipientIds = wmsPosters,
                        type = "PURCHASE_ORDER_SENT",
                        entityType = "PURCHASE_ORDER",
                        entityId = resultOrder.id,
                        title = "Оформлен заказ ${resultOrder.number} по заявке $demandNumber — ожидается поставка"
                    )
                }
                pendingEvents = events
            }
            resultOrder
        })
        notificationService.publishEvents(pendingEvents)
        return order
    }

    // -----------------------------------------------------------------------
    // Частичные поставки
    // -----------------------------------------------------------------------

    fun listDeliveries(orderId: Long?, currentUser: CurrentUser): List<PurchaseDeliveryDto> {
        currentUser.requirePermission("suppliers", "read")
        val deliveries = purchaseRepository.listDeliveries(orderId)
        if (deliveries.isEmpty()) return emptyList()
        val orderNumbers = deliveries.map { it.orderId }.distinct()
            .associateWith { purchaseRepository.findOrderById(it)?.number }
        val linesByDelivery = purchaseRepository.listDeliveryLinesForDeliveries(deliveries.map { it.id })
            .groupBy { it.deliveryId }
        val orderLines = purchaseRepository.listOrderLinesForOrders(orderNumbers.keys.toList())
            .associateBy { it.id }
        return deliveries.map { delivery ->
            delivery.toDto(
                orderNumbers[delivery.orderId],
                linesByDelivery[delivery.id].orEmpty().map { line ->
                    val orderLine = orderLines[line.orderLineId]
                    val nomenclature = orderLine?.let { purchaseRepository.findNomenclatureById(it.nomenclatureId) }
                    line.toDto(nomenclature?.id, nomenclature?.name)
                }
            )
        }
    }

    fun registerDelivery(
        orderId: Long,
        request: CreatePurchaseDeliveryRequest,
        currentUser: CurrentUser
    ): CommandResponse {
        currentUser.requirePermission("suppliers", "receive")
        if (request.lines.isEmpty()) {
            throw ValidationException(
                "Delivery must contain at least one line",
                mapOf("lines" to "required")
            )
        }
        val deliveredAt = parseDate(request.deliveredAt, "deliveredAt")
            ?: throw ValidationException("Delivery date is required", mapOf("deliveredAt" to "required"))
        val payload = json.encodeToString(CreatePurchaseDeliveryRequest.serializer(), request)
        return executeIdempotent(
            "PURCHASE_DELIVERY_CREATE", request.idempotencyKey, payload, HttpStatusCode.Created, currentUser
        ) {
                val order = purchaseRepository.lockOrderById(orderId)
                    ?: throw NotFoundException("purchase_order", orderId)
                if (order.status !in setOf("SENT", "PARTIALLY_DELIVERED")) {
                    throw ConflictException(
                        "Delivery can be registered only for a SENT or PARTIALLY_DELIVERED order; " +
                            "current status is ${order.status}"
                    )
                }
                val orderLines = purchaseRepository.listOrderLines(orderId).associateBy { it.id }
                val normalized = request.lines.mapIndexed { index, line ->
                    val orderLine = orderLines[line.orderLineId]
                        ?: throw ValidationException(
                            "Order line does not belong to this order",
                            mapOf("lines[$index].orderLineId" to "not_in_order")
                        )
                    val quantity = parseQuantity(line.quantity, "lines[$index].quantity")
                    if (quantity <= BigDecimal.ZERO) {
                        throw ValidationException(
                            "Delivered quantity must be positive",
                            mapOf("lines[$index].quantity" to "not_positive")
                        )
                    }
                    val alreadyDelivered = purchaseRepository.deliveredQuantityForLine(orderLine.id)
                    val remaining = orderLine.quantity - alreadyDelivered
                    if (quantity > remaining) {
                        throw ConflictException(
                            "Delivered quantity ${quantity.toPlainString()} exceeds remaining " +
                                "${remaining.max(BigDecimal.ZERO).toPlainString()} for line ${orderLine.lineNo}"
                        )
                    }
                    PurchaseOrderRepository.NewDeliveryLine(
                        orderLineId = orderLine.id,
                        quantity = quantity,
                        note = line.note?.trim()
                    )
                }
                if (normalized.map { it.orderLineId }.distinct().size != normalized.size) {
                    throw ValidationException(
                        "Each order line may appear only once per delivery",
                        mapOf("lines" to "duplicate_order_line")
                    )
                }
                val number = purchaseRepository.generateDeliveryNumber()
                val delivery = purchaseRepository.insertDelivery(
                    number = number,
                    orderId = orderId,
                    deliveredAt = deliveredAt,
                    deliveryNote = request.deliveryNote?.trim(),
                    sourceDocument = request.sourceDocument?.trim(),
                    note = request.note?.trim(),
                    createdBy = currentUser.userId
                )
                purchaseRepository.insertDeliveryLines(delivery.id, normalized, currentUser.userId)

                var allDelivered = true
                purchaseRepository.listOrderLines(orderId).forEach { orderLine ->
                    val total = purchaseRepository.deliveredQuantityForLine(orderLine.id)
                    purchaseRepository.updateDeliveredQuantity(orderLine.id, total)
                    if (total < orderLine.quantity) allDelivered = false
                }
                val newStatus = if (allDelivered) "DELIVERED" else "PARTIALLY_DELIVERED"
                val updatedOrder = purchaseRepository.updateOrderStatus(
                    id = orderId,
                    expectedVersion = order.version,
                    fromStatuses = listOf("SENT", "PARTIALLY_DELIVERED"),
                    newStatus = newStatus,
                    updatedBy = currentUser.userId
                ) ?: throw ConflictException("Purchase order was modified concurrently")
                purchaseRepository.insertOrderHistory(
                    orderId, "DELIVERY_REGISTERED", order.status, updatedOrder.status,
                    "Поставка $number", currentUser.userId
                )
                auditService.log(
                    currentUser.userId, "REGISTER_DELIVERY", "PURCHASE_ORDER", orderId,
                    mapOf(
                        "orderNumber" to order.number,
                        "deliveryNumber" to number,
                        "lines" to normalized.size
                    )
                )
                delivery.id to json.encodeToString(
                    PurchaseDeliveryDto.serializer(),
                    delivery.toDto(
                        order.number,
                        purchaseRepository.listDeliveryLines(delivery.id).map { line ->
                            val orderLine = purchaseRepository.findOrderLineById(line.orderLineId)
                            val nomenclature = orderLine?.let {
                                purchaseRepository.findNomenclatureById(it.nomenclatureId)
                            }
                            line.toDto(nomenclature?.id, nomenclature?.name)
                        }
                    )
                )
        }
    }

    fun cancelDelivery(
        id: Long,
        request: CancelPurchaseDeliveryRequest,
        currentUser: CurrentUser
    ): CommandResponse {
        currentUser.requirePermission("suppliers", "receive")
        if (request.reason.isBlank()) {
            throw ValidationException("Cancel reason is required", mapOf("reason" to "required"))
        }
        val payload = json.encodeToString(CancelPurchaseDeliveryRequest.serializer(), request)
        return executeIdempotent(
            "PURCHASE_DELIVERY_CANCEL", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser
        ) {
                val delivery = purchaseRepository.lockDeliveryById(id)
                    ?: throw NotFoundException("purchase_delivery", id)
                if (delivery.status != "REGISTERED") {
                    throw ConflictException("Delivery is already cancelled")
                }
                val order = purchaseRepository.lockOrderById(delivery.orderId)
                    ?: throw NotFoundException("purchase_order", delivery.orderId)
                val cancelled = purchaseRepository.markDeliveryCancelled(id, request.version, currentUser.userId)
                    ?: throw ConflictException("Delivery was modified concurrently")
                // Пересчитываем поставленные количества: отмена возвращает остаток.
                var anyDelivered = false
                var allDelivered = true
                purchaseRepository.listOrderLines(order.id).forEach { line ->
                    val total = purchaseRepository.deliveredQuantityForLine(line.id)
                    purchaseRepository.updateDeliveredQuantity(line.id, total)
                    if (total > BigDecimal.ZERO) anyDelivered = true
                    if (total < line.quantity) allDelivered = false
                }
                val newStatus = when {
                    allDelivered && anyDelivered -> "DELIVERED"
                    anyDelivered -> "PARTIALLY_DELIVERED"
                    else -> "SENT"
                }
                val updatedOrder = purchaseRepository.updateOrderStatus(
                    id = order.id,
                    expectedVersion = order.version,
                    fromStatuses = listOf("SENT", "PARTIALLY_DELIVERED", "DELIVERED"),
                    newStatus = newStatus,
                    updatedBy = currentUser.userId
                ) ?: throw ConflictException("Purchase order was modified concurrently")
                purchaseRepository.insertOrderHistory(
                    order.id, "DELIVERY_CANCELLED", order.status, updatedOrder.status,
                    request.reason.trim(), currentUser.userId
                )
                auditService.log(
                    currentUser.userId, "CANCEL_DELIVERY", "PURCHASE_ORDER", order.id,
                    mapOf("deliveryNumber" to delivery.number, "reason" to request.reason.trim())
                )
                id to json.encodeToString(
                    PurchaseDeliveryDto.serializer(),
                    cancelled.toDto(order.number, purchaseRepository.listDeliveryLines(id).map { line ->
                        val orderLine = purchaseRepository.findOrderLineById(line.orderLineId)
                        val nomenclature = orderLine?.let {
                            purchaseRepository.findNomenclatureById(it.nomenclatureId)
                        }
                        line.toDto(nomenclature?.id, nomenclature?.name)
                    })
                )
        }
    }

    // -----------------------------------------------------------------------
    // Внутреннее
    // -----------------------------------------------------------------------

    private data class OfferLineView(
        val offerLineId: Long,
        val nomenclatureId: Long,
        val quantity: BigDecimal,
        val unit: String,
        val unitPrice: BigDecimal,
        val vatRate: BigDecimal
    )

    private fun normalizeLines(
        offerLines: List<org.example.project.generated.jooq.tables.records.SupplierOfferLinesRecord>,
        overrides: List<CreatePurchaseOrderLineInput>?
    ): List<OfferLineView> {
        val byOfferLine = offerLines.associateBy { it.id }
        if (overrides == null) {
            return offerLines.map {
                OfferLineView(it.id, it.nomenclatureId, it.quantity, it.unit, it.unitPrice, it.vatRate)
            }
        }
        if (overrides.isEmpty()) {
            throw ValidationException("Order must contain at least one line", mapOf("lines" to "required"))
        }
        return overrides.mapIndexed { index, override ->
            val offerLine = byOfferLine[override.offerLineId]
                ?: throw ValidationException(
                    "Offer line does not belong to the selected offer",
                    mapOf("lines[$index].offerLineId" to "not_in_offer")
                )
            val quantity = parseQuantity(override.quantity, "lines[$index].quantity")
            if (quantity <= BigDecimal.ZERO) {
                throw ValidationException(
                    "Ordered quantity must be positive",
                    mapOf("lines[$index].quantity" to "not_positive")
                )
            }
            if (quantity > offerLine.quantity) {
                throw ValidationException(
                    "Ordered quantity cannot exceed the offered quantity",
                    mapOf("lines[$index].quantity" to "exceeds_offer")
                )
            }
            OfferLineView(offerLine.id, offerLine.nomenclatureId, quantity, offerLine.unit, offerLine.unitPrice, offerLine.vatRate)
        }
    }

    private fun resolveDemands(
        request: CreatePurchaseOrderRequest,
        offer: org.example.project.generated.jooq.tables.records.SupplierOffersRecord
    ): List<Long> {
        val requested = request.demandIds.ifEmpty { listOf(offer.demandId) }
        requested.distinct().forEach { demandId ->
            purchaseRepository.findDemandById(demandId) ?: throw NotFoundException("demand", demandId)
        }
        // Предложение относится к своей потребности: связь с ней обязательна,
        // иначе заказ теряет прослеживаемость «потребность → заказ».
        if (offer.demandId !in requested) {
            throw ValidationException(
                "Purchase order must reference the demand of the selected offer",
                mapOf("demandIds" to "missing_offer_demand")
            )
        }
        return requested.distinct()
    }

    private fun applyLineChanges(
        order: PurchaseOrdersRecord,
        lines: List<UpdatePurchaseOrderLineInput>,
        reasons: MutableList<String>
    ) {
        if (lines.isEmpty()) {
            throw ValidationException("Order must contain at least one line", mapOf("lines" to "required"))
        }
        val orderLines = purchaseRepository.listOrderLines(order.id).associateBy { it.id }
        lines.forEachIndexed { index, line ->
            // В изменении заказа идентификатор — это id строки заказа, а не предложения.
            val orderLine = orderLines[line.orderLineId]
                ?: throw ValidationException(
                    "Order line does not belong to this order",
                    mapOf("lines[$index].orderLineId" to "not_in_order")
                )
            val quantity = parseQuantity(line.quantity, "lines[$index].quantity")
            if (quantity <= BigDecimal.ZERO) {
                throw ValidationException(
                    "Ordered quantity must be positive",
                    mapOf("lines[$index].quantity" to "not_positive")
                )
            }
            if (quantity < orderLine.deliveredQuantity) {
                throw ConflictException(
                    "Ordered quantity cannot be less than already delivered " +
                        orderLine.deliveredQuantity.toPlainString()
                )
            }
            val offerLineId = orderLine.offerLineId
                ?: throw ConflictException("Order line has no source offer line")
            val offerLine = purchaseRepository.findOfferLineById(offerLineId)
                ?: throw NotFoundException("supplier_offer_line", offerLineId)
            if (quantity > offerLine.quantity) {
                throw ValidationException(
                    "Ordered quantity exceeds the selected offer",
                    mapOf("lines[$index].quantity" to "exceeds_offer")
                )
            }
            if (quantity != orderLine.quantity) {
                purchaseRepository.updateOrderLineQuantity(orderLine.id, quantity)
                reasons += "изменено количество по позиции ${orderLine.lineNo}"
            }
        }
    }

    private fun transition(
        id: Long,
        request: PurchaseOrderActionRequest,
        currentUser: CurrentUser,
        action: String,
        fromStatuses: List<String>,
        newStatus: String,
        requirePermission: String,
        permissionAction: String,
        extraCheck: (PurchaseOrdersRecord) -> Unit
    ): CommandResponse {
        currentUser.requirePermission(requirePermission, permissionAction)
        val payload = json.encodeToString(PurchaseOrderActionRequest.serializer(), request)
        return executeIdempotent(
            "PURCHASE_ORDER_$action", request.idempotencyKey, payload, HttpStatusCode.OK, currentUser
        ) {
                val order = purchaseRepository.findOrderById(id)
                    ?: throw NotFoundException("purchase_order", id)
                extraCheck(order)
                val now = if (newStatus == "APPROVED") OffsetDateTime.now() else null
                val orderedAt = if (newStatus == "SENT") OffsetDateTime.now() else null
                val updated = purchaseRepository.updateOrderStatus(
                    id = id,
                    expectedVersion = request.version,
                    fromStatuses = fromStatuses,
                    newStatus = newStatus,
                    approvedBy = if (newStatus == "APPROVED") currentUser.userId else null,
                    approvedAt = now,
                    approvalNote = if (newStatus == "APPROVED") request.note?.trim() else null,
                    orderedAt = orderedAt,
                    // При явном согласовании признак повторного согласования снимается.
                    reapprovalRequired = if (newStatus == "APPROVED") false else null,
                    reapprovalReason = null,
                    updatedBy = currentUser.userId
                ) ?: throw ConflictException(
                    "Purchase order cannot move to $newStatus from ${order.status}"
                )
                purchaseRepository.insertOrderHistory(
                    id, action, order.status, newStatus, request.note?.trim(), currentUser.userId
                )
                auditService.log(
                    currentUser.userId, action, "PURCHASE_ORDER", id,
                    mapOf("number" to order.number, "toStatus" to newStatus, "note" to request.note)
                )
                id to json.encodeToString(PurchaseOrderDto.serializer(), build(updated))
        }
    }

    private fun buildMany(orders: List<PurchaseOrdersRecord>): List<PurchaseOrderDto> {
        val orderIds = orders.map { it.id }
        val linesByOrder = purchaseRepository.listOrderLinesForOrders(orderIds).groupBy { it.orderId }
        val demandIds = purchaseRepository.listDemandIdsForOrders(orderIds)
        val demandNumbers = purchaseRepository.demandNumbers(demandIds.values.flatten().distinct())
        val totals = purchaseRepository.orderTotalsBatch(orderIds)
        val userNames = purchaseRepository.findUserNames(orders.mapNotNull { it.paidBy })
        val offerInvoiceDocs = purchaseRepository.findOfferInvoiceDocumentIds(orders.map { it.offerId })
        val nomenclatureCache = mutableMapOf<Long, Pair<String?, String?>?>()
        return orders.map { order ->
            order.toDto(
                supplierName = purchaseRepository.findSupplierById(order.supplierId)?.name,
                offerNumber = purchaseRepository.findOfferById(order.offerId)?.number,
                demandIds = demandIds[order.id].orEmpty(),
                lines = linesByOrder[order.id].orEmpty().map { line ->
                    val nomenclature = nomenclatureCache.getOrPut(line.nomenclatureId) {
                        purchaseRepository.findNomenclatureById(line.nomenclatureId)
                            ?.let { it.code to it.name }
                    }
                    line.toDto(nomenclature?.first, nomenclature?.second)
                },
                totals = totals[order.id],
                demandNumbers = demandIds[order.id].orEmpty().mapNotNull { demandNumbers[it] },
                paidByName = order.paidBy?.let { userNames[it] },
                invoiceDocumentId = offerInvoiceDocs[order.offerId]
            )
        }
    }

    private fun build(order: PurchaseOrdersRecord): PurchaseOrderDto = buildMany(listOf(order)).first()

    private fun parseQuantity(value: String, field: String): BigDecimal =
        try {
            BigDecimal(value.trim().replace(" ", "").replace(',', '.')).setScale(4, RoundingMode.HALF_UP)
        } catch (_: NumberFormatException) {
            throw ValidationException("Invalid decimal value", mapOf(field to "format"))
        }

    private fun parseDate(value: String, field: String): LocalDate? {
        if (value.isBlank()) return null
        return try {
            LocalDate.parse(value.trim())
        } catch (_: DateTimeParseException) {
            throw ValidationException("Invalid ISO date", mapOf(field to "format"))
        }
    }

    private fun executeIdempotent(
        operation: String,
        idempotencyKey: String?,
        requestPayload: String,
        successStatus: HttpStatusCode,
        currentUser: CurrentUser,
        work: () -> Pair<Long, String>
    ): CommandResponse {
        val key = idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ValidationException(
                "Idempotency key is required",
                mapOf("idempotencyKey" to "required")
            )
        val requestHash = sha256("$operation|$requestPayload")
        try {
            return dsl.transactionResult(TransactionalCallable {
                val existing = purchaseRepository.findIdempotency(currentUser.userId, operation, key)
                if (existing != null) {
                    if (existing.requestHash != requestHash) {
                        throw ConflictException("Idempotency key was already used with a different request")
                    }
                    return@TransactionalCallable CommandResponse(
                        HttpStatusCode.fromValue(existing.responseStatus),
                        existing.responseBody
                    )
                }
                val (entityId, dataJson) = work()
                val body = """{"success":true,"data":$dataJson}"""
                purchaseRepository.insertIdempotency(
                    currentUser.userId, operation, key, requestHash,
                    "PURCHASE_ORDER", entityId, successStatus.value, body
                )
                CommandResponse(successStatus, body)
            })
        } catch (e: org.jooq.exception.DataAccessException) {
            if (e.sqlState() == "23505" && e.message?.contains("uq_purchase_idempotency") == true) {
                val existing = purchaseRepository.findIdempotency(currentUser.userId, operation, key)
                if (existing != null && existing.requestHash == requestHash) {
                    return CommandResponse(HttpStatusCode.fromValue(existing.responseStatus), existing.responseBody)
                }
                throw ConflictException("Idempotency key was already used with a different request")
            }
            throw e
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
