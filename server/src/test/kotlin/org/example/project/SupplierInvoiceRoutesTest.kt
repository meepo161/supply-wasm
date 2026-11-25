package org.example.project

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import org.example.project.dto.*
import org.example.project.smoke.Actor
import org.example.project.smoke.Company
import org.example.project.smoke.company
import org.example.project.smoke.data
import org.example.project.smoke.encode
import org.example.project.smoke.getOk
import org.example.project.smoke.newKey
import org.example.project.smoke.perms
import org.example.project.smoke.postJson
import org.example.project.smoke.postOk
import org.example.project.smoke.putJson
import org.example.project.smoke.smokeApp
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Счёт поставщика (D-61, D-62): снабжение загружает счёт (файл обязателен),
 * сверяет с заказом и оформляет поступление; склад принимает и проводит.
 * Пример данных — счёт ОАО «СЗТТ» на трансформаторы ТОП-0,66.
 */
class SupplierInvoiceRoutesTest {

    private class Ctx(
        val company: Company,
        val buyer: Actor,
        val keeper: Actor,
        val supplierId: Long,
        val supplierInn: String,
        val order: PurchaseOrderDto,
        val nomenclatureId: Long,
        val warehouseId: Long,
        val locationId: Long
    )

    @Test
    fun `procurement invoice flows into a warehouse receipt that the storekeeper accepts and posts`() = smokeApp {
        val ctx = setup()
        val documentId = uploadInvoiceFile(ctx.buyer.client)
        val invoice = ctx.buyer.client.postOk<SupplierInvoiceInput, SupplierInvoiceDto>(
            "/api/supplier-invoices", invoiceInput(ctx, documentId), HttpStatusCode.Created
        )
        assertEquals(SupplierInvoiceStatus.DRAFT, invoice.status)
        assertEquals("invoice-219658.pdf", invoice.fileName)
        assertEquals(ctx.order.lines.first().id, invoice.lines.first().purchaseOrderLineId)

        // Сверка с заказом: цена, количество, ИНН и суммы совпадают
        val check = ctx.buyer.client.getOk<SupplierInvoiceCheckDto>("/api/supplier-invoices/${invoice.id}/check")
        assertTrue(check.canConfirm, check.issues.toString())
        assertTrue(check.issues.none { it.severity != InvoiceIssueSeverity.WARNING }, check.issues.toString())

        val checked = ctx.buyer.client.postOk<ConfirmSupplierInvoiceCheckRequest, SupplierInvoiceDto>(
            "/api/supplier-invoices/${invoice.id}/confirm-check", ConfirmSupplierInvoiceCheckRequest(invoice.version)
        )
        assertEquals(SupplierInvoiceStatus.CHECKED, checked.status)

        // Склад не оформляет поступление по счёту сам: ни из счёта, ни вручную
        val keeperFromInvoice = ctx.keeper.client.postJson(
            "/api/supplier-invoices/${invoice.id}/receipt", encode(CreateReceiptFromInvoiceRequest(ctx.warehouseId, checked.version))
        )
        assertEquals(HttpStatusCode.Forbidden, keeperFromInvoice.status)
        val manual = ctx.keeper.client.postJson(
            "/api/wms/receipts",
            encode(
                CreateReceiptRequest(
                    sourceType = ReceiptSourceType.INVOICE, sourceDocumentNo = "219658", purchaseOrderId = ctx.order.id,
                    warehouseId = ctx.warehouseId,
                    lines = listOf(ReceiptLineInput(lineNo = 1, nomenclatureId = ctx.nomenclatureId, expectedQuantity = "3", locationId = ctx.locationId))
                )
            )
        )
        assertEquals(HttpStatusCode.BadRequest, manual.status, manual.bodyAsText())

        // Снабжение оформляет поступление; склад видит черновик без ячеек
        val receipt = ctx.buyer.client.postOk<CreateReceiptFromInvoiceRequest, ReceiptDto>(
            "/api/supplier-invoices/${invoice.id}/receipt", CreateReceiptFromInvoiceRequest(ctx.warehouseId, checked.version), HttpStatusCode.Created
        )
        assertEquals(ReceiptSourceType.INVOICE, receipt.sourceType)
        assertEquals(invoice.id, receipt.supplierInvoiceId)
        assertEquals("219658", receipt.sourceDocumentNo)
        assertEquals(ReceiptStatus.DRAFT, receipt.status)
        assertNull(receipt.lines.first().locationId)

        // Повторно оформить поступление по тому же счёту нельзя
        val again = ctx.buyer.client.postJson(
            "/api/supplier-invoices/${invoice.id}/receipt",
            encode(CreateReceiptFromInvoiceRequest(ctx.warehouseId, checked.version + 1))
        )
        assertEquals(HttpStatusCode.Conflict, again.status, again.bodyAsText())

        // Проведение без ячейки запрещено
        val noCell = ctx.keeper.client.postJson("/api/wms/receipts/${receipt.id}/post", encode(PostReceiptRequest(receipt.version)))
        assertEquals(HttpStatusCode.BadRequest, noCell.status, noCell.bodyAsText())

        // Привезли 2 из 3: без причины расхождения не принимается
        val lineId = receipt.lines.first().id
        val noReason = ctx.keeper.client.putJson(
            "/api/wms/receipts/${receipt.id}/acceptance",
            encode(AcceptReceiptLinesRequest(receipt.version, listOf(AcceptReceiptLineInput(lineId, "2", ctx.locationId))))
        )
        assertEquals(HttpStatusCode.BadRequest, noReason.status, noReason.bodyAsText())
        val accepted = ctx.keeper.client.putOkJson<AcceptReceiptLinesRequest, ReceiptDto>(
            "/api/wms/receipts/${receipt.id}/acceptance",
            AcceptReceiptLinesRequest(receipt.version, listOf(AcceptReceiptLineInput(lineId, "2", ctx.locationId, discrepancyReason = "Третий трансформатор в следующей отгрузке")))
        )
        assertEquals(ReceiptStatus.IN_PROGRESS, accepted.status)
        assertEquals(ReceiptDiscrepancyKind.SHORTAGE, accepted.lines.first().discrepancyKind)
        assertEquals(ctx.locationId, accepted.lines.first().locationId)

        val posted = ctx.keeper.client.postOk<PostReceiptRequest, ReceiptDto>("/api/wms/receipts/${receipt.id}/post", PostReceiptRequest(accepted.version))
        assertEquals(ReceiptStatus.POSTED, posted.status)
        val stock = ctx.keeper.client.getOk<List<StockBalanceDto>>("/api/wms/stock?warehouseId=${ctx.warehouseId}&nomenclatureId=${ctx.nomenclatureId}")
        assertEquals(0, BigDecimal(stock.first().physicalQty).compareTo(BigDecimal("2")))

        // Счёт показывает проведённое поступление
        val afterPost = ctx.buyer.client.getOk<SupplierInvoiceDto>("/api/supplier-invoices/${invoice.id}")
        assertEquals(receipt.number, afterPost.receiptNumber)
        assertEquals(ReceiptStatus.POSTED, afterPost.receiptStatus)

        // Файл счёта открывают снабжение и склад, принимающий по счёту; посторонний — нет
        val keeperFile = ctx.keeper.client.get("/api/supplier-invoices/${invoice.id}/file")
        assertEquals(HttpStatusCode.OK, keeperFile.status)
        assertEquals("%PDF-1.4 счёт 219658", String(keeperFile.readRawBytes(), Charsets.UTF_8))
        val outsider = ctx.company.hire("outsider", "Посторонний", emptyList())
        assertEquals(HttpStatusCode.Forbidden, outsider.client.get("/api/supplier-invoices/${invoice.id}/file").status)
        assertEquals(HttpStatusCode.Forbidden, outsider.client.get("/api/supplier-invoices").status)

        // История счёта
        val history = ctx.buyer.client.getOk<List<SupplierInvoiceHistoryDto>>("/api/supplier-invoices/${invoice.id}/history")
        assertEquals(listOf("CREATED", "CHECKED", "RECEIPT_CREATED"), history.map { it.action })

        // Отменить счёт с оформленным поступлением нельзя
        val cancel = ctx.buyer.client.postJson(
            "/api/supplier-invoices/${invoice.id}/cancel", encode(CancelSupplierInvoiceRequest(afterPost.version, "Ошибка"))
        )
        assertEquals(HttpStatusCode.Conflict, cancel.status)
    }

    @Test
    fun `invoice cannot be registered without an uploaded invoice file`() = smokeApp {
        val ctx = setup()
        val emptyDoc = ctx.buyer.client.postOk<CreateDocumentRequest, DocumentDto>(
            "/api/documents", CreateDocumentRequest("Счёт без файла", DocumentType.INVOICE), HttpStatusCode.Created
        )
        val noFile = ctx.buyer.client.postJson("/api/supplier-invoices", encode(invoiceInput(ctx, emptyDoc.id)))
        assertEquals(HttpStatusCode.BadRequest, noFile.status)
        assertTrue(noFile.bodyAsText().contains("no_file"))

        val contract = ctx.buyer.client.postOk<CreateDocumentRequest, DocumentDto>(
            "/api/documents", CreateDocumentRequest("Договор", DocumentType.CONTRACT), HttpStatusCode.Created
        )
        uploadVersion(ctx.buyer.client, contract.id)
        val wrongType = ctx.buyer.client.postJson("/api/supplier-invoices", encode(invoiceInput(ctx, contract.id)))
        assertEquals(HttpStatusCode.BadRequest, wrongType.status)
        assertTrue(wrongType.bodyAsText().contains("not_invoice"))

        val missing = ctx.buyer.client.postJson("/api/supplier-invoices", encode(invoiceInput(ctx, 987654)))
        assertEquals(HttpStatusCode.BadRequest, missing.status)
    }

    @Test
    fun `check blocks wrong inn excess quantity and expiry and requires a reason for price changes`() = smokeApp {
        val ctx = setup()
        val documentId = uploadInvoiceFile(ctx.buyer.client)
        val bad = invoiceInput(ctx, documentId, quantity = "5", price = "2300.00").copy(supplierInn = "6658017999")
        val invoice = ctx.buyer.client.postOk<SupplierInvoiceInput, SupplierInvoiceDto>("/api/supplier-invoices", bad, HttpStatusCode.Created)

        val check = ctx.buyer.client.getOk<SupplierInvoiceCheckDto>("/api/supplier-invoices/${invoice.id}/check")
        val codes = check.issues.associate { it.code to it.severity }
        assertEquals(InvoiceIssueSeverity.ERROR, codes["SUPPLIER_INN_MISMATCH"])
        assertEquals(InvoiceIssueSeverity.ERROR, codes["QUANTITY_EXCEEDS_ORDER"])
        assertEquals(InvoiceIssueSeverity.REQUIRES_REASON, codes["PRICE_MISMATCH"])
        assertEquals(InvoiceIssueSeverity.REQUIRES_REASON, codes["INVOICES_EXCEED_ORDER"])
        assertEquals(false, check.canConfirm)
        val blocked = ctx.buyer.client.postJson(
            "/api/supplier-invoices/${invoice.id}/confirm-check", encode(ConfirmSupplierInvoiceCheckRequest(invoice.version, "Согласовано"))
        )
        assertEquals(HttpStatusCode.Conflict, blocked.status)

        // Исправили количество и ИНН, цена осталась выше заказа
        val fixed = ctx.buyer.client.putOkJson<UpdateSupplierInvoiceRequest, SupplierInvoiceDto>(
            "/api/supplier-invoices/${invoice.id}",
            UpdateSupplierInvoiceRequest(invoiceInput(ctx, documentId, price = "2300.00"), invoice.version)
        )
        // Устаревшая версия — конфликт, а не тихая перезапись
        val stale = ctx.buyer.client.putJson(
            "/api/supplier-invoices/${invoice.id}", encode(UpdateSupplierInvoiceRequest(invoiceInput(ctx, documentId), invoice.version))
        )
        assertEquals(HttpStatusCode.Conflict, stale.status)

        val noReason = ctx.buyer.client.postJson(
            "/api/supplier-invoices/${invoice.id}/confirm-check", encode(ConfirmSupplierInvoiceCheckRequest(fixed.version))
        )
        assertEquals(HttpStatusCode.BadRequest, noReason.status)
        val withReason = ctx.buyer.client.postOk<ConfirmSupplierInvoiceCheckRequest, SupplierInvoiceDto>(
            "/api/supplier-invoices/${invoice.id}/confirm-check",
            ConfirmSupplierInvoiceCheckRequest(fixed.version, "Поставщик поднял цену, согласовано с нач. снабжения")
        )
        assertEquals(SupplierInvoiceStatus.CHECKED, withReason.status)
        assertEquals("Поставщик поднял цену, согласовано с нач. снабжения", withReason.acceptedDiscrepancyReason)

        // Вернуть в черновик можно, пока нет поступления
        val reopened = ctx.buyer.client.postOk<SupplierInvoiceActionRequest, SupplierInvoiceDto>(
            "/api/supplier-invoices/${invoice.id}/reopen", SupplierInvoiceActionRequest(withReason.version)
        )
        assertEquals(SupplierInvoiceStatus.DRAFT, reopened.status)

        // Истёкший счёт подтвердить нельзя
        val expired = ctx.buyer.client.putOkJson<UpdateSupplierInvoiceRequest, SupplierInvoiceDto>(
            "/api/supplier-invoices/${invoice.id}",
            UpdateSupplierInvoiceRequest(
                invoiceInput(ctx, documentId).copy(invoiceDate = LocalDate.now().minusDays(20).toString(), validUntil = LocalDate.now().minusDays(1).toString()),
                reopened.version
            )
        )
        assertTrue(expired.expired)
        val expiredCheck = ctx.buyer.client.getOk<SupplierInvoiceCheckDto>("/api/supplier-invoices/${invoice.id}/check")
        assertTrue(expiredCheck.issues.any { it.code == "EXPIRED" && it.severity == InvoiceIssueSeverity.ERROR })

        // Отмена только с причиной
        val blank = ctx.buyer.client.postJson("/api/supplier-invoices/${invoice.id}/cancel", encode(CancelSupplierInvoiceRequest(expired.version, " ")))
        assertEquals(HttpStatusCode.BadRequest, blank.status)
        val cancelled = ctx.buyer.client.postOk<CancelSupplierInvoiceRequest, SupplierInvoiceDto>(
            "/api/supplier-invoices/${invoice.id}/cancel", CancelSupplierInvoiceRequest(expired.version, "Поставщик выставит новый счёт")
        )
        assertEquals(SupplierInvoiceStatus.CANCELLED, cancelled.status)
    }

    @Test
    fun `same invoice number is unique per supplier only`() = smokeApp {
        val ctx = setup()
        val documentId = uploadInvoiceFile(ctx.buyer.client)
        ctx.buyer.client.postOk<SupplierInvoiceInput, SupplierInvoiceDto>("/api/supplier-invoices", invoiceInput(ctx, documentId), HttpStatusCode.Created)
        val duplicate = ctx.buyer.client.postJson("/api/supplier-invoices", encode(invoiceInput(ctx, documentId)))
        assertEquals(HttpStatusCode.Conflict, duplicate.status)

        // У другого поставщика свой счёт с тем же номером допустим
        val other = ctx.buyer.client.postOk<CreateSupplierRequest, SupplierCardDto>(
            "/api/suppliers", CreateSupplierRequest(name = "ООО «Другой завод»", inn = "7702000004", idempotencyKey = newKey("sup")), HttpStatusCode.Created
        )
        val otherInvoice = SupplierInvoiceInput(
            number = "219658", invoiceDate = LocalDate.now().toString(), supplierId = other.supplier.id, documentId = documentId,
            supplierInn = "7702000004", netTotal = "100.00", vatTotal = "20.00", grossTotal = "120.00",
            lines = listOf(SupplierInvoiceLineInput(1, null, "Шина 30х4", ctx.nomenclatureId, null, "1", "шт", "100.00", "20", "20.00", "100.00"))
        )
        val created = ctx.buyer.client.postJson("/api/supplier-invoices", encode(otherInvoice))
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val warn = ctx.buyer.client.getOk<SupplierInvoiceCheckDto>("/api/supplier-invoices/${created.data<SupplierInvoiceDto>().id}/check")
        assertTrue(warn.issues.any { it.code == "NO_PURCHASE_ORDER" && it.severity == InvoiceIssueSeverity.WARNING })
    }

    @Test
    fun `invoice endpoints require invoice permissions`() = smokeApp {
        val ctx = setup()
        val reader = ctx.company.hire("reader", "Бухгалтер", perms("invoices:read"))
        val documentId = uploadInvoiceFile(ctx.buyer.client)
        val invoice = ctx.buyer.client.postOk<SupplierInvoiceInput, SupplierInvoiceDto>("/api/supplier-invoices", invoiceInput(ctx, documentId), HttpStatusCode.Created)

        assertEquals(HttpStatusCode.OK, reader.client.get("/api/supplier-invoices/${invoice.id}").status)
        assertEquals(HttpStatusCode.OK, reader.client.get("/api/supplier-invoices/${invoice.id}/file").status)
        val readerConfirm = reader.client.postJson(
            "/api/supplier-invoices/${invoice.id}/confirm-check", encode(ConfirmSupplierInvoiceCheckRequest(invoice.version))
        )
        assertEquals(HttpStatusCode.Forbidden, readerConfirm.status)
        // Склад без поступления по счёту файл не видит
        assertEquals(HttpStatusCode.Forbidden, ctx.keeper.client.get("/api/supplier-invoices/${invoice.id}/file").status)
    }

    // ------------------------------------------------------------------

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.setup(): Ctx {
        val c = company()
        val buyer = c.hire("buyer", "Снабженец Сидорова", perms(
            "suppliers:read", "suppliers:create", "suppliers:update", "suppliers:offer", "suppliers:order",
            "demands:read_any", "wms:read", "users:read_any", "invoices:manage"
        ))
        val initiator = c.hire("initiator", "Инженер Петров", perms("demands:create", "demands:read_any", "suppliers:read", "suppliers:select", "wms:read"))
        val chief = c.hire("chief", "Нач. снабжения Орлов", perms("suppliers:read", "suppliers:approve_order", "demands:read_any", "wms:read"))
        val keeper = c.hire("keeper", "Кладовщик Иванов", perms("wms:read", "wms:post", "wms:manage"))

        val wh = keeper.client.postOk<CreateWarehouseRequest, WarehouseDto>("/api/wms/warehouses", CreateWarehouseRequest("WH-1", "Основной склад"), HttpStatusCode.Created)
        val cell = keeper.client.postOk<CreateStorageLocationRequest, StorageLocationDto>(
            "/api/wms/locations", CreateStorageLocationRequest(wh.id, code = "E-01", name = "Электротехника", kind = StorageLocationKind.CELL), HttpStatusCode.Created
        )
        val top = keeper.client.postOk<CreateNomenclatureRequest, NomenclatureDto>(
            "/api/wms/nomenclature",
            CreateNomenclatureRequest(code = "TOP-0.66-120/5", name = "Трансформатор ТОП-0,66-5-0,2S-120/5 У3", type = NomenclatureType.PURCHASED_COMPONENT, unit = "шт"),
            HttpStatusCode.Created
        )
        val demand = initiator.client.postOk<CreateDemandRequest, DemandDetailDto>(
            "/api/demands",
            CreateDemandRequest(sourceType = DemandSourceType.MANUAL, requiredDate = LocalDate.now().plusDays(40).toString(), lines = listOf(DemandLineInput(1, top.id, "3", "шт"))),
            HttpStatusCode.Created
        )
        val inn = "6658017928"
        val supplier = buyer.client.postOk<CreateSupplierRequest, SupplierCardDto>(
            "/api/suppliers", CreateSupplierRequest(name = "ОАО «СЗТТ»", inn = inn, idempotencyKey = newKey("sup")), HttpStatusCode.Created
        )
        val verified = c.admin.client.postJson(
            "/api/suppliers/${supplier.supplier.id}/verification",
            encode(SupplierVerificationRequest(SupplierVerificationStatus.VERIFIED, "Проверен", supplier.supplier.version, newKey("ver")))
        )
        assertEquals(HttpStatusCode.OK, verified.status, verified.bodyAsText())
        val offer = buyer.client.postOk<CreateSupplierOfferRequest, SupplierOfferDto>(
            "/api/supplier-offers",
            CreateSupplierOfferRequest(
                demandId = demand.header.id, supplierId = supplier.supplier.id, compliance = SupplierOfferCompliance.FULL,
                documentsComplete = true, deliveryDays = 25, paymentTerms = "100% предоплата",
                validUntil = LocalDate.now().plusDays(30).toString(),
                lines = listOf(SupplierOfferLineInput(demand.lines.first().id, "3", "2220.00", "20")),
                idempotencyKey = newKey("offer")
            ),
            HttpStatusCode.Created
        )
        val offerFull = buyer.client.getOk<SupplierOfferDto>("/api/supplier-offers/${offer.id}")
        initiator.client.postOk<SelectSupplierOfferRequest, SupplierOfferDto>(
            "/api/supplier-offers/${offer.id}/select", SelectSupplierOfferRequest("Единственный производитель", offerFull.version, newKey("sel"))
        )
        var po = buyer.client.postOk<CreatePurchaseOrderRequest, PurchaseOrderDto>(
            "/api/purchase-orders",
            CreatePurchaseOrderRequest(
                offerId = offer.id, expectedAt = LocalDate.now().plusDays(35).toString(),
                lines = listOf(CreatePurchaseOrderLineInput(offerFull.lines.first().id, "3")), idempotencyKey = newKey("po")
            ),
            HttpStatusCode.Created
        )
        po = buyer.client.postOk<PurchaseOrderActionRequest, PurchaseOrderDto>("/api/purchase-orders/${po.id}/submit", PurchaseOrderActionRequest(po.version, idempotencyKey = newKey()))
        po = chief.client.postOk<PurchaseOrderActionRequest, PurchaseOrderDto>("/api/purchase-orders/${po.id}/approve", PurchaseOrderActionRequest(po.version, idempotencyKey = newKey()))
        po = buyer.client.postOk<PurchaseOrderActionRequest, PurchaseOrderDto>("/api/purchase-orders/${po.id}/send", PurchaseOrderActionRequest(po.version, idempotencyKey = newKey()))
        return Ctx(c, buyer, keeper, supplier.supplier.id, inn, po, top.id, wh.id, cell.id)
    }

    /** Счёт как в примере: 3 шт × 2 220,00, НДС 20 % сверху. */
    private fun invoiceInput(ctx: Ctx, documentId: Long, quantity: String = "3", price: String = "2220.00"): SupplierInvoiceInput {
        val net = BigDecimal(quantity).multiply(BigDecimal(price)).setScale(2)
        val vat = net.multiply(BigDecimal("0.20")).setScale(2)
        return SupplierInvoiceInput(
            number = "219658",
            invoiceDate = LocalDate.now().toString(),
            supplierId = ctx.supplierId,
            purchaseOrderId = ctx.order.id,
            documentId = documentId,
            supplierInn = ctx.supplierInn,
            supplierKpp = "665801001",
            netTotal = net.toPlainString(),
            vatTotal = vat.toPlainString(),
            grossTotal = (net + vat).toPlainString(),
            validUntil = LocalDate.now().plusDays(10).toString(),
            paymentTerms = "100% предоплата",
            prepaymentPct = "100",
            leadTimeDays = 25,
            leadTimeKind = LeadTimeKind.WORKING,
            deliveryTerms = "До склада ТК «Деловые Линии», тариф за покупателем",
            paymentPurpose = "Оплата по заказу клиента №219658",
            lines = listOf(
                SupplierInvoiceLineInput(
                    lineNo = 1, supplierItemCode = "10000185984", supplierItemName = "ТОП-0,66-5-0,2S-120/5 У3",
                    nomenclatureId = ctx.nomenclatureId, quantity = quantity, unit = "шт", unitPrice = price,
                    vatRate = "20", vatAmount = vat.toPlainString(), lineTotal = net.toPlainString()
                )
            )
        )
    }

    private suspend fun uploadInvoiceFile(client: HttpClient): Long {
        val doc = client.postOk<CreateDocumentRequest, DocumentDto>(
            "/api/documents", CreateDocumentRequest("Счёт № 219658 ОАО «СЗТТ»", DocumentType.INVOICE), HttpStatusCode.Created
        )
        uploadVersion(client, doc.id)
        return doc.id
    }

    private suspend fun uploadVersion(client: HttpClient, documentId: Long) {
        val response = client.submitFormWithBinaryData(
            url = "/api/documents/$documentId/versions",
            formData = formData {
                append("file", "%PDF-1.4 счёт 219658".toByteArray(Charsets.UTF_8), Headers.build {
                    append(HttpHeaders.ContentType, "application/pdf")
                    append(HttpHeaders.ContentDisposition, "filename=\"invoice-219658.pdf\"")
                })
            }
        )
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    private suspend inline fun <reified Req, reified Res> HttpClient.putOkJson(path: String, request: Req): Res {
        val response = putJson(path, encode(request))
        assertEquals(HttpStatusCode.OK, response.status, "PUT $path -> ${response.bodyAsText()}")
        return response.data()
    }

    @Before
    fun reset() = TestDatabase.reset()

    companion object {
        @JvmStatic
        @BeforeClass
        fun setup() = TestDatabase.init()

        @JvmStatic
        @AfterClass
        fun teardown() = TestDatabase.destroy()
    }
}
