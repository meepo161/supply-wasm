package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.example.project.dto.*
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Заказ поставщику и частичные поставки (SUP-15, SUP-16).
 *
 * Проверяется ТЗ §9.3–9.8:
 *  - заказ оформляет снабженец только из выбранного предложения (D-28);
 *  - заказ проходит согласование, а без согласования не отправляется;
 *  - существенное изменение после согласования возвращает заказ на согласование;
 *  - поставки частичные, «заказано/поставлено/осталось» ведёт база;
 *  - отмена поставки возвращает остаток;
 *  - команды идемпотентны, чужое тело с тем же ключом отклоняется.
 */
class PurchaseOrderRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `order lifecycle with partial deliveries`() = testApp {
        val ctx = bootstrap()
        val order = createOrderForSelectedOffer(ctx, quantity = "10", unitPrice = "500.00")

        assertEquals(PurchaseOrderStatus.DRAFT, order.status)
        assertTrue(order.number.startsWith("PO-"), order.number)
        assertEquals("10.0000", order.lines.first().quantity)
        assertEquals(1, order.demandIds.size)

        // Отправить без согласования нельзя.
        val earlySend = ctx.buyer.post("/api/purchase-orders/${order.id}/send") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PurchaseOrderActionRequest(order.version, idempotencyKey = "send-early")))
        }
        assertEquals(HttpStatusCode.Conflict, earlySend.status, earlySend.bodyAsText())

        val submitted = action(ctx.buyer, order.id, "submit", order.version, "submit-1")
        assertEquals(PurchaseOrderStatus.PENDING_APPROVAL, submitted.status)

        // Снабженец не согласует собственный заказ без отдельного права.
        val selfApprove = ctx.buyer.post("/api/purchase-orders/${order.id}/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PurchaseOrderActionRequest(submitted.version, idempotencyKey = "approve-self")))
        }
        assertEquals(HttpStatusCode.Forbidden, selfApprove.status, selfApprove.bodyAsText())

        val approved = action(ctx.approver, order.id, "approve", submitted.version, "approve-1")
        assertEquals(PurchaseOrderStatus.APPROVED, approved.status)
        assertNotNull(approved.approvedAt)

        val sent = action(ctx.buyer, order.id, "send", approved.version, "send-1")
        assertEquals(PurchaseOrderStatus.SENT, sent.status)
        assertNotNull(sent.orderedAt)

        // Частичная поставка: 4 из 10.
        val first = deliver(ctx.buyer, sent, listOf(sent.lines.first().id to "4"), "pd-1")
        assertEquals(PurchaseDeliveryStatus.REGISTERED, first.status)
        assertTrue(first.number.startsWith("PD-"), first.number)

        val afterFirst = getOrder(ctx.buyer, sent.id)
        assertEquals(PurchaseOrderStatus.PARTIALLY_DELIVERED, afterFirst.status)
        assertEquals("4.0000", afterFirst.lines.first().deliveredQuantity)
        assertEquals("6.0000", afterFirst.lines.first().remainingQuantity)
        assertEquals("4.0000", afterFirst.totals?.deliveredQuantity)

        // Больше остатка принять нельзя.
        val tooMuch = ctx.buyer.post("/api/purchase-orders/${sent.id}/deliveries") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreatePurchaseDeliveryRequest(
                        deliveredAt = today(),
                        lines = listOf(CreatePurchaseDeliveryLineInput(sent.lines.first().id, "7")),
                        idempotencyKey = "pd-too-much"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Conflict, tooMuch.status, tooMuch.bodyAsText())

        // Остаток 6 закрывает заказ.
        val second = deliver(ctx.buyer, afterFirst, listOf(afterFirst.lines.first().id to "6"), "pd-2")
        assertNotNull(second.id)
        val delivered = getOrder(ctx.buyer, sent.id)
        assertEquals(PurchaseOrderStatus.DELIVERED, delivered.status)
        assertEquals("0.0000", delivered.lines.first().remainingQuantity)

        // Поставленному заказу поставку не добавляют.
        val extra = ctx.buyer.post("/api/purchase-orders/${sent.id}/deliveries") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreatePurchaseDeliveryRequest(
                        deliveredAt = today(),
                        lines = listOf(CreatePurchaseDeliveryLineInput(sent.lines.first().id, "1")),
                        idempotencyKey = "pd-extra"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Conflict, extra.status, extra.bodyAsText())

        // Отмена второй поставки возвращает остаток и статус.
        val deliveries = getDeliveries(ctx.buyer, sent.id)
        assertEquals(2, deliveries.size)
        val cancelTarget = deliveries.first { it.number == second.number }
        val cancel = ctx.buyer.post("/api/purchase-deliveries/${cancelTarget.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CancelPurchaseDeliveryRequest(
                        version = cancelTarget.version,
                        reason = "Накладная оформлена ошибочно",
                        idempotencyKey = "pd-cancel-1"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, cancel.status, cancel.bodyAsText())
        val reverted = getOrder(ctx.buyer, sent.id)
        assertEquals(PurchaseOrderStatus.PARTIALLY_DELIVERED, reverted.status)
        assertEquals("6.0000", reverted.lines.first().remainingQuantity)

        // История фиксирует весь путь заказа.
        val history = getHistory(ctx.buyer, sent.id)
        val actions = history.map { it.action }
        assertTrue(actions.contains("CREATED"), actions.toString())
        assertTrue(actions.contains("SUBMIT"), actions.toString())
        assertTrue(actions.contains("APPROVE"), actions.toString())
        assertTrue(actions.contains("SEND"), actions.toString())
        assertTrue(actions.contains("DELIVERY_REGISTERED"), actions.toString())
        assertTrue(actions.contains("DELIVERY_CANCELLED"), actions.toString())
    }

    @Test
    fun `order cannot be created from an offer that is not selected`() = testApp {
        val ctx = bootstrap()
        val demand = getDemand(ctx.fromInitiator, ctx.demandId)
        // Предложение получено, но инициатор ещё ничего не выбрал (D-28).
        val offer = createOffer(ctx.buyer, ctx.demandId, ctx.supplierId, demand.lines.first().id)
        val attempt = ctx.buyer.post("/api/purchase-orders") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreatePurchaseOrderRequest(
                        offerId = offer.id,
                        idempotencyKey = "po-unselected-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Conflict, attempt.status, attempt.bodyAsText())
        assertTrue(attempt.bodyAsText().contains("SELECTED", ignoreCase = true), attempt.bodyAsText())
        assertEquals(0, ctx.buyer.get("/api/purchase-orders").let { response ->
            response.bodyAs<ApiResponse<List<PurchaseOrderDto>>>().data!!.size
        })
    }

    @Test
    fun `order cannot be created twice for the same offer`() = testApp {
        val ctx = bootstrap()
        val offer = createSelectedOffer(ctx)
        val first = ctx.buyer.post("/api/purchase-orders") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreatePurchaseOrderRequest(
                        offerId = offer.id,
                        idempotencyKey = "po-first-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val second = ctx.buyer.post("/api/purchase-orders") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreatePurchaseOrderRequest(
                        offerId = offer.id,
                        idempotencyKey = "po-second-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Conflict, second.status, second.bodyAsText())
    }

    @Test
    fun `significant change after approval requires reapproval`() = testApp {
        val ctx = bootstrap()
        val order = createOrderForSelectedOffer(ctx, quantity = "5", unitPrice = "100.00")
        val submitted = action(ctx.buyer, order.id, "submit", order.version, "submit-r")
        val approved = action(ctx.approver, order.id, "approve", submitted.version, "approve-r")

        // Без обоснования существенное изменение отклоняется.
        val noReason = ctx.buyer.put("/api/purchase-orders/${order.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    UpdatePurchaseOrderRequest(
                        expectedAt = java.time.LocalDate.now().plusDays(30).toString(),
                        lines = listOf(UpdatePurchaseOrderLineInput(approved.lines.first().id, "3")),
                        version = approved.version
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, noReason.status, noReason.bodyAsText())
        assertTrue(noReason.bodyAsText().contains("changeReason"), noReason.bodyAsText())

        val updated = ctx.buyer.put("/api/purchase-orders/${order.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    UpdatePurchaseOrderRequest(
                        expectedAt = java.time.LocalDate.now().plusDays(30).toString(),
                        lines = listOf(UpdatePurchaseOrderLineInput(approved.lines.first().id, "3")),
                        changeReason = "Поставщик подтвердил меньшую партию",
                        version = approved.version
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, updated.status, updated.bodyAsText())
        val changed = updated.bodyAs<ApiResponse<PurchaseOrderDto>>().data!!
        assertEquals(PurchaseOrderStatus.PENDING_APPROVAL, changed.status)
        assertTrue(changed.reapprovalRequired)
        assertEquals("3.0000", changed.lines.first().quantity)

        val aboveOffer = ctx.buyer.put("/api/purchase-orders/${order.id}") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    UpdatePurchaseOrderRequest(
                        lines = listOf(UpdatePurchaseOrderLineInput(approved.lines.first().id, "10")),
                        changeReason = "Попытка увеличить количество сверх предложения",
                        version = changed.version
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, aboveOffer.status, aboveOffer.bodyAsText())
        assertTrue(aboveOffer.bodyAsText().contains("exceeds_offer"), aboveOffer.bodyAsText())

        // Отправка заблокирована до повторного согласования.
        val sendBlocked = ctx.buyer.post("/api/purchase-orders/${order.id}/send") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PurchaseOrderActionRequest(changed.version, idempotencyKey = "send-blocked")))
        }
        assertEquals(HttpStatusCode.Conflict, sendBlocked.status, sendBlocked.bodyAsText())

        // Повторное согласование снимает признак и позволяет отправить.
        val reapproved = action(ctx.approver, order.id, "approve", changed.version, "approve-again")
        assertEquals(PurchaseOrderStatus.APPROVED, reapproved.status)
        assertTrue(!reapproved.reapprovalRequired)
        val resent = action(ctx.buyer, order.id, "send", reapproved.version, "send-again")
        assertEquals(PurchaseOrderStatus.SENT, resent.status)
    }

    @Test
    fun `order creation is idempotent and rejects a different body`() = testApp {
        val ctx = bootstrap()
        val offer = createSelectedOffer(ctx)
        val key = "po-idem-${UUID.randomUUID()}"
        val request = CreatePurchaseOrderRequest(offerId = offer.id, idempotencyKey = key)
        val first = ctx.buyer.post("/api/purchase-orders") {
            contentType(ContentType.Application.Json)
            setBody(stringify(request))
        }
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val firstOrder = first.bodyAs<ApiResponse<PurchaseOrderDto>>().data!!

        val replay = ctx.buyer.post("/api/purchase-orders") {
            contentType(ContentType.Application.Json)
            setBody(stringify(request))
        }
        assertEquals(HttpStatusCode.Created, replay.status, replay.bodyAsText())
        assertEquals(
            firstOrder.id,
            replay.bodyAs<ApiResponse<PurchaseOrderDto>>().data!!.id
        )

        val conflicting = ctx.buyer.post("/api/purchase-orders") {
            contentType(ContentType.Application.Json)
            setBody(stringify(request.copy(note = "другое тело")))
        }
        assertEquals(HttpStatusCode.Conflict, conflicting.status, conflicting.bodyAsText())
    }

    @Test
    fun `cancel order requires a reason and blocks deliveries`() = testApp {
        val ctx = bootstrap()
        val order = createOrderForSelectedOffer(ctx, quantity = "2", unitPrice = "50.00")
        val noReason = ctx.buyer.post("/api/purchase-orders/${order.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CancelPurchaseOrderRequest(order.version, "  ", "cancel-blank")))
        }
        assertEquals(HttpStatusCode.BadRequest, noReason.status, noReason.bodyAsText())

        val cancelled = ctx.buyer.post("/api/purchase-orders/${order.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CancelPurchaseOrderRequest(
                        version = order.version,
                        reason = "Потребность закрыта другим заказом",
                        idempotencyKey = "cancel-ok"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        assertEquals(
            PurchaseOrderStatus.CANCELLED,
            cancelled.bodyAs<ApiResponse<PurchaseOrderDto>>().data!!.status
        )
        val delivery = ctx.buyer.post("/api/purchase-orders/${order.id}/deliveries") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreatePurchaseDeliveryRequest(
                        deliveredAt = today(),
                        lines = listOf(CreatePurchaseDeliveryLineInput(order.lines.first().id, "1")),
                        idempotencyKey = "pd-cancelled-order"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Conflict, delivery.status, delivery.bodyAsText())
    }

    @Test
    fun `purchase order endpoints require permissions`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "admin", "admin123", "Admin User", emptyList())
        createUserWithRole(admin, "nobody", "password", "Nobody", emptyList())
        val nobody = loginClient("nobody", "password")

        assertEquals(HttpStatusCode.Forbidden, nobody.get("/api/purchase-orders").status)
        assertEquals(HttpStatusCode.Forbidden, nobody.get("/api/purchase-orders/1").status)
        assertEquals(HttpStatusCode.Forbidden, nobody.get("/api/purchase-deliveries").status)
    }

    // --- Сценарии и фикстуры ---

    private data class Ctx(
        val fromInitiator: HttpClient,
        val buyer: HttpClient,
        val approver: HttpClient,
        val supplierId: Long,
        val demandId: Long
    )

    private suspend fun ApplicationTestBuilder.bootstrap(verifySupplier: Boolean = true): Ctx {
        val admin = jsonClient()
        createUserWithRole(admin, "admin", "admin123", "Admin User", emptyList())
        createUserWithRole(admin, "initiator", "password", "Init", initiatorPermissions())
        createUserWithRole(admin, "buyer", "password", "Buyer", buyerPermissions())
        createUserWithRole(admin, "approver", "password", "Approver", approverPermissions())
        val initiator = loginClient("initiator", "password")
        val buyer = loginClient("buyer", "password")
        val approver = loginClient("approver", "password")

        val material = createNomenclature(admin)
        val demand = createDemand(initiator, material.id)
        val supplierCard = createSupplier(buyer)
        if (verifySupplier) {
            setVerified(admin, supplierCard.supplier.id, supplierCard.supplier.version)
        }
        return Ctx(initiator, buyer, approver, supplierCard.supplier.id, demand.header.id)
    }

    /** Потребность → предложение → выбор инициатором. Возвращает выбранное предложение. */
    private suspend fun ApplicationTestBuilder.createSelectedOffer(ctx: Ctx): SupplierOfferDto {
        val demand = getDemand(ctx.fromInitiator, ctx.demandId)
        val offer = createOffer(ctx.buyer, ctx.demandId, ctx.supplierId, demand.lines.first().id)
        val selected = ctx.fromInitiator.post("/api/supplier-offers/${offer.id}/select") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SelectSupplierOfferRequest(
                        reason = "Оптимальные условия поставки",
                        version = offer.version,
                        idempotencyKey = "select-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, selected.status, selected.bodyAsText())
        return selected.bodyAs<ApiResponse<SupplierOfferDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.createOrderForSelectedOffer(
        ctx: Ctx,
        quantity: String,
        unitPrice: String
    ): PurchaseOrderDto {
        val demand = getDemand(ctx.fromInitiator, ctx.demandId)
        val offer = createOffer(
            ctx.buyer,
            ctx.demandId,
            ctx.supplierId,
            demand.lines.first().id,
            quantity = quantity,
            unitPrice = unitPrice
        )
        val selected = ctx.fromInitiator.post("/api/supplier-offers/${offer.id}/select") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SelectSupplierOfferRequest(
                        reason = "Выбрано по совокупности условий",
                        version = offer.version,
                        idempotencyKey = "select-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, selected.status, selected.bodyAsText())

        val response = ctx.buyer.post("/api/purchase-orders") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreatePurchaseOrderRequest(
                        offerId = offer.id,
                        expectedAt = java.time.LocalDate.now().plusDays(14).toString(),
                        paymentTerms = "Постоплата 30 дней",
                        deliveryAddress = "Склад, ул. Примерная, 1",
                        lines = listOf(
                            CreatePurchaseOrderLineInput(
                                offer.lines.first().id,
                                quantity
                            )
                        ),
                        idempotencyKey = "po-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<PurchaseOrderDto>>().data!!
    }

    private suspend fun action(
        client: HttpClient,
        orderId: Long,
        verb: String,
        version: Long,
        keyPrefix: String
    ): PurchaseOrderDto {
        val response = client.post("/api/purchase-orders/$orderId/$verb") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    PurchaseOrderActionRequest(
                        version = version,
                        idempotencyKey = "$keyPrefix-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<PurchaseOrderDto>>().data!!
    }

    private suspend fun deliver(
        client: HttpClient,
        order: PurchaseOrderDto,
        lines: List<Pair<Long, String>>,
        key: String
    ): PurchaseDeliveryDto {
        val response = client.post("/api/purchase-orders/${order.id}/deliveries") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreatePurchaseDeliveryRequest(
                        deliveredAt = today(),
                        deliveryNote = "Накладная к заказу ${order.number}",
                        lines = lines.map { (lineId, quantity) ->
                            CreatePurchaseDeliveryLineInput(lineId, quantity)
                        },
                        idempotencyKey = "$key-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<PurchaseDeliveryDto>>().data!!
    }

    private suspend fun getOrder(client: HttpClient, id: Long): PurchaseOrderDto {
        val response = client.get("/api/purchase-orders/$id")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<PurchaseOrderDto>>().data!!
    }

    private suspend fun getDeliveries(client: HttpClient, orderId: Long): List<PurchaseDeliveryDto> {
        val response = client.get("/api/purchase-orders/$orderId/deliveries")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<List<PurchaseDeliveryDto>>>().data!!
    }

    private suspend fun getHistory(client: HttpClient, orderId: Long): List<PurchaseOrderHistoryDto> {
        val response = client.get("/api/purchase-orders/$orderId/history")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<List<PurchaseOrderHistoryDto>>>().data!!
    }

    private suspend fun getDemand(client: HttpClient, id: Long): DemandDetailDto {
        val response = client.get("/api/demands/$id")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<DemandDetailDto>>().data!!
    }

    private suspend fun createNomenclature(client: HttpClient): NomenclatureDto {
        val response = client.post("/api/wms/nomenclature") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateNomenclatureRequest(
                        code = "PO-MAT-${UUID.randomUUID().toString().take(6)}",
                        name = "Материал для заказа",
                        type = NomenclatureType.RAW_MATERIAL,
                        unit = "pcs"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<NomenclatureDto>>().data!!
    }

    private suspend fun createDemand(client: HttpClient, nomenclatureId: Long): DemandDetailDto {
        val response = client.post("/api/demands") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateDemandRequest(
                        sourceType = DemandSourceType.MANUAL,
                        priority = DemandPriority.NORMAL,
                        requiredDate = java.time.LocalDate.now().plusDays(20).toString(),
                        lines = listOf(DemandLineInput(1, nomenclatureId, "10", "pcs"))
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<DemandDetailDto>>().data!!
    }

    private suspend fun createSupplier(client: HttpClient): SupplierCardDto {
        val response = client.post("/api/suppliers") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateSupplierRequest(
                        name = "ООО Заказ",
                        inn = randomValidInn(),
                        idempotencyKey = "sup-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<SupplierCardDto>>().data!!
    }

    private suspend fun setVerified(admin: HttpClient, supplierId: Long, version: Long) {
        val response = admin.post("/api/suppliers/$supplierId/verification") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SupplierVerificationRequest(
                        status = SupplierVerificationStatus.VERIFIED,
                        note = "Проверено",
                        version = version,
                        idempotencyKey = "verify-$supplierId"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun createOffer(
        client: HttpClient,
        demandId: Long,
        supplierId: Long,
        demandLineId: Long,
        quantity: String = "10",
        unitPrice: String = "500.00"
    ): SupplierOfferDto {
        val response = client.post("/api/supplier-offers") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateSupplierOfferRequest(
                        demandId = demandId,
                        supplierId = supplierId,
                        compliance = SupplierOfferCompliance.FULL,
                        documentsComplete = true,
                        deliveryDays = 5,
                        paymentTerms = "Постоплата 30 дней",
                        validUntil = java.time.LocalDate.now().plusDays(45).toString(),
                        lines = listOf(
                            SupplierOfferLineInput(
                                demandLineId = demandLineId,
                                quantity = quantity,
                                unitPrice = unitPrice,
                                vatRate = "20"
                            )
                        ),
                        idempotencyKey = "offer-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<SupplierOfferDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.createUserWithRole(
        adminClient: HttpClient,
        login: String,
        password: String,
        fullName: String,
        permissions: List<CreatePermissionRequest>
    ): UserDto {
        adminClient.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        adminClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        if (login == "admin") {
            val me = adminClient.get("/api/auth/me")
            assertEquals(HttpStatusCode.OK, me.status, me.bodyAsText())
            return me.bodyAs<ApiResponse<MeResponse>>().data!!.user
        }
        if (permissions.isNotEmpty()) {
            val roleResponse = adminClient.post("/api/roles") {
                contentType(ContentType.Application.Json)
                setBody(
                    stringify(
                        CreateRoleRequest(
                            code = login.uppercase(),
                            name = fullName,
                            permissions = permissions
                        )
                    )
                )
            }
            assertEquals(HttpStatusCode.Created, roleResponse.status, roleResponse.bodyAsText())
            val role = roleResponse.bodyAs<ApiResponse<RoleDto>>().data!!
            val userResponse = adminClient.post("/api/users") {
                contentType(ContentType.Application.Json)
                setBody(stringify(CreateUserRequest(login, password, fullName, roleIds = listOf(role.id))))
            }
            assertEquals(HttpStatusCode.Created, userResponse.status, userResponse.bodyAsText())
            return userResponse.bodyAs<ApiResponse<UserDto>>().data!!
        }
        val userResponse = adminClient.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUserRequest(login, password, fullName)))
        }
        assertEquals(HttpStatusCode.Created, userResponse.status, userResponse.bodyAsText())
        return userResponse.bodyAs<ApiResponse<UserDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.loginClient(login: String, password: String): HttpClient {
        val client = jsonClient()
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest(login, password)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return client
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) {
        testApplication {
            environment {
                config = HoconApplicationConfig(
                    ConfigFactory.parseString(
                        """
                        ktor {
                          deployment { port = 0 }
                          application {
                            modules = [org.example.project.ApplicationKt.module]
                          }
                        }
                        database {
                          url = "${TestDatabase.TEST_URL}"
                          user = "erp"
                          password = "erp"
                        }
                        session {
                          ttlMinutes = 60
                          cookieName = "erp_session"
                        }
                        security {
                          allowedOrigins = "http://localhost:8080,http://localhost:8081"
                          cookieSecure = false
                        }
                        storage {
                          path = "./.local/test-storage"
                        }
                        """.trimIndent()
                    )
                )
            }
            block()
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(HttpCookies)
    }

    private suspend inline fun <reified T> HttpResponse.bodyAs(): T =
        json.decodeFromString(serializer<T>(), bodyAsText())

    private inline fun <reified T> stringify(value: T): String =
        json.encodeToString(serializer<T>(), value)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private fun today(): String = java.time.LocalDate.now().toString()

        @JvmStatic
        @BeforeClass
        fun setup() = TestDatabase.init()

        @JvmStatic
        @AfterClass
        fun teardown() = TestDatabase.destroy()

        private fun initiatorPermissions() = listOf(
            CreatePermissionRequest("demands", "create"),
            CreatePermissionRequest("demands", "read_any"),
            CreatePermissionRequest("demands", "update"),
            CreatePermissionRequest("suppliers", "read"),
            CreatePermissionRequest("suppliers", "select"),
            CreatePermissionRequest("wms", "read")
        )

        private fun buyerPermissions() = listOf(
            CreatePermissionRequest("suppliers", "read"),
            CreatePermissionRequest("suppliers", "create"),
            CreatePermissionRequest("suppliers", "update"),
            CreatePermissionRequest("suppliers", "offer"),
            CreatePermissionRequest("suppliers", "order"),
            CreatePermissionRequest("suppliers", "receive"),
            CreatePermissionRequest("demands", "read_any"),
            CreatePermissionRequest("wms", "read"),
            CreatePermissionRequest("users", "read_any")
        )

        private fun approverPermissions() = listOf(
            CreatePermissionRequest("suppliers", "read"),
            CreatePermissionRequest("suppliers", "approve_order"),
            CreatePermissionRequest("demands", "read_any"),
            CreatePermissionRequest("wms", "read")
        )
    }
}
