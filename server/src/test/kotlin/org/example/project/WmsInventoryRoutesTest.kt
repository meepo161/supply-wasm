package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.example.project.dto.*
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WmsInventoryRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
        truncateWms()
    }

    @Test
    fun `inventory fixes surplus and shortage with adjustments`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cellA = createLocation(client, warehouse.id, "A-01")
        val cellB = createLocation(client, warehouse.id, "A-02")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cellA.id, nomenclature.id, "10")
        postInitialStock(client, warehouse.id, cellB.id, nomenclature.id, "5")

        val created = createInventory(client, warehouse.id, InventoryScopeType.WAREHOUSE)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val inventory = created.bodyAs<ApiResponse<InventoryDto>>().data!!
        assertEquals(InventoryStatus.DRAFT, inventory.status)
        assertTrue(inventory.number.startsWith("INV-"))

        val booked = client.post("/api/wms/inventories/${inventory.id}/book") {
            contentType(ContentType.Application.Json)
            setBody(stringify(InventoryActionRequest(inventory.version, "book-1")))
        }
        assertEquals(HttpStatusCode.OK, booked.status, booked.bodyAsText())
        val bookedDto = booked.bodyAs<ApiResponse<InventoryDto>>().data!!
        assertEquals(InventoryStatus.BOOKED, bookedDto.status)
        assertEquals(2, bookedDto.snapshot.size)

        val cellALine = bookedDto.snapshot.first { it.locationId == cellA.id }
        val cellBLine = bookedDto.snapshot.first { it.locationId == cellB.id }
        assertQty("10", cellALine.bookQuantity)
        assertQty("5", cellBLine.bookQuantity)

        // Факт: в A-01 лежит 12 (излишек), в A-02 — 4 (недостача).
        val counted = client.post("/api/wms/inventories/${inventory.id}/counts") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SubmitInventoryCountsRequest(
                        version = bookedDto.version,
                        counts = listOf(
                            InventoryCountInput(cellALine.id, "12"),
                            InventoryCountInput(cellBLine.id, "4")
                        ),
                        idempotencyKey = "count-1"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, counted.status, counted.bodyAsText())
        val countedDto = counted.bodyAs<ApiResponse<InventoryDto>>().data!!
        assertEquals(InventoryStatus.DISCREPANCY, countedDto.status)
        assertEquals(2, countedDto.discrepancies.size)
        val surplus = countedDto.discrepancies.first { it.locationId == cellA.id }
        val shortage = countedDto.discrepancies.first { it.locationId == cellB.id }
        assertEquals(InventoryDiscrepancyKind.SURPLUS, surplus.kind)
        assertQty("2", surplus.discrepancy)
        assertEquals(InventoryDiscrepancyKind.SHORTAGE, shortage.kind)
        assertQty("-1", shortage.discrepancy)

        val approved = client.post("/api/wms/inventories/${inventory.id}/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(InventoryActionRequest(countedDto.version, "approve-1")))
        }
        assertEquals(HttpStatusCode.OK, approved.status, approved.bodyAsText())
        val approvedDto = approved.bodyAs<ApiResponse<InventoryDto>>().data!!

        val posted = client.post("/api/wms/inventories/${inventory.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(InventoryActionRequest(approvedDto.version, "post-1")))
        }
        assertEquals(HttpStatusCode.OK, posted.status, posted.bodyAsText())
        assertEquals(InventoryStatus.POSTED, posted.bodyAs<ApiResponse<InventoryDto>>().data!!.status)

        val stock = getStock(client, warehouse.id, nomenclature.id).associateBy { it.locationId }
        assertQty("12", stock.getValue(cellA.id).physicalQty)
        assertQty("4", stock.getValue(cellB.id).physicalQty)
        assertEquals(1, countMovements(nomenclature.id, "ADJUSTMENT_IN"))
        assertEquals(1, countMovements(nomenclature.id, "ADJUSTMENT_OUT"))
    }

    @Test
    fun `shortage cannot exceed free stock`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "10")

        val reserve = client.post("/api/wms/reservations") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateReservationRequest(
                        reasonType = ReservationReasonType.OTHER,
                        nomenclatureId = nomenclature.id,
                        warehouseId = warehouse.id,
                        locationId = cell.id,
                        quantity = "8"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, reserve.status, reserve.bodyAsText())

        val inventory = createInventory(client, warehouse.id, InventoryScopeType.WAREHOUSE)
            .bodyAs<ApiResponse<InventoryDto>>().data!!
        val booked = client.post("/api/wms/inventories/${inventory.id}/book") {
            contentType(ContentType.Application.Json)
            setBody(stringify(InventoryActionRequest(inventory.version, "book-short")))
        }.bodyAs<ApiResponse<InventoryDto>>().data!!
        val line = booked.snapshot.first()

        // Факт 0 при 8 в резерве: списать недостачу нельзя, свободно только 2.
        val counted = client.post("/api/wms/inventories/${inventory.id}/counts") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SubmitInventoryCountsRequest(
                        version = booked.version,
                        counts = listOf(InventoryCountInput(line.id, "0")),
                        idempotencyKey = "count-short"
                    )
                )
            )
        }.bodyAs<ApiResponse<InventoryDto>>().data!!

        val posted = client.post("/api/wms/inventories/${inventory.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(InventoryActionRequest(counted.version, "post-short")))
        }
        assertEquals(HttpStatusCode.Conflict, posted.status, posted.bodyAsText())

        val stock = getStock(client, warehouse.id, nomenclature.id).first()
        assertQty("10", stock.physicalQty)
        assertQty("8", stock.reservedQty)
    }

    @Test
    fun `active inventory blocks a second one on the same warehouse`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "3")

        val first = createInventory(client, warehouse.id, InventoryScopeType.WAREHOUSE)
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val firstDto = first.bodyAs<ApiResponse<InventoryDto>>().data!!
        client.post("/api/wms/inventories/${firstDto.id}/book") {
            contentType(ContentType.Application.Json)
            setBody(stringify(InventoryActionRequest(firstDto.version, "book-block")))
        }

        val second = createInventory(client, warehouse.id, InventoryScopeType.CELL, locationId = cell.id)
        assertEquals(HttpStatusCode.Conflict, second.status, second.bodyAsText())

        // Отмена активной инвентаризации освобождает склад.
        val booked = client.get("/api/wms/inventories/${firstDto.id}")
            .bodyAs<ApiResponse<InventoryDto>>().data!!
        val cancelled = client.post("/api/wms/inventories/${firstDto.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CancelInventoryRequest(booked.version, "Ошибочное задание", "cancel-1")))
        }
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        val third = createInventory(client, warehouse.id, InventoryScopeType.CELL, locationId = cell.id)
        assertEquals(HttpStatusCode.Created, third.status, third.bodyAsText())
    }

    @Test
    fun `recount overrides an earlier count`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "6")

        val inventory = createInventory(client, warehouse.id, InventoryScopeType.WAREHOUSE)
            .bodyAs<ApiResponse<InventoryDto>>().data!!
        val booked = client.post("/api/wms/inventories/${inventory.id}/book") {
            contentType(ContentType.Application.Json)
            setBody(stringify(InventoryActionRequest(inventory.version, "book-recount")))
        }.bodyAs<ApiResponse<InventoryDto>>().data!!
        val line = booked.snapshot.first()

        val first = client.post("/api/wms/inventories/${inventory.id}/counts") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SubmitInventoryCountsRequest(
                        version = booked.version,
                        counts = listOf(InventoryCountInput(line.id, "2", note = "первый подсчёт")),
                        idempotencyKey = "count-first"
                    )
                )
            )
        }.bodyAs<ApiResponse<InventoryDto>>().data!!
        val second = client.post("/api/wms/inventories/${inventory.id}/counts") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SubmitInventoryCountsRequest(
                        version = first.version,
                        counts = listOf(
                            InventoryCountInput(line.id, "6", recount = true, note = "пересчёт")
                        ),
                        idempotencyKey = "count-second"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())
        val recounted = second.bodyAs<ApiResponse<InventoryDto>>().data!!
        // Пересчёт совпал с учётом: последняя строка ведомости без расхождения,
        // документ остаётся в подсчёте, согласовать нечего.
        assertEquals(1, recounted.discrepancies.size)
        assertQty("0", recounted.discrepancies.first().discrepancy)
        assertEquals(null, recounted.discrepancies.first().kind)
        assertEquals(InventoryStatus.COUNTING, recounted.status)
        val approve = client.post("/api/wms/inventories/${inventory.id}/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(InventoryActionRequest(recounted.version, "approve-none")))
        }
        assertEquals(HttpStatusCode.BadRequest, approve.status, approve.bodyAsText())
    }

    @Test
    fun `inventory commands require wms post permission`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        createUserWithRole(admin, "reader", "password", "Reader User", listOf(CreatePermissionRequest("wms", "read")))
        val keeper = loginClient("keeper", "password")
        val reader = loginClient("reader", "password")

        val warehouse = createWarehouse(keeper, "WH-1")
        val cell = createLocation(keeper, warehouse.id, "A-01")
        val nomenclature = createNomenclature(keeper, "MAT-1")
        postInitialStock(keeper, warehouse.id, cell.id, nomenclature.id, "4")

        val denied = createInventory(reader, warehouse.id, InventoryScopeType.WAREHOUSE)
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        val list = reader.get("/api/wms/inventories")
        assertEquals(HttpStatusCode.OK, list.status, list.bodyAsText())
    }

    // --- вспомогательные методы ---

    private suspend fun ApplicationTestBuilder.createUserWithRole(
        adminClient: HttpClient,
        login: String,
        password: String,
        fullName: String,
        permissions: List<CreatePermissionRequest>
    ): UserDto {
        val bootstrapResponse = adminClient.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        if (bootstrapResponse.status != HttpStatusCode.Created &&
            bootstrapResponse.status != HttpStatusCode.Unauthorized
        ) {
            assertEquals(HttpStatusCode.Created, bootstrapResponse.status, bootstrapResponse.bodyAsText())
        }
        adminClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        val roleResponse = adminClient.post("/api/roles") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateRoleRequest(login.uppercase(), fullName, permissions = permissions)))
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

    private suspend fun ApplicationTestBuilder.loginClient(login: String, password: String): HttpClient {
        val client = jsonClient()
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest(login, password)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return client
    }

    private suspend fun createWarehouse(client: HttpClient, code: String): WarehouseDto {
        val response = client.post("/api/wms/warehouses") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateWarehouseRequest(code, "Warehouse $code")))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<WarehouseDto>>().data!!
    }

    private suspend fun createLocation(client: HttpClient, warehouseId: Long, code: String): StorageLocationDto {
        val response = client.post("/api/wms/locations") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateStorageLocationRequest(
                        warehouseId = warehouseId,
                        code = code,
                        name = "Location $code",
                        kind = StorageLocationKind.CELL,
                        purpose = StorageLocationPurpose.STORAGE
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<StorageLocationDto>>().data!!
    }

    private suspend fun createNomenclature(client: HttpClient, code: String): NomenclatureDto {
        val response = client.post("/api/wms/nomenclature") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateNomenclatureRequest(
                        code = code,
                        name = "Nomenclature $code",
                        type = NomenclatureType.MATERIAL,
                        unit = "pcs"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<NomenclatureDto>>().data!!
    }

    private suspend fun postInitialStock(
        client: HttpClient,
        warehouseId: Long,
        locationId: Long,
        nomenclatureId: Long,
        quantity: String
    ) {
        val createResponse = client.post("/api/wms/receipts") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateReceiptRequest(
                        sourceType = ReceiptSourceType.INITIAL_STOCK,
                        warehouseId = warehouseId,
                        lines = listOf(
                            ReceiptLineInput(
                                lineNo = 1,
                                nomenclatureId = nomenclatureId,
                                expectedQuantity = quantity,
                                locationId = locationId
                            )
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, createResponse.status, createResponse.bodyAsText())
        val receipt = createResponse.bodyAs<ApiResponse<ReceiptDto>>().data!!
        val postResponse = client.post("/api/wms/receipts/${receipt.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PostReceiptRequest(receipt.version)))
        }
        assertEquals(HttpStatusCode.OK, postResponse.status, postResponse.bodyAsText())
    }

    private suspend fun createInventory(
        client: HttpClient,
        warehouseId: Long,
        scopeType: InventoryScopeType,
        locationId: Long? = null,
        nomenclatureId: Long? = null
    ): HttpResponse = client.post("/api/wms/inventories") {
        contentType(ContentType.Application.Json)
        setBody(
            stringify(
                CreateInventoryRequest(
                    warehouseId = warehouseId,
                    scopeType = scopeType,
                    scopeLocationId = locationId,
                    scopeNomenclatureId = nomenclatureId,
                    idempotencyKey = "inv-${System.nanoTime()}"
                )
            )
        )
    }

    private suspend fun getStock(client: HttpClient, warehouseId: Long, nomenclatureId: Long): List<StockBalanceDto> {
        val response = client.get("/api/wms/stock?warehouseId=$warehouseId&nomenclatureId=$nomenclatureId")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<List<StockBalanceDto>>>().data!!
    }

    // Отдельного read-API журнала движений пока нет (WMS-39).
    private fun countMovements(nomenclatureId: Long, movementType: String): Int {
        return PGSimpleDataSource().apply {
            setUrl(TEST_URL)
            user = USER
            password = PASSWORD
        }.connection.use { conn ->
            conn.prepareStatement(
                "SELECT COUNT(*) FROM stock_movement WHERE movement_type = ? AND nomenclature_id = ?"
            ).use { stmt ->
                stmt.setString(1, movementType)
                stmt.setLong(2, nomenclatureId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }
    }

    private fun assertQty(expected: String, actual: String) {
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "expected $expected but was $actual")
    }

    private fun truncateWms() {
        PGSimpleDataSource().apply {
            setUrl(TEST_URL)
            user = USER
            password = PASSWORD
        }.connection.use { conn ->
            conn.createStatement().execute(
                """
                TRUNCATE TABLE
                  wms_inventory_idempotency_keys, inventory_discrepancies, inventory_counts, inventory_snapshot, inventory_orders,
                  wms_write_off_idempotency_keys, write_off_lines, write_off_orders, wms_write_off_limits,
                  wms_idempotency_keys, transfer_events, transfers,
                  receipt_line_serials, receipt_lines, receipts,
                  reservation_history, reservations,
                  stock_movement, stock_balance,
                  serial_items, lots, nomenclature_barcodes, nomenclature,
                  storage_locations, warehouses
                RESTART IDENTITY CASCADE
                """.trimIndent()
            )
        }
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

    private suspend inline fun <reified T> HttpResponse.bodyAs(): T {
        return json.decodeFromString(serializer<T>(), bodyAsText())
    }

    private inline fun <reified T> stringify(value: T): String {
        return json.encodeToString(serializer<T>(), value)
    }

    companion object {
        private val TEST_URL = TestDatabase.TEST_URL
        private const val USER = "erp"
        private const val PASSWORD = "erp"

        private val json = Json {
            ignoreUnknownKeys = true
        }

        @JvmStatic
        @BeforeClass
        fun setup() {
            TestDatabase.init()
        }

        @JvmStatic
        @AfterClass
        fun teardown() {
            TestDatabase.destroy()
        }

        private fun wmsPermissions() = listOf(
            CreatePermissionRequest("wms", "manage"),
            CreatePermissionRequest("wms", "post"),
            CreatePermissionRequest("wms", "read")
        )
    }
}
