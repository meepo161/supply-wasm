package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
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
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Сквозной тест решения D-64 (SUP-UX-001):
 * Ведущий создаёт потребность (сразу APPROVED) → снабжение загружает 2 предложения с
 * обязательными файлами счетов → ведущий получает уведомления, скачивает счёт и выбирает
 * предложение в 1 клик → снабжение отмечает оплату → склад видит оплаченный заказ в
 * ожидаемых приходах и принимает его в 1 клик → заказ DELIVERED, потребность COVERED,
 * остаток увеличен, а ведущий и снабженец получили уведомления DEMAND_RECEIPT_POSTED.
 */
class DemandStreamlinedFlowRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `full 6-step streamlined demand to warehouse receipt flow`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "admin", "admin123", "Admin User", emptyList())
        val lead = createUserWithRole(admin, "lead", "password", "Ведущий Иванов", leadPermissions())
        val buyer = createUserWithRole(admin, "buyer", "password", "Снабженец Петрова", buyerPermissions())
        val keeper = createUserWithRole(admin, "keeper", "password", "Кладовщик Сидоров", keeperPermissions())

        val leadClient = loginClient("lead", "password")
        val buyerClient = loginClient("buyer", "password")
        val keeperClient = loginClient("keeper", "password")

        // Подготовка склада, ячейки и номенклатуры
        val warehouse = postCreated<WarehouseDto, CreateWarehouseRequest>(
            keeperClient,
            "/api/wms/warehouses",
            CreateWarehouseRequest(code = "WH-D64", name = "Склад D-64")
        )
        val location = postCreated<StorageLocationDto, CreateStorageLocationRequest>(
            keeperClient,
            "/api/wms/locations",
            CreateStorageLocationRequest(
                warehouseId = warehouse.id,
                code = "D64-A1",
                name = "Ячейка D64-A1",
                kind = StorageLocationKind.CELL
            )
        )
        val material = postCreated<NomenclatureDto, CreateNomenclatureRequest>(
            admin,
            "/api/wms/nomenclature",
            CreateNomenclatureRequest(
                code = "D64-BOLT",
                name = "Болт титановый М10",
                type = NomenclatureType.PURCHASED_COMPONENT,
                unit = "шт"
            )
        )

        // Шаг 1. Ведущий создаёт потребность -> статус сразу APPROVED
        val demand = postCreated<DemandDetailDto, CreateDemandRequest>(
            leadClient,
            "/api/demands",
            CreateDemandRequest(
                sourceType = DemandSourceType.MANUAL,
                priority = DemandPriority.HIGH,
                lines = listOf(DemandLineInput(1, material.id, "12", "шт"))
            )
        )
        assertEquals(DemandStatus.APPROVED, demand.header.status)
        assertEquals(lead.id, demand.header.approvedBy)
        assertNotNull(demand.header.approvedAt)
        val demandLineId = demand.lines.single().id

        // Создаём двух поставщиков (неверифицированных — по D-64 это не мешает выбору)
        val supplier1 = postCreated<SupplierCardDto, CreateSupplierRequest>(
            buyerClient,
            "/api/suppliers",
            CreateSupplierRequest(name = "ООО Поставщик-1", inn = "7701234560", idempotencyKey = "sup1-${UUID.randomUUID()}")
        )
        val supplier2 = postCreated<SupplierCardDto, CreateSupplierRequest>(
            buyerClient,
            "/api/suppliers",
            CreateSupplierRequest(name = "ООО Поставщик-2", inn = "7709876545", idempotencyKey = "sup2-${UUID.randomUUID()}")
        )

        // Шаг 2. Попытка создать предложение БЕЗ invoiceDocumentId -> 400 BadRequest
        val missingInvoiceResp = buyerClient.post("/api/supplier-offers") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateSupplierOfferRequest(
                        demandId = demand.header.id,
                        supplierId = supplier1.supplier.id,
                        lines = listOf(SupplierOfferLineInput(demandLineId, "12", "150.00", "20")),
                        idempotencyKey = "no-inv-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, missingInvoiceResp.status, missingInvoiceResp.bodyAsText())
        assertTrue(missingInvoiceResp.bodyAsText().contains("invoiceDocumentId"))

        // Шаг 3. Снабжение загружает 2 файла счёта и создаёт 2 предложения
        val pdfBytes1 = "%PDF-1.4\nInvoice-1-content".toByteArray(Charsets.UTF_8)
        val pdfBytes2 = "%PDF-1.4\nInvoice-2-content".toByteArray(Charsets.UTF_8)
        val doc1Id = uploadInvoiceDocument(buyerClient, "Счёт № 101", "inv-101.pdf", pdfBytes1)
        val doc2Id = uploadInvoiceDocument(buyerClient, "Счёт № 102", "inv-102.pdf", pdfBytes2)

        val offer1 = postCreated<SupplierOfferDto, CreateSupplierOfferRequest>(
            buyerClient,
            "/api/supplier-offers",
            CreateSupplierOfferRequest(
                demandId = demand.header.id,
                supplierId = supplier1.supplier.id,
                deliveryDays = 5,
                lines = listOf(SupplierOfferLineInput(demandLineId, "12", "150.00", "20")),
                idempotencyKey = "offer1-${UUID.randomUUID()}",
                invoiceDocumentId = doc1Id,
                invoiceNumber = "СЧ-101",
                invoiceDate = java.time.LocalDate.now().toString()
            )
        )
        assertEquals(doc1Id, offer1.invoiceDocumentId)
        assertEquals("inv-101.pdf", offer1.invoiceFileName)

        val offer2 = postCreated<SupplierOfferDto, CreateSupplierOfferRequest>(
            buyerClient,
            "/api/supplier-offers",
            CreateSupplierOfferRequest(
                demandId = demand.header.id,
                supplierId = supplier2.supplier.id,
                deliveryDays = 3,
                lines = listOf(SupplierOfferLineInput(demandLineId, "12", "140.00", "20")),
                idempotencyKey = "offer2-${UUID.randomUUID()}",
                invoiceDocumentId = doc2Id,
                invoiceNumber = "СЧ-102",
                invoiceDate = java.time.LocalDate.now().toString()
            )
        )
        assertEquals(doc2Id, offer2.invoiceDocumentId)

        // Ведущий получил уведомления о новых предложениях
        val leadNotificationsAfterOffers = getNotifications(leadClient)
        assertEquals(2, leadNotificationsAfterOffers.count { it.type == "DEMAND_OFFER_CREATED" && it.entityId == demand.header.id })

        // Ведущий может открыть карточку потребности с предложениями и скачать файл счёта
        val detailAfterOffers = leadClient.get("/api/demands/${demand.header.id}")
            .bodyAs<ApiResponse<DemandDetailDto>>().data!!
        assertEquals(2, detailAfterOffers.offers.size)
        assertEquals("0", detailAfterOffers.stockByNomenclature[material.id])

        val downloadedFileResp = leadClient.get("/api/suppliers/offers/${offer2.id}/invoice-file")
        assertEquals(HttpStatusCode.OK, downloadedFileResp.status)
        assertContentEquals(pdfBytes2, downloadedFileResp.bodyAsBytes())

        // Шаг 4. Ведущий выбирает предложение #2 в 1 клик (с дефолтным reason)
        val selectResp = leadClient.post("/api/supplier-offers/${offer2.id}/select") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SelectSupplierOfferRequest(
                        version = offer2.version,
                        idempotencyKey = "sel-${UUID.randomUUID()}"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, selectResp.status, selectResp.bodyAsText())
        val selectedOffer = selectResp.bodyAs<ApiResponse<SupplierOfferDto>>().data!!
        assertEquals(SupplierOfferStatus.SELECTED, selectedOffer.status)

        // Снабжение получило уведомление о выборе предложения
        val buyerNotificationsAfterSelect = getNotifications(buyerClient)
        assertTrue(
            buyerNotificationsAfterSelect.any { it.type == "DEMAND_OFFER_SELECTED" && it.entityId == demand.header.id },
            "Buyer should receive DEMAND_OFFER_SELECTED: $buyerNotificationsAfterSelect"
        )

        // Шаг 5. Снабжение отмечает заявку оплаченной
        val markPaidResp = buyerClient.post("/api/demands/${demand.header.id}/mark-paid") {
            contentType(ContentType.Application.Json)
            setBody(stringify(MarkDemandPaidRequest(paymentNote = "Оплачено по п/п 42")))
        }
        assertEquals(HttpStatusCode.OK, markPaidResp.status, markPaidResp.bodyAsText())
        val paidDetail = markPaidResp.bodyAs<ApiResponse<DemandDetailDto>>().data!!
        assertTrue(paidDetail.header.isPaid)
        assertNotNull(paidDetail.header.paidAt)
        assertEquals("ООО Поставщик-2", paidDetail.header.selectedSupplierName)
        assertEquals(1, paidDetail.orders.size)
        val createdOrder = paidDetail.orders.single()
        assertEquals(PurchaseOrderStatus.SENT, createdOrder.status)
        assertNotNull(createdOrder.paidAt)
        assertEquals(buyer.id, createdOrder.paidBy)
        assertEquals(doc2Id, createdOrder.invoiceDocumentId)

        // Проверка в списке потребностей
        val demandList = leadClient.get("/api/demands").bodyAs<ApiResponse<List<DemandHeaderDto>>>().data!!
        val listedHeader = demandList.single { it.id == demand.header.id }
        assertTrue(listedHeader.isPaid)
        assertEquals("Ведущий Иванов", listedHeader.initiatorName)
        assertEquals("ООО Поставщик-2", listedHeader.selectedSupplierName)

        // Склад видит оплаченный заказ в GET /api/wms/receipts/expected
        val expectedResp = keeperClient.get("/api/wms/receipts/expected")
        assertEquals(HttpStatusCode.OK, expectedResp.status, expectedResp.bodyAsText())
        val expectedLines = expectedResp.bodyAs<ApiResponse<List<ExpectedReceiptLineDto>>>().data!!
        val expectedForOrder = expectedLines.single { it.orderId == createdOrder.id }
        assertTrue(expectedForOrder.isPaid)
        assertNotNull(expectedForOrder.paidAt)
        assertEquals("ООО Поставщик-2", expectedForOrder.supplierName)
        assertEquals(listOf(demand.header.number), expectedForOrder.demandNumbers)
        assertEquals(offer2.id, expectedForOrder.offerId)
        assertEquals(doc2Id, expectedForOrder.invoiceDocumentId)
        assertEquals("шт", expectedForOrder.unit)

        // Шаг 6. Кладовщик принимает заказ в 1 клик
        val quickReceiveResp = keeperClient.post("/api/wms/receipts/quick-receive-order") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    QuickReceiveOrderRequest(
                        orderId = createdOrder.id,
                        warehouseId = warehouse.id,
                        locationId = location.id,
                        note = "Принято полностью"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, quickReceiveResp.status, quickReceiveResp.bodyAsText())
        val postedReceipt = quickReceiveResp.bodyAs<ApiResponse<ReceiptDto>>().data!!
        assertEquals(ReceiptStatus.POSTED, postedReceipt.status)

        // Остаток на складе увеличился на 12
        val stockResp = keeperClient.get("/api/wms/stock?warehouseId=${warehouse.id}&nomenclatureId=${material.id}")
        val stockRows = stockResp.bodyAs<ApiResponse<List<StockBalanceDto>>>().data!!
        assertEquals(0, BigDecimal(stockRows.single().physicalQty).compareTo(BigDecimal("12")))

        // Заказ стал DELIVERED
        val orderAfter = buyerClient.get("/api/purchase-orders/${createdOrder.id}")
            .bodyAs<ApiResponse<PurchaseOrderDto>>().data!!
        assertEquals(PurchaseOrderStatus.DELIVERED, orderAfter.status)

        // Потребность стала COVERED, coveredQuantity == quantity, в карточке видна приёмка и остаток
        val demandFinal = leadClient.get("/api/demands/${demand.header.id}")
            .bodyAs<ApiResponse<DemandDetailDto>>().data!!
        assertEquals(DemandStatus.COVERED, demandFinal.header.status)
        assertEquals(DemandLineStatus.COVERED, demandFinal.lines.single().status)
        assertEquals(0, BigDecimal(demandFinal.lines.single().coveredQuantity).compareTo(BigDecimal("12")))
        assertEquals(1, demandFinal.receipts.size)
        assertEquals(postedReceipt.id, demandFinal.receipts.single().id)
        assertEquals("12", demandFinal.stockByNomenclature[material.id])

        // И ведущий, И снабженец получили уведомление DEMAND_RECEIPT_POSTED
        val leadFinalNotifications = getNotifications(leadClient)
        assertTrue(
            leadFinalNotifications.any { it.type == "DEMAND_RECEIPT_POSTED" && it.entityId == demand.header.id },
            "Lead should receive DEMAND_RECEIPT_POSTED: $leadFinalNotifications"
        )
        val buyerFinalNotifications = getNotifications(buyerClient)
        assertTrue(
            buyerFinalNotifications.any { it.type == "DEMAND_RECEIPT_POSTED" && it.entityId == demand.header.id },
            "Buyer should receive DEMAND_RECEIPT_POSTED: $buyerFinalNotifications"
        )
    }

    @Test
    fun `mark purchase order paid directly transitions draft order to sent and sets paid fields`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "admin", "admin123", "Admin User", emptyList())
        createUserWithRole(admin, "lead", "password", "Ведущий", leadPermissions())
        createUserWithRole(admin, "buyer", "password", "Снабженец", buyerPermissions())
        val leadClient = loginClient("lead", "password")
        val buyerClient = loginClient("buyer", "password")

        val material = postCreated<NomenclatureDto, CreateNomenclatureRequest>(
            admin,
            "/api/wms/nomenclature",
            CreateNomenclatureRequest(code = "D64-M2", name = "Гайка М10", type = NomenclatureType.MATERIAL, unit = "шт")
        )
        val demand = postCreated<DemandDetailDto, CreateDemandRequest>(
            leadClient,
            "/api/demands",
            CreateDemandRequest(
                sourceType = DemandSourceType.MANUAL,
                lines = listOf(DemandLineInput(1, material.id, "5", "шт"))
            )
        )
        val supplier = postCreated<SupplierCardDto, CreateSupplierRequest>(
            buyerClient,
            "/api/suppliers",
            CreateSupplierRequest(name = "ООО Прямой", inn = "7707083893", idempotencyKey = "sup-${UUID.randomUUID()}")
        )
        val docId = uploadInvoiceDocument(buyerClient, "Счёт", "inv.pdf", "%PDF-1.4".toByteArray())
        val offer = postCreated<SupplierOfferDto, CreateSupplierOfferRequest>(
            buyerClient,
            "/api/supplier-offers",
            CreateSupplierOfferRequest(
                demandId = demand.header.id,
                supplierId = supplier.supplier.id,
                lines = listOf(SupplierOfferLineInput(demand.lines.single().id, "5", "50.00", "20")),
                idempotencyKey = "off-${UUID.randomUUID()}",
                invoiceDocumentId = docId
            )
        )
        val selectResp = leadClient.post("/api/supplier-offers/${offer.id}/select") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SelectSupplierOfferRequest(version = offer.version, idempotencyKey = "sel-${UUID.randomUUID()}")))
        }
        assertEquals(HttpStatusCode.OK, selectResp.status, selectResp.bodyAsText())

        val po = postCreated<PurchaseOrderDto, CreatePurchaseOrderRequest>(
            buyerClient,
            "/api/purchase-orders",
            CreatePurchaseOrderRequest(offerId = offer.id, idempotencyKey = "po-${UUID.randomUUID()}")
        )
        assertEquals(PurchaseOrderStatus.DRAFT, po.status)

        val paidResp = buyerClient.post("/api/purchase-orders/${po.id}/mark-paid")
        assertEquals(HttpStatusCode.OK, paidResp.status, paidResp.bodyAsText())
        val paidPo = paidResp.bodyAs<ApiResponse<PurchaseOrderDto>>().data!!
        assertEquals(PurchaseOrderStatus.SENT, paidPo.status)
        assertNotNull(paidPo.paidAt)
        assertEquals("Снабженец", paidPo.paidByName)
    }

    // --- Helpers ---

    private suspend fun getNotifications(client: HttpClient): List<NotificationDto> {
        val resp = client.get("/api/notifications?limit=100")
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.bodyAs<ApiResponse<NotificationPageDto>>().data!!.notifications
    }

    private suspend fun uploadInvoiceDocument(
        client: HttpClient,
        name: String,
        fileName: String,
        content: ByteArray
    ): Long {
        val doc = postCreated<DocumentDto, CreateDocumentRequest>(
            client,
            "/api/documents",
            CreateDocumentRequest(name = name, docType = DocumentType.INVOICE)
        )
        val verResp = client.submitFormWithBinaryData(
            url = "/api/documents/${doc.id}/versions",
            formData = formData {
                append("file", content, Headers.build {
                    append(HttpHeaders.ContentType, "application/pdf")
                    append(HttpHeaders.ContentDisposition, "filename=\"$fileName\"")
                })
            }
        )
        assertEquals(HttpStatusCode.Created, verResp.status, verResp.bodyAsText())
        return doc.id
    }

    private suspend inline fun <reified Res, reified Req : Any> postCreated(
        client: HttpClient,
        path: String,
        request: Req
    ): Res {
        val resp = client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(stringify(request))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.bodyAs<ApiResponse<Res>>().data!!
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
        val roleResp = adminClient.post("/api/roles") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateRoleRequest(login.uppercase(), fullName, permissions = permissions)))
        }
        assertEquals(HttpStatusCode.Created, roleResp.status, roleResp.bodyAsText())
        val role = roleResp.bodyAs<ApiResponse<RoleDto>>().data!!
        val userResp = adminClient.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUserRequest(login, password, fullName, roleIds = listOf(role.id))))
        }
        assertEquals(HttpStatusCode.Created, userResp.status, userResp.bodyAsText())
        return userResp.bodyAs<ApiResponse<UserDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.loginClient(login: String, password: String): HttpClient {
        val client = jsonClient()
        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest(login, password)))
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
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

        @JvmStatic
        @BeforeClass
        fun setup() = TestDatabase.init()

        @JvmStatic
        @AfterClass
        fun teardown() = TestDatabase.destroy()

        private fun leadPermissions() = listOf(
            CreatePermissionRequest("demands", "create"),
            CreatePermissionRequest("demands", "update"),
            CreatePermissionRequest("suppliers", "select")
        )

        private fun buyerPermissions() = listOf(
            CreatePermissionRequest("suppliers", "read"),
            CreatePermissionRequest("suppliers", "create"),
            CreatePermissionRequest("suppliers", "update"),
            CreatePermissionRequest("suppliers", "offer"),
            CreatePermissionRequest("suppliers", "order"),
            CreatePermissionRequest("demands", "read_any"),
            CreatePermissionRequest("wms", "read")
        )

        private fun keeperPermissions() = listOf(
            CreatePermissionRequest("wms", "read"),
            CreatePermissionRequest("wms", "post"),
            CreatePermissionRequest("wms", "manage")
        )
    }
}
