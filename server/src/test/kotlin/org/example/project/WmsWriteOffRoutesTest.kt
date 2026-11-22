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

class WmsWriteOffRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
        truncateWms()
    }

    @Test
    fun `write-off posts and decreases only free stock`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "10")
        val limitSet = client.put("/api/wms/write-off-limit/${warehouse.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SetWriteOffLimitRequest("100")))
        }
        assertEquals(HttpStatusCode.OK, limitSet.status, limitSet.bodyAsText())

        val created = createWriteOff(client, warehouse.id, WriteOffReasonType.DAMAGE, "Повреждение при хранении", cell.id, nomenclature.id, "3")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val writeOff = created.bodyAs<ApiResponse<WriteOffDto>>().data!!
        assertEquals(WriteOffStatus.DRAFT, writeOff.status)
        assertTrue(writeOff.number.startsWith("WOF-"))
        assertQty("3", writeOff.totalQuantity)
        assertEquals(1, writeOff.lines.size)

        val posted = client.post("/api/wms/write-offs/${writeOff.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(WriteOffActionRequest(writeOff.version, "post-1")))
        }
        assertEquals(HttpStatusCode.OK, posted.status, posted.bodyAsText())
        val postedDto = posted.bodyAs<ApiResponse<WriteOffDto>>().data!!
        assertEquals(WriteOffStatus.POSTED, postedDto.status)
        assertNotNull(postedDto.postedAt)

        val stock = getStock(client, warehouse.id, nomenclature.id)
        assertEquals(1, stock.size)
        assertQty("7", stock.first().physicalQty)
        assertQty("7", stock.first().availableQty)

        // Повторное проведение отклоняется: документ уже проведён.
        val again = client.post("/api/wms/write-offs/${writeOff.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(WriteOffActionRequest(postedDto.version, "post-2")))
        }
        assertEquals(HttpStatusCode.Conflict, again.status)

        assertEquals(1, countMovements(warehouse.id, nomenclature.id, "WRITE_OFF"))
    }

    @Test
    fun `reserved stock cannot be written off`() = testApp {
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

        val writeOff = createWriteOff(client, warehouse.id, WriteOffReasonType.LOSS, "Утеряно при перемещении", cell.id, nomenclature.id, "3")
            .bodyAs<ApiResponse<WriteOffDto>>().data!!
        // Свободно только 2 из 10.
        val posted = client.post("/api/wms/write-offs/${writeOff.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(WriteOffActionRequest(writeOff.version, "post-reserved")))
        }
        assertEquals(HttpStatusCode.Conflict, posted.status, posted.bodyAsText())

        val stock = getStock(client, warehouse.id, nomenclature.id).first()
        assertQty("10", stock.physicalQty)
        assertQty("8", stock.reservedQty)
    }

    @Test
    fun `write-off over the warehouse limit requires approval route`() = testApp {
        val admin = jsonClient()
        val keeper = createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        createUserWithRole(
            admin, "manager", "password", "Manager User",
            wmsPermissions() + listOf(
                CreatePermissionRequest("approvals", "start"),
                CreatePermissionRequest("approvals", "manage_templates")
            )
        )
        val client = loginClient("keeper", "password")
        val manager = loginClient("manager", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "10")

        // Лимит склада — 2, списываем 5: без согласования проведение запрещено.
        val limitSet = client.put("/api/wms/write-off-limit/${warehouse.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SetWriteOffLimitRequest("2")))
        }
        assertEquals(HttpStatusCode.OK, limitSet.status, limitSet.bodyAsText())
        val limit = limitSet.bodyAs<ApiResponse<WriteOffLimitDto>>().data!!
        assertQty("2", limit.limitQuantity)

        val writeOff = createWriteOff(client, warehouse.id, WriteOffReasonType.DAMAGE, "Повреждение при транспортировке", cell.id, nomenclature.id, "5")
            .bodyAs<ApiResponse<WriteOffDto>>().data!!
        val denied = client.post("/api/wms/write-offs/${writeOff.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(WriteOffActionRequest(writeOff.version, "post-over")))
        }
        assertEquals(HttpStatusCode.Conflict, denied.status, denied.bodyAsText())
        assertTrue(denied.bodyAsText().contains("limit"))

        // Маршрут согласования: шаблон цели WRITE_OFF, одна стадия, один согласующий.
        val templateResponse = manager.post("/api/approvals/templates") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateApprovalTemplateRequest(
                        code = "WO-1",
                        name = "Списание сверх лимита",
                        targetType = "WRITE_OFF",
                        stages = listOf(
                            ApprovalStageInput(
                                stageNo = 1,
                                name = "Склад",
                                decisionPolicy = ApprovalDecisionPolicy.ALL,
                                assignees = listOf(
                                    ApprovalAssigneeInput(
                                        type = ApprovalAssigneeType.USER,
                                        userId = keeper.id
                                    )
                                )
                            )
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, templateResponse.status, templateResponse.bodyAsText())
        val template = templateResponse.bodyAs<ApiResponse<ApprovalTemplateDto>>().data!!

        val routeResponse = manager.post("/api/approvals/routes") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    StartApprovalRouteRequest(
                        templateId = template.id,
                        targetType = "WRITE_OFF",
                        targetId = writeOff.id,
                        idempotencyKey = "route-1"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, routeResponse.status, routeResponse.bodyAsText())
        val route = routeResponse.bodyAs<ApiResponse<ApprovalRouteDto>>().data!!

        val current = client.get("/api/wms/write-offs/${writeOff.id}")
            .bodyAs<ApiResponse<WriteOffDto>>().data!!
        assertEquals(WriteOffStatus.PENDING_APPROVAL, current.status)

        // Согласующий — хранитель (assignee по user_id), решение принимает он.
        val decision = client.post("/api/approvals/routes/${route.id}/decisions") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    SubmitApprovalDecisionRequest(
                        decision = ApprovalDecision.APPROVE,
                        comment = "Согласовано"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, decision.status, decision.bodyAsText())

        val approved = client.get("/api/wms/write-offs/${writeOff.id}")
            .bodyAs<ApiResponse<WriteOffDto>>().data!!
        assertEquals(WriteOffStatus.APPROVED, approved.status)

        val posted = client.post("/api/wms/write-offs/${writeOff.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(WriteOffActionRequest(approved.version, "post-approved")))
        }
        assertEquals(HttpStatusCode.OK, posted.status, posted.bodyAsText())
        val stock = getStock(client, warehouse.id, nomenclature.id).first()
        assertQty("5", stock.physicalQty)
    }

    @Test
    fun `write-off command is idempotent`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "10")
        val limitSet = client.put("/api/wms/write-off-limit/${warehouse.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SetWriteOffLimitRequest("100")))
        }
        assertEquals(HttpStatusCode.OK, limitSet.status, limitSet.bodyAsText())

        val writeOff = createWriteOff(client, warehouse.id, WriteOffReasonType.EXPIRY, "Истёк срок годности", cell.id, nomenclature.id, "4", key = "create-1")
            .bodyAs<ApiResponse<WriteOffDto>>().data!!
        val first = client.post("/api/wms/write-offs/${writeOff.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(WriteOffActionRequest(writeOff.version, "post-idem")))
        }
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val second = client.post("/api/wms/write-offs/${writeOff.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(WriteOffActionRequest(writeOff.version, "post-idem")))
        }
        assertEquals(HttpStatusCode.OK, second.status, second.bodyAsText())

        val stock = getStock(client, warehouse.id, nomenclature.id).first()
        assertQty("6", stock.physicalQty)
    }

    @Test
    fun `write-off requires wms post permission`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        createUserWithRole(admin, "reader", "password", "Reader User", listOf(CreatePermissionRequest("wms", "read")))
        val keeper = loginClient("keeper", "password")
        val reader = loginClient("reader", "password")

        val warehouse = createWarehouse(keeper, "WH-1")
        val cell = createLocation(keeper, warehouse.id, "A-01")
        val nomenclature = createNomenclature(keeper, "MAT-1")
        postInitialStock(keeper, warehouse.id, cell.id, nomenclature.id, "5")

        val deniedCreate = createWriteOff(reader, warehouse.id, WriteOffReasonType.OTHER, "Прочие потери на складе", cell.id, nomenclature.id, "1")
        assertEquals(HttpStatusCode.Forbidden, deniedCreate.status)

        val writeOff = createWriteOff(keeper, warehouse.id, WriteOffReasonType.OTHER, "Прочие потери на складе", cell.id, nomenclature.id, "1")
            .bodyAs<ApiResponse<WriteOffDto>>().data!!
        val listAllowed = reader.get("/api/wms/write-offs")
        assertEquals(HttpStatusCode.OK, listAllowed.status, listAllowed.bodyAsText())
        val deniedPost = reader.post("/api/wms/write-offs/${writeOff.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(WriteOffActionRequest(writeOff.version, "reader-post")))
        }
        assertEquals(HttpStatusCode.Forbidden, deniedPost.status)
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
        if (bootstrapResponse.status != HttpStatusCode.Created && bootstrapResponse.status != HttpStatusCode.Unauthorized) {
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

    private suspend fun createWriteOff(
        client: HttpClient,
        warehouseId: Long,
        reasonType: WriteOffReasonType,
        reasonNote: String,
        locationId: Long,
        nomenclatureId: Long,
        quantity: String,
        key: String = "create-${System.nanoTime()}"
    ): HttpResponse = client.post("/api/wms/write-offs") {
        contentType(ContentType.Application.Json)
        setBody(
            stringify(
                CreateWriteOffRequest(
                    warehouseId = warehouseId,
                    reasonType = reasonType,
                    reasonNote = reasonNote,
                    lines = listOf(
                        WriteOffLineInput(
                            lineNo = 1,
                            nomenclatureId = nomenclatureId,
                            locationId = locationId,
                            quantity = quantity
                        )
                    ),
                    idempotencyKey = key
                )
            )
        )
    }

    private suspend fun getStock(client: HttpClient, warehouseId: Long, nomenclatureId: Long): List<StockBalanceDto> {
        val response = client.get("/api/wms/stock?warehouseId=$warehouseId&nomenclatureId=$nomenclatureId")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<List<StockBalanceDto>>>().data!!
    }

    // Отдельного read-API журнала движений пока нет (WMS-39), поэтому
    // факт появления движения WRITE_OFF проверяется напрямую в БД.
    private fun countMovements(warehouseId: Long, nomenclatureId: Long, movementType: String): Int {
        return PGSimpleDataSource().apply {
            setUrl(TEST_URL)
            user = USER
            password = PASSWORD
        }.connection.use { conn ->
            conn.prepareStatement(
                """
                SELECT COUNT(*)
                FROM stock_movement m
                JOIN stock_balance b ON b.location_id = m.from_location_id
                WHERE m.movement_type = ? AND m.nomenclature_id = ?
                  AND m.document_type = 'WRITE_OFF' AND b.warehouse_id = ?
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, movementType)
                stmt.setLong(2, nomenclatureId)
                stmt.setLong(3, warehouseId)
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
