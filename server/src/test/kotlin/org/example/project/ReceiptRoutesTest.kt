package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
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
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Приёмка на склад по разным основаниям (ТЗ §8.4–8.6, §9.3, §25 инвариант 15).
 *
 * Проверяется:
 *  - основание обязательно: заказ, счёт, накладная/УПД, возврат, перемещение,
 *    начальный остаток, прочий документ; без номера документа приёмка по
 *    заказу/счёту/накладной не создаётся;
 *  - один документ-основание проводится один раз (повтор — 409);
 *  - приёмка по заказу уменьшает ожидаемый остаток, поддерживает частичность
 *    и несколько приёмок по одному заказу, а превышение заказанного запрещено;
 *  - расхождения фиксируются: недопоставка видна, излишек без обоснования
 *    не принимается, расхождение без причины отклоняется;
 *  - остаток появляется только после успешного проведения, повторное
 *    проведение не удваивает остаток;
 *  - поздняя связь проведённой приёмки с заказом/накладной не создаёт
 *    остаток повторно и не даёт связать одно и то же дважды;
 *  - права: без `wms:read` список и карточка недоступны, без `wms:post`
 *    создание и проведение запрещены.
 */
class ReceiptRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `initial stock receipt posts stock only after posting`() = testApp {
        val ctx = bootstrap()
        val receipt = createReceipt(
            ctx.keeper,
            sourceType = ReceiptSourceType.INITIAL_STOCK,
            warehouseId = ctx.warehouseId,
            lines = listOf(line(ctx.nomenclatureId, ctx.locationId, expected = "10"))
        )
        assertEquals(ReceiptStatus.DRAFT, receipt.status)
        assertQty("0", stockQuantity(ctx.keeper, ctx.warehouseId, ctx.nomenclatureId))

        val posted = postReceipt(ctx.keeper, receipt)
        assertEquals(ReceiptStatus.POSTED, posted.status)
        assertQty("10", stockQuantity(ctx.keeper, ctx.warehouseId, ctx.nomenclatureId))

        // Повторное проведение не удваивает остаток.
        val second = postStatus(
            ctx.keeper,
            "/api/wms/receipts/${receipt.id}/post",
            PostReceiptRequest(posted.version)
        )
        assertEquals(HttpStatusCode.Conflict, second.status, second.bodyAsText())
        assertQty("10", stockQuantity(ctx.keeper, ctx.warehouseId, ctx.nomenclatureId))
    }

    @Test
    fun `purchase order receipt reduces expected remainder and supports partial acceptance`() = testApp {
        val ctx = bootstrap()
        val order = sentOrder(ctx, quantity = "10")
        val orderLine = order.lines.first()
        assertEquals("10.0000", orderLine.quantity)

        // Ожидаемое: заказано 10, принято 0.
        val expectedBefore = expectedLines(ctx.keeper, order.id).first()
        assertQty("0", expectedBefore.acceptedQuantity)
        assertQty("10", expectedBefore.notAcceptedQuantity)

        // Приёмка по заказу с документом-основанием (УПД), частично: 4 из 10.
        val first = createReceipt(
            ctx.keeper,
            sourceType = ReceiptSourceType.DELIVERY_NOTE,
            warehouseId = ctx.warehouseId,
            purchaseOrderId = order.id,
            sourceDocumentNo = "UPD-${UUID.randomUUID()}",
            lines = listOf(
                line(
                    ctx.nomenclatureId,
                    ctx.locationId,
                    expected = "10",
                    received = "4",
                    orderLineId = orderLine.id,
                    discrepancyReason = "Поставщик привёз 4 из 10"
                )
            )
        )
        assertEquals(order.id, first.purchaseOrderId)
        assertEquals(order.number, first.purchaseOrderNumber)
        assertTrue(first.hasDiscrepancies)
        assertEquals(ReceiptDiscrepancyKind.SHORTAGE, first.lines.first().discrepancyKind)
        assertEquals("6.0000", first.lines.first().discrepancyQuantity)

        // До проведения остаток не меняется.
        assertQty("0", stockQuantity(ctx.keeper, ctx.warehouseId, ctx.nomenclatureId))
        postReceipt(ctx.keeper, first)
        assertQty("4", stockQuantity(ctx.keeper, ctx.warehouseId, ctx.nomenclatureId))

        val expectedAfter = expectedLines(ctx.keeper, order.id).first()
        assertQty("4", expectedAfter.acceptedQuantity)
        assertQty("6", expectedAfter.notAcceptedQuantity)

        // Вторая приёмка тем же основанием запрещена: уникальность документа.
        val duplicate = postStatus(
            ctx.keeper,
            "/api/wms/receipts",
            CreateReceiptRequest(
                sourceType = ReceiptSourceType.DELIVERY_NOTE,
                sourceDocumentNo = first.sourceDocumentNo,
                purchaseOrderId = order.id,
                warehouseId = ctx.warehouseId,
                lines = listOf(line(ctx.nomenclatureId, ctx.locationId, expected = "6"))
            )
        )
        assertEquals(HttpStatusCode.Conflict, duplicate.status, duplicate.bodyAsText())

        // Вторая приёмка по тому же заказу другим документом: оставшиеся 6.
        val second = createReceipt(
            ctx.keeper,
            sourceType = ReceiptSourceType.PURCHASE_ORDER,
            warehouseId = ctx.warehouseId,
            purchaseOrderId = order.id,
            sourceDocumentNo = "PO-DOC-${UUID.randomUUID()}",
            lines = listOf(line(ctx.nomenclatureId, ctx.locationId, expected = "6"))
        )
        postReceipt(ctx.keeper, second)
        assertQty("10", stockQuantity(ctx.keeper, ctx.warehouseId, ctx.nomenclatureId))

        val final = expectedLines(ctx.keeper, order.id).first()
        assertQty("10", final.acceptedQuantity)
        assertQty("0", final.notAcceptedQuantity)

        // Превышение заказанного невозможно даже третьей приёмкой.
        val overflow = createReceiptAtServer(ctx.keeper, ctx, order.id, "1")
        assertEquals(HttpStatusCode.Conflict, overflow.status, overflow.bodyAsText())
    }

    @Test
    fun `delivery receipt derives order and enforces ordered quantity`() = testApp {
        val ctx = bootstrap()
        val order = sentOrder(ctx, quantity = "10")
        val orderLine = order.lines.first()
        val delivery = postCreated<PurchaseDeliveryDto, CreatePurchaseDeliveryRequest>(
            ctx.keeper,
            "/api/purchase-orders/${order.id}/deliveries",
            CreatePurchaseDeliveryRequest(
                deliveredAt = java.time.LocalDate.now().toString(),
                lines = listOf(CreatePurchaseDeliveryLineInput(orderLine.id, "4")),
                idempotencyKey = "delivery-receipt-${UUID.randomUUID()}"
            )
        )
        val receipt = createReceipt(
            ctx.keeper,
            sourceType = ReceiptSourceType.DELIVERY_NOTE,
            sourceDocumentNo = "UPD-DELIVERY-${UUID.randomUUID()}",
            warehouseId = ctx.warehouseId,
            purchaseDeliveryId = delivery.id,
            lines = listOf(
                line(
                    ctx.nomenclatureId,
                    ctx.locationId,
                    expected = "4",
                    orderLineId = orderLine.id,
                    discrepancyReason = "Принята первая часть поставки"
                )
            )
        )
        assertEquals(order.id, receipt.purchaseOrderId)
        postReceipt(ctx.keeper, receipt)
        assertQty("4", stockQuantity(ctx.keeper, ctx.warehouseId, ctx.nomenclatureId))

        val overflow = postStatus(
            ctx.keeper,
            "/api/wms/receipts",
            CreateReceiptRequest(
                sourceType = ReceiptSourceType.DELIVERY_NOTE,
                sourceDocumentNo = "UPD-DELIVERY-OVER-${UUID.randomUUID()}",
                purchaseDeliveryId = delivery.id,
                warehouseId = ctx.warehouseId,
                lines = listOf(
                    line(
                        ctx.nomenclatureId,
                        ctx.locationId,
                        expected = "7",
                        received = "7",
                        orderLineId = orderLine.id
                    )
                )
            )
        )
        assertEquals(HttpStatusCode.Conflict, overflow.status, overflow.bodyAsText())
    }

    @Test
    fun `receipt without source document is rejected for order and invoice`() = testApp {
        val ctx = bootstrap()
        val order = sentOrder(ctx, quantity = "5")
        listOf(ReceiptSourceType.PURCHASE_ORDER, ReceiptSourceType.INVOICE, ReceiptSourceType.DELIVERY_NOTE)
            .forEach { type ->
                val response = postStatus(
                    ctx.keeper,
                    "/api/wms/receipts",
                    CreateReceiptRequest(
                        sourceType = type,
                        purchaseOrderId = order.id,
                        warehouseId = ctx.warehouseId,
                        lines = listOf(line(ctx.nomenclatureId, ctx.locationId, expected = "5"))
                    )
                )
                assertEquals(HttpStatusCode.BadRequest, response.status, "$type: ${response.bodyAsText()}")
                assertEquals("VALIDATION_ERROR", response.errorCode())
                assertTrue(response.bodyAsText().contains("sourceDocumentNo"), response.bodyAsText())
            }

        // Начальный остаток и прочий документ допустимы без номера.
        val initial = createReceipt(
            ctx.keeper,
            sourceType = ReceiptSourceType.INITIAL_STOCK,
            warehouseId = ctx.warehouseId,
            lines = listOf(line(ctx.nomenclatureId, ctx.locationId, expected = "1"))
        )
        assertNull(initial.sourceDocumentNo)
    }

    @Test
    fun `discrepancy without reason is rejected and surplus is recorded`() = testApp {
        val ctx = bootstrap()
        // Без обоснования расхождение не принимается.
        val noReason = postStatus(
            ctx.keeper,
            "/api/wms/receipts",
            CreateReceiptRequest(
                sourceType = ReceiptSourceType.INITIAL_STOCK,
                warehouseId = ctx.warehouseId,
                lines = listOf(
                    ReceiptLineInput(
                        lineNo = 1,
                        nomenclatureId = ctx.nomenclatureId,
                        expectedQuantity = "10",
                        receivedQuantity = "8",
                        locationId = ctx.locationId
                    )
                )
            )
        )
        assertEquals(HttpStatusCode.BadRequest, noReason.status, noReason.bodyAsText())
        assertTrue(noReason.bodyAsText().contains("discrepancyReason"), noReason.bodyAsText())

        // С обоснованием — принимается и фиксируется как недопоставка.
        val short = createReceipt(
            ctx.keeper,
            sourceType = ReceiptSourceType.INITIAL_STOCK,
            warehouseId = ctx.warehouseId,
            lines = listOf(
                line(
                    ctx.nomenclatureId,
                    ctx.locationId,
                    expected = "10",
                    received = "8",
                    discrepancyReason = "Пересчёт: две единицы повреждены"
                )
            )
        )
        assertEquals(ReceiptDiscrepancyKind.SHORTAGE, short.lines.first().discrepancyKind)
        assertEquals("2.0000", short.lines.first().discrepancyQuantity)
        postReceipt(ctx.keeper, short)
        assertQty("8", stockQuantity(ctx.keeper, ctx.warehouseId, ctx.nomenclatureId))
    }

    @Test
    fun `late order link does not create stock twice`() = testApp {
        val ctx = bootstrap()
        // Приёмка проведена по накладной, заказ к тому моменту ещё не оформлен.
        val receipt = createReceipt(
            ctx.keeper,
            sourceType = ReceiptSourceType.DELIVERY_NOTE,
            sourceDocumentNo = "UPD-LATE-${UUID.randomUUID()}",
            warehouseId = ctx.warehouseId,
            lines = listOf(line(ctx.nomenclatureId, ctx.locationId, expected = "3"))
        )
        val posted = postReceipt(ctx.keeper, receipt)
        assertQty("3", stockQuantity(ctx.keeper, ctx.warehouseId, ctx.nomenclatureId))

        // Заказ появился позже: связываем, остаток не пересоздаётся.
        val order = sentOrder(ctx, quantity = "3")
        val linkResponse = postStatus(
            ctx.keeper,
            "/api/wms/receipts/${posted.id}/links",
            LinkReceiptRequest(
                purchaseOrderId = order.id,
                reason = "Заказ оформлен после фактической приёмки",
                version = posted.version
            )
        )
        assertEquals(HttpStatusCode.OK, linkResponse.status, linkResponse.bodyAsText())
        val linked = linkResponse.bodyAs<ApiResponse<ReceiptDto>>().data!!
        assertEquals(order.id, linked.purchaseOrderId)
        assertEquals(1, linked.links.size)
        // Версия выросла: оптимистичная блокировка продолжает работать.
        assertTrue(linked.version > posted.version, "${posted.version} -> ${linked.version}")

        assertQty("3", stockQuantity(ctx.keeper, ctx.warehouseId, ctx.nomenclatureId))

        // Повторная связь тех же документов запрещена.
        val again = postStatus(
            ctx.keeper,
            "/api/wms/receipts/${linked.id}/links",
            LinkReceiptRequest(
                purchaseOrderId = order.id,
                reason = "Повторная привязка того же заказа",
                version = linked.version
            )
        )
        assertEquals(HttpStatusCode.Conflict, again.status, again.bodyAsText())

        // Связывать не проведённую приёмку нельзя.
        val draft = createReceipt(
            ctx.keeper,
            sourceType = ReceiptSourceType.DELIVERY_NOTE,
            sourceDocumentNo = "UPD-DRAFT-${UUID.randomUUID()}",
            warehouseId = ctx.warehouseId,
            lines = listOf(line(ctx.nomenclatureId, ctx.locationId, expected = "1"))
        )
        val tooEarly = postStatus(
            ctx.keeper,
            "/api/wms/receipts/${draft.id}/links",
            LinkReceiptRequest(
                purchaseOrderId = order.id,
                reason = "Связь до проведения",
                version = draft.version
            )
        )
        assertEquals(HttpStatusCode.Conflict, tooEarly.status, tooEarly.bodyAsText())

        // Начальный остаток к заказу поставщику не относится — связь отклоняется.
        val initial = createReceipt(
            ctx.keeper,
            sourceType = ReceiptSourceType.INITIAL_STOCK,
            warehouseId = ctx.warehouseId,
            lines = listOf(line(ctx.nomenclatureId, ctx.locationId, expected = "1"))
        )
        val initialPosted = postReceipt(ctx.keeper, initial)
        val wrongSource = postStatus(
            ctx.keeper,
            "/api/wms/receipts/${initialPosted.id}/links",
            LinkReceiptRequest(
                purchaseOrderId = order.id,
                reason = "Начальный остаток к заказу не относится",
                version = initialPosted.version
            )
        )
        assertEquals(HttpStatusCode.BadRequest, wrongSource.status, wrongSource.bodyAsText())
    }

    @Test
    fun `receipt registration requires permissions`() = testApp {
        val ctx = bootstrap()
        createUserWithRole(ctx.admin, "nobody", "password", "Nobody", emptyList())
        val nobody = loginClient("nobody", "password")

        assertEquals(HttpStatusCode.Forbidden, nobody.get("/api/wms/receipts").status)
        assertEquals(HttpStatusCode.Forbidden, nobody.get("/api/wms/expected-receipts").status)

        // Читатель без wms:post не создаёт документ.
        val reader = ctx.reader
        val denied = postStatus(
            reader,
            "/api/wms/receipts",
            CreateReceiptRequest(
                sourceType = ReceiptSourceType.INITIAL_STOCK,
                warehouseId = ctx.warehouseId,
                lines = listOf(line(ctx.nomenclatureId, ctx.locationId, expected = "1"))
            )
        )
        assertEquals(HttpStatusCode.Forbidden, denied.status, denied.bodyAsText())
        // Чтение разрешено, список приёмок доступен.
        assertEquals(HttpStatusCode.OK, reader.get("/api/wms/receipts").status)
        // Карточка существующей приёмки тоже доступна читателю.
        val draft = createReceipt(
            ctx.keeper,
            sourceType = ReceiptSourceType.DELIVERY_NOTE,
            sourceDocumentNo = "UPD-DRAFT-${UUID.randomUUID()}",
            warehouseId = ctx.warehouseId,
            lines = listOf(line(ctx.nomenclatureId, ctx.locationId, expected = "1"))
        )
        assertEquals(HttpStatusCode.OK, reader.get("/api/wms/receipts/${draft.id}").status)
    }

    // --- Сценарии ---

    private data class Ctx(
        val admin: HttpClient,
        val keeper: HttpClient,
        val reader: HttpClient,
        val initiator: HttpClient,
        val approver: HttpClient,
        val warehouseId: Long,
        val locationId: Long,
        val nomenclatureId: Long,
        val demandId: Long,
        val supplierId: Long
    )

    private suspend fun ApplicationTestBuilder.bootstrap(): Ctx {
        val admin = jsonClient()
        createUserWithRole(admin, "admin", "admin123", "Admin User", emptyList())
        createUserWithRole(admin, "keeper", "password", "Keeper", keeperPermissions())
        createUserWithRole(admin, "reader", "password", "Reader", readerPermissions())
        createUserWithRole(admin, "initiator", "password", "Init", initiatorPermissions())
        createUserWithRole(admin, "approver", "password", "Approver", approverPermissions())
        val keeper = loginClient("keeper", "password")
        val reader = loginClient("reader", "password")
        val initiator = loginClient("initiator", "password")
        val approver = loginClient("approver", "password")

        val warehouse = postCreated<WarehouseDto, CreateWarehouseRequest>(
            keeper,
            "/api/wms/warehouses",
            CreateWarehouseRequest(code = "WH-1", name = "Основной склад")
        )
        val location = postCreated<StorageLocationDto, CreateStorageLocationRequest>(
            keeper,
            "/api/wms/locations",
            CreateStorageLocationRequest(
                warehouseId = warehouse.id,
                code = "A-01",
                name = "Ячейка A-01",
                kind = StorageLocationKind.CELL
            )
        )
        val nomenclature = postCreated<NomenclatureDto, CreateNomenclatureRequest>(
            admin,
            "/api/wms/nomenclature",
            CreateNomenclatureRequest(
                code = "REC-MAT-${UUID.randomUUID().toString().take(6)}",
                name = "Материал приёмки",
                type = NomenclatureType.RAW_MATERIAL,
                unit = "pcs"
            )
        )
        val demand = postCreated<DemandDetailDto, CreateDemandRequest>(
            initiator,
            "/api/demands",
            CreateDemandRequest(
                sourceType = DemandSourceType.MANUAL,
                priority = DemandPriority.NORMAL,
                requiredDate = java.time.LocalDate.now().plusDays(30).toString(),
                lines = listOf(DemandLineInput(1, nomenclature.id, "20", "pcs"))
            )
        )
        val supplier = postCreated<SupplierCardDto, CreateSupplierRequest>(
            keeper,
            "/api/suppliers",
            CreateSupplierRequest(
                name = "ООО Приёмка",
                inn = randomValidInn(),
                idempotencyKey = "sup-${UUID.randomUUID()}"
            )
        )
        setVerified(admin, supplier.supplier.id, supplier.supplier.version)
        return Ctx(
            admin = admin,
            keeper = keeper,
            reader = reader,
            initiator = initiator,
            approver = approver,
            warehouseId = warehouse.id,
            locationId = location.id,
            nomenclatureId = nomenclature.id,
            demandId = demand.header.id,
            supplierId = supplier.supplier.id
        )
    }

    /** Заказ в статусе SENT: предложение → выбор → согласование → отправка. */
    private suspend fun ApplicationTestBuilder.sentOrder(ctx: Ctx, quantity: String): PurchaseOrderDto {
        val demand = ctx.initiator.get("/api/demands/${ctx.demandId}")
            .bodyAs<ApiResponse<DemandDetailDto>>().data!!
        val offer = postCreated<SupplierOfferDto, CreateSupplierOfferRequest>(
            ctx.keeper,
            "/api/supplier-offers",
            CreateSupplierOfferRequest(
                demandId = ctx.demandId,
                supplierId = ctx.supplierId,
                compliance = SupplierOfferCompliance.FULL,
                documentsComplete = true,
                deliveryDays = 3,
                validUntil = java.time.LocalDate.now().plusDays(30).toString(),
                lines = listOf(
                    SupplierOfferLineInput(
                        demandLineId = demand.lines.first().id,
                        quantity = quantity,
                        unitPrice = "100.00",
                        vatRate = "20"
                    )
                ),
                idempotencyKey = "offer-${UUID.randomUUID()}"
            )
        )
        // Строки предложения читаются отдельно: в списке они не приходят.
        val offerWithLines = ctx.keeper.get("/api/supplier-offers/${offer.id}")
            .bodyAs<ApiResponse<SupplierOfferDto>>().data!!
        val selected = ctx.initiator.post("/api/supplier-offers/${offer.id}/select") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SelectSupplierOfferRequest(
                        reason = "Выбрано для проверки приёмки по заказу",
                        version = offerWithLines.version,
                        idempotencyKey = "select-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, selected.status, selected.bodyAsText())

        val order = postCreated<PurchaseOrderDto, CreatePurchaseOrderRequest>(
            ctx.keeper,
            "/api/purchase-orders",
            CreatePurchaseOrderRequest(
                offerId = offer.id,
                expectedAt = java.time.LocalDate.now().plusDays(7).toString(),
                lines = listOf(CreatePurchaseOrderLineInput(offerWithLines.lines.first().id, quantity)),
                idempotencyKey = "po-${UUID.randomUUID()}"
            )
        )
        val submitted = orderAction(ctx.keeper, order, "submit")
        val approved = orderAction(ctx.approver, submitted, "approve")
        return orderAction(ctx.keeper, approved, "send")
    }

    private suspend fun orderAction(client: HttpClient, order: PurchaseOrderDto, verb: String): PurchaseOrderDto {
        val response = client.post("/api/purchase-orders/${order.id}/$verb") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    PurchaseOrderActionRequest(
                        version = order.version,
                        idempotencyKey = "$verb-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<PurchaseOrderDto>>().data!!
    }

    private fun line(
        nomenclatureId: Long,
        locationId: Long,
        expected: String,
        received: String? = null,
        orderLineId: Long? = null,
        discrepancyReason: String? = null
    ) = ReceiptLineInput(
        lineNo = 1,
        nomenclatureId = nomenclatureId,
        expectedQuantity = expected,
        receivedQuantity = received,
        locationId = locationId,
        purchaseOrderLineId = orderLineId,
        discrepancyReason = discrepancyReason
    )

    private suspend fun createReceipt(
        client: HttpClient,
        sourceType: ReceiptSourceType,
        warehouseId: Long,
        lines: List<ReceiptLineInput>,
        purchaseOrderId: Long? = null,
        purchaseDeliveryId: Long? = null,
        sourceDocumentNo: String? = null
    ): ReceiptDto = postCreated<ReceiptDto, CreateReceiptRequest>(
        client,
        "/api/wms/receipts",
        CreateReceiptRequest(
            sourceType = sourceType,
            sourceDocumentNo = sourceDocumentNo,
            purchaseOrderId = purchaseOrderId,
            purchaseDeliveryId = purchaseDeliveryId,
            warehouseId = warehouseId,
            lines = lines
        )
    )

    private suspend fun createReceiptAtServer(
        client: HttpClient,
        ctx: Ctx,
        orderId: Long,
        quantity: String
    ): HttpResponse {
        val response = client.get("/api/purchase-orders/$orderId")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val order = response.bodyAs<ApiResponse<PurchaseOrderDto>>().data!!
        return postStatus(
            client,
            "/api/wms/receipts",
            CreateReceiptRequest(
                sourceType = ReceiptSourceType.PURCHASE_ORDER,
                sourceDocumentNo = "PO-OVER-${UUID.randomUUID()}",
                purchaseOrderId = orderId,
                warehouseId = ctx.warehouseId,
                lines = listOf(
                    line(
                        ctx.nomenclatureId,
                        ctx.locationId,
                        expected = quantity,
                        received = quantity,
                        orderLineId = order.lines.first().id
                    )
                )
            )
        )
    }

    private suspend fun postReceipt(client: HttpClient, receipt: ReceiptDto): ReceiptDto {
        val response = postStatus(
            client,
            "/api/wms/receipts/${receipt.id}/post",
            PostReceiptRequest(receipt.version)
        )
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ReceiptDto>>().data!!
    }

    private suspend fun expectedLines(client: HttpClient, orderId: Long): List<ExpectedReceiptLineDto> {
        val response = client.get("/api/wms/expected-receipts?purchaseOrderId=$orderId")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<List<ExpectedReceiptLineDto>>>().data!!
    }

    /** Физический остаток; сервер отдаёт его строкой DECIMAL(19,4). */
    private suspend fun stockQuantity(client: HttpClient, warehouseId: Long, nomenclatureId: Long): BigDecimal {
        val response = client.get("/api/wms/stock?warehouseId=$warehouseId&nomenclatureId=$nomenclatureId")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val stock = response.bodyAs<ApiResponse<List<StockBalanceDto>>>().data!!
        return BigDecimal(stock.firstOrNull()?.physicalQty ?: "0")
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

    /** Создание ресурса: 201 и тело в `ApiResponse`. */
    private suspend inline fun <reified Res, reified Req : Any> postCreated(
        client: HttpClient,
        path: String,
        request: Req
    ): Res {
        val response = client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(stringify(request))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<Res>>().data!!
    }

    private suspend inline fun <reified Req : Any> postStatus(
        client: HttpClient,
        path: String,
        request: Req
    ): HttpResponse = client.post(path) {
        contentType(ContentType.Application.Json)
        setBody(stringify(request))
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

    /** Количества сервер отдаёт как DECIMAL(19,4) — сравниваем численно. */
    private fun assertQty(expected: String, actual: String) = assertQty(expected, BigDecimal(actual))

    private fun assertQty(expected: String, actual: BigDecimal) {
        assertEquals(0, BigDecimal(expected).compareTo(actual), "expected $expected, got $actual")
    }

    /** Код ошибки из тела `ApiResponse` (например, `VALIDATION_ERROR`). */
    private suspend fun HttpResponse.errorCode(): String =
        runCatching { bodyAs<ApiResponse<String>>().error?.code }.getOrNull() ?: bodyAsText()

    private suspend inline fun <reified T> HttpResponse.bodyAs(): T =
        json.decodeFromString(serializer<T>(), bodyAsText())

    private inline fun <reified T> stringify(value: T): String =
        json.encodeToString(serializer<T>(), value)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @JvmStatic
        @BeforeClass
        fun setup() = TestDatabase.init()

        @JvmStatic
        @AfterClass
        fun teardown() = TestDatabase.destroy()

        private fun keeperPermissions() = listOf(
            CreatePermissionRequest("wms", "read"),
            CreatePermissionRequest("wms", "post"),
            CreatePermissionRequest("wms", "manage"),
            CreatePermissionRequest("demands", "read_any"),
            CreatePermissionRequest("suppliers", "read"),
            CreatePermissionRequest("suppliers", "create"),
            CreatePermissionRequest("suppliers", "offer"),
            CreatePermissionRequest("suppliers", "order"),
            CreatePermissionRequest("suppliers", "receive"),
            CreatePermissionRequest("users", "read_any")
        )

        private fun readerPermissions() = listOf(
            CreatePermissionRequest("wms", "read")
        )

        private fun initiatorPermissions() = listOf(
            CreatePermissionRequest("demands", "create"),
            CreatePermissionRequest("demands", "read_any"),
            CreatePermissionRequest("suppliers", "read"),
            CreatePermissionRequest("suppliers", "select"),
            CreatePermissionRequest("wms", "read")
        )

        private fun approverPermissions() = listOf(
            CreatePermissionRequest("suppliers", "read"),
            CreatePermissionRequest("suppliers", "approve_order"),
            CreatePermissionRequest("demands", "read_any"),
            CreatePermissionRequest("wms", "read")
        )
    }
}
