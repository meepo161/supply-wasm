package org.example.project

import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.example.project.dto.*
import org.example.project.realtime.PresenceRegistry
import org.example.project.smoke.company
import org.example.project.smoke.data
import org.example.project.smoke.encode
import org.example.project.smoke.getOk
import org.example.project.smoke.newKey
import org.example.project.smoke.perms
import org.example.project.smoke.postJson
import org.example.project.smoke.postOk
import org.example.project.smoke.smokeApp
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Supply2ImprovementsRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `presence registry tracks online users and exposes endpoint`() = smokeApp {
        val company = company()
        val u1 = company.hire("presence1", "Presence User", perms("users:read"))

        PresenceRegistry.connect(u1.user.id)
        try {
            val online = u1.client.getOk<PresenceSnapshotDto>("/api/presence/online")
            assertTrue(u1.user.id in online.onlineUserIds)
        } finally {
            PresenceRegistry.disconnect(u1.user.id)
        }
    }

    @Test
    fun `inventory pause and resume blocks counts while paused and restores status`() = smokeApp {
        val company = company()
        val keeper = company.hire(
            "keeper_pause",
            "Кладовщик Пауза",
            perms("wms:read", "wms:manage", "wms:post", "nomenclature:read", "nomenclature:manage")
        )

        val wh = keeper.client.postOk<CreateWarehouseRequest, WarehouseDto>(
            "/api/wms/warehouses",
            CreateWarehouseRequest("WH-P", "Склад паузы"),
            HttpStatusCode.Created
        )
        val loc = keeper.client.postOk<CreateStorageLocationRequest, StorageLocationDto>(
            "/api/wms/locations",
            CreateStorageLocationRequest(wh.id, null, "A-01", "Ячейка A-01", StorageLocationKind.CELL, StorageLocationPurpose.STORAGE),
            HttpStatusCode.Created
        )
        val nom = keeper.client.postOk<CreateNomenclatureRequest, NomenclatureDto>(
            "/api/wms/nomenclature",
            CreateNomenclatureRequest("MAT-P", "Материал паузы", type = NomenclatureType.RAW_MATERIAL, unit = "шт"),
            HttpStatusCode.Created
        )
        val receipt = keeper.client.postOk<CreateReceiptRequest, ReceiptDto>(
            "/api/wms/receipts",
            CreateReceiptRequest(
                sourceType = ReceiptSourceType.OTHER,
                sourceRef = "Тест подсказки ячейки",
                warehouseId = wh.id,
                lines = listOf(ReceiptLineInput(lineNo = 1, nomenclatureId = nom.id, expectedQuantity = "5", receivedQuantity = "5", locationId = loc.id))
            ),
            HttpStatusCode.Created
        )
        keeper.client.postOk<PostReceiptRequest, ReceiptDto>(
            "/api/wms/receipts/${receipt.id}/post",
            PostReceiptRequest(receipt.version)
        )

        // Подсказка ячейки должна предложить созданную ячейку
        val suggested = keeper.client.getOk<SuggestLocationDto>(
            "/api/wms/suggest-location?warehouseId=${wh.id}&nomenclatureId=${nom.id}"
        )
        assertEquals(loc.id, suggested.locationId)
        assertEquals("A-01", suggested.locationCode)

        val inv = keeper.client.postOk<CreateInventoryRequest, InventoryDto>(
            "/api/wms/inventories",
            CreateInventoryRequest(wh.id, InventoryScopeType.WAREHOUSE, idempotencyKey = newKey()),
            HttpStatusCode.Created
        )
        val booked = keeper.client.postOk<InventoryActionRequest, InventoryDto>(
            "/api/wms/inventories/${inv.id}/book",
            InventoryActionRequest(inv.version, newKey())
        )
        assertEquals(InventoryStatus.BOOKED, booked.status)

        val paused = keeper.client.postOk<InventoryActionRequest, InventoryDto>(
            "/api/wms/inventories/${inv.id}/pause",
            InventoryActionRequest(booked.version, newKey())
        )
        assertEquals(InventoryStatus.PAUSED, paused.status)
        assertEquals(InventoryStatus.BOOKED, paused.pausedFromStatus)

        // Во время паузы ввод подсчёта должен отклоняться (409 Conflict)
        val countWhilePaused = keeper.client.postJson(
            "/api/wms/inventories/${inv.id}/counts",
            encode(
                SubmitInventoryCountsRequest(
                    version = paused.version,
                    counts = listOf(InventoryCountInput(paused.snapshot.first().id, "5")),
                    idempotencyKey = newKey()
                )
            )
        )
        assertEquals(HttpStatusCode.Conflict, countWhilePaused.status)

        val resumed = keeper.client.postOk<InventoryActionRequest, InventoryDto>(
            "/api/wms/inventories/${inv.id}/resume",
            InventoryActionRequest(paused.version, newKey())
        )
        assertEquals(InventoryStatus.BOOKED, resumed.status)
    }

    @Test
    fun `serial calibration dates can be updated and validated`() = smokeApp {
        val company = company()
        val keeper = company.hire(
            "keeper_cal",
            "Кладовщик Поверка",
            perms("wms:read", "wms:manage", "wms:post", "nomenclature:read", "nomenclature:manage")
        )

        val wh = keeper.client.postOk<CreateWarehouseRequest, WarehouseDto>(
            "/api/wms/warehouses",
            CreateWarehouseRequest("WH-CAL", "Склад поверки"),
            HttpStatusCode.Created
        )
        val loc = keeper.client.postOk<CreateStorageLocationRequest, StorageLocationDto>(
            "/api/wms/locations",
            CreateStorageLocationRequest(wh.id, null, "B-01", "Ячейка B-01", StorageLocationKind.CELL, StorageLocationPurpose.STORAGE),
            HttpStatusCode.Created
        )
        val nom = keeper.client.postOk<CreateNomenclatureRequest, NomenclatureDto>(
            "/api/wms/nomenclature",
            CreateNomenclatureRequest("DEV-1", "Прибор 1", type = NomenclatureType.EQUIPMENT, unit = "шт", serialTracked = true),
            HttpStatusCode.Created
        )
        org.jooq.impl.DSL.using(TestDatabase.TEST_URL, "erp", "erp").use { dsl ->
            val rec = dsl.newRecord(org.example.project.generated.jooq.tables.SerialItems.SERIAL_ITEMS)
            rec.nomenclatureId = nom.id
            rec.serialNumber = "SN-CAL-001"
            rec.createdBy = keeper.user.id
            rec.store()
        }
        val serials = keeper.client.getOk<List<SerialItemDto>>("/api/wms/serials?nomenclatureId=${nom.id}")
        val serial = serials.single()

        val invalidResp = keeper.client.patch("/api/wms/serials/${serial.id}/calibration") {
            contentType(ContentType.Application.Json)
            setBody(encode(UpdateSerialCalibrationRequest("2026-10-10", "2026-01-01")))
        }
        assertEquals(HttpStatusCode.BadRequest, invalidResp.status)

        val okResp = keeper.client.patch("/api/wms/serials/${serial.id}/calibration") {
            contentType(ContentType.Application.Json)
            setBody(encode(UpdateSerialCalibrationRequest("2026-01-15", "2027-01-15")))
        }
        assertEquals(HttpStatusCode.OK, okResp.status)
        val updated = okResp.data<SerialItemDto>()
        assertEquals("2026-01-15", updated.calibrationDate)
        assertEquals("2027-01-15", updated.calibrationExpiryDate)
    }

    @Test
    fun `supplier invoice pdf parse extracts fields`() = smokeApp {
        val company = company()
        val buyer = company.hire("buyer_pdf", "Снабженец PDF", perms("invoices:read", "invoices:manage"))

        val pdfBytes = ByteArrayOutputStream().use { out ->
            PDDocument().use { doc ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText("INN 7707083893 KPP 770701001")
                    cs.endText()
                }
                doc.save(out)
            }
            out.toByteArray()
        }

        val resp = buyer.client.submitFormWithBinaryData(
            url = "/api/supplier-invoices/parse-pdf",
            formData = formData {
                append(
                    key = "file",
                    value = pdfBytes,
                    headers = Headers.build {
                        append(HttpHeaders.ContentType, "application/pdf")
                        append(HttpHeaders.ContentDisposition, "filename=\"invoice.pdf\"")
                    }
                )
            }
        )
        assertEquals(HttpStatusCode.OK, resp.status)
        val parsed = resp.data<SupplierInvoiceAutofillDto>()
        assertTrue(parsed.extractedText.contains("7707083893"))
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun up() {
            TestDatabase.init()
        }

        @AfterClass
        @JvmStatic
        fun down() {
            TestDatabase.destroy()
        }
    }
}
