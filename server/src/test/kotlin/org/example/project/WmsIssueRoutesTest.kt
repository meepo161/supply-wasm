package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.example.project.dto.*
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.postgresql.ds.PGSimpleDataSource
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WmsIssueRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
        truncateWms()
    }

    @Test
    fun `issue from reservation posts and fulfills reservation`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "10")
        val reservation = reserve(client, warehouse.id, cell.id, nomenclature.id, "6")

        val order = createIssue(client, warehouse.id, nomenclature.id, "6", reservationId = reservation.id)
        val picked = pickAll(client, order, cell.id)
        val issued = issueOrder(client, picked)
        assertEquals(IssueStatus.ISSUED, issued.status)

        val confirmed = confirmIssue(client, issued)
        assertEquals(IssueStatus.RECEIVED, confirmed.status)
        assertQty("6", confirmed.lines[0].issuedQuantity)
        assertNotNull(confirmed.confirmedAt)

        val stock = getStock(client, warehouse.id, nomenclature.id)
        assertEquals(1, stock.size)
        assertQty("4", stock[0].physicalQty)
        assertQty("0", stock[0].reservedQty)
        assertQty("4", stock[0].availableQty)

        val reservationAfter = client.get("/api/wms/reservations/${reservation.id}")
            .bodyAs<ApiResponse<ReservationDto>>().data!!
        assertEquals(ReservationStatus.FULFILLED, reservationAfter.status)
        assertQty("6", reservationAfter.issuedQuantity)
        val actions = reservationAfter.history.map { it.action }
        assertTrue(ReservationHistoryAction.ISSUED in actions)
        assertTrue(ReservationHistoryAction.FULFILLED in actions)
    }

    @Test
    fun `partial picking posts shortage`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "10")

        val order = createIssue(client, warehouse.id, nomenclature.id, "10")
        val line = order.lines[0]
        val picksResponse = client.post("/api/wms/issues/${order.id}/picks") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    PickIssueRequest(
                        version = order.version,
                        picks = listOf(IssuePickInput(lineId = line.id, locationId = cell.id, quantity = "4")),
                        idempotencyKey = newKey()
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, picksResponse.status, picksResponse.bodyAsText())
        val picked = picksResponse.bodyAs<ApiResponse<IssueOrderDto>>().data!!

        val confirmed = confirmIssue(client, issueOrder(client, picked))
        assertEquals(IssueStatus.SHORTAGE, confirmed.status)
        assertQty("4", confirmed.lines[0].issuedQuantity)
        assertQty("10", confirmed.lines[0].requestedQuantity)

        val stock = getStock(client, warehouse.id, nomenclature.id)
        assertQty("6", stock[0].physicalQty)
    }

    @Test
    fun `issue without reservation requires dedicated permission`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        createUserWithRole(
            admin, "poster", "password", "Poster User",
            listOf(
                CreatePermissionRequest("wms", "manage"),
                CreatePermissionRequest("wms", "post"),
                CreatePermissionRequest("wms", "read")
            )
        )
        val keeperClient = loginClient("keeper", "password")
        val posterClient = loginClient("poster", "password")

        val warehouse = createWarehouse(keeperClient, "WH-1")
        val cell = createLocation(keeperClient, warehouse.id, "A-01")
        val nomenclature = createNomenclature(keeperClient, "MAT-1")
        postInitialStock(keeperClient, warehouse.id, cell.id, nomenclature.id, "10")

        val order = createIssue(posterClient, warehouse.id, nomenclature.id, "3")
        val picked = pickAll(posterClient, order, cell.id)
        val issued = issueOrder(posterClient, picked)

        val confirmDenied = confirmIssueResponse(posterClient, issued)
        assertEquals(HttpStatusCode.Forbidden, confirmDenied.status)

        val confirmAllowed = confirmIssueResponse(keeperClient, issued)
        assertEquals(HttpStatusCode.OK, confirmAllowed.status, confirmAllowed.bodyAsText())
        assertQty("7", getStock(keeperClient, warehouse.id, nomenclature.id)[0].physicalQty)
    }

    @Test
    fun `reservation cannot be issued twice`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "10")
        val reservation = reserve(client, warehouse.id, cell.id, nomenclature.id, "5")

        val orderA = createIssue(client, warehouse.id, nomenclature.id, "5", reservationId = reservation.id)
        val orderB = createIssue(client, warehouse.id, nomenclature.id, "5", reservationId = reservation.id)
        val issuedA = issueOrder(client, pickAll(client, orderA, cell.id))
        val issuedB = issueOrder(client, pickAll(client, orderB, cell.id))

        val confirmA = confirmIssueResponse(client, issuedA)
        assertEquals(HttpStatusCode.OK, confirmA.status, confirmA.bodyAsText())

        // Резерв уже использован полностью: второе проведение отклоняется,
        // остаток и резерв не меняются.
        val confirmB = confirmIssueResponse(client, issuedB)
        assertEquals(HttpStatusCode.Conflict, confirmB.status)

        val stock = getStock(client, warehouse.id, nomenclature.id)
        assertQty("5", stock[0].physicalQty)
        assertQty("0", stock[0].reservedQty)
    }

    @Test
    fun `confirm replay via idempotency does not move stock twice`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "10")

        val order = createIssue(client, warehouse.id, nomenclature.id, "4")
        val issued = issueOrder(client, pickAll(client, order, cell.id))

        val key = newKey()
        val first = confirmIssueResponse(client, issued, key)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val replay = confirmIssueResponse(client, issued, key)
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertEquals(
            first.bodyAs<ApiResponse<IssueOrderDto>>().data!!.id,
            replay.bodyAs<ApiResponse<IssueOrderDto>>().data!!.id
        )

        assertQty("6", getStock(client, warehouse.id, nomenclature.id)[0].physicalQty)
    }

    @Test
    fun `idempotency key with different body is rejected`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "10")

        val key = newKey()
        val first = createIssueResponse(client, warehouse.id, nomenclature.id, "3", idempotencyKey = key)
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val differentBody = createIssueResponse(client, warehouse.id, nomenclature.id, "4", idempotencyKey = key)
        assertEquals(HttpStatusCode.Conflict, differentBody.status)
    }

    @Test
    fun `concurrent confirms compete for the same free stock and only one succeeds`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "10")

        val issuedA = issueOrder(client, pickAll(client, createIssue(client, warehouse.id, nomenclature.id, "6"), cell.id))
        val issuedB = issueOrder(client, pickAll(client, createIssue(client, warehouse.id, nomenclature.id, "6"), cell.id))

        val firstClient = loginClient("keeper", "password")
        val secondClient = loginClient("keeper", "password")
        val statuses = coroutineScope {
            val first = async { confirmIssueResponse(firstClient, issuedA).status }
            val second = async { confirmIssueResponse(secondClient, issuedB).status }
            listOf(first.await(), second.await())
        }
        assertEquals(1, statuses.count { it == HttpStatusCode.OK }, "statuses: $statuses")
        assertEquals(1, statuses.count { it == HttpStatusCode.Conflict }, "statuses: $statuses")

        assertQty("4", getStock(client, warehouse.id, nomenclature.id)[0].physicalQty)
    }

    @Test
    fun `refuse and cancel follow explicit rules`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "10")

        val issued = issueOrder(client, pickAll(client, createIssue(client, warehouse.id, nomenclature.id, "2"), cell.id))
        val refuseResponse = client.post("/api/wms/issues/${issued.id}/refuse") {
            contentType(ContentType.Application.Json)
            setBody(stringify(RefuseIssueRequest(issued.version, "Получатель отказался", newKey())))
        }
        assertEquals(HttpStatusCode.OK, refuseResponse.status, refuseResponse.bodyAsText())
        val refused = refuseResponse.bodyAs<ApiResponse<IssueOrderDto>>().data!!
        assertEquals(IssueStatus.REFUSED, refused.status)
        assertEquals("Получатель отказался", refused.refusalReason)
        // Отказ не трогает остаток.
        assertQty("10", getStock(client, warehouse.id, nomenclature.id)[0].physicalQty)

        val draft = createIssue(client, warehouse.id, nomenclature.id, "1")
        val cancelResponse = client.post("/api/wms/issues/${draft.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(IssueActionRequest(draft.version, newKey())))
        }
        assertEquals(HttpStatusCode.OK, cancelResponse.status, cancelResponse.bodyAsText())
        assertEquals(IssueStatus.CANCELLED, cancelResponse.bodyAs<ApiResponse<IssueOrderDto>>().data!!.status)

        val cancelAfterIssue = client.post("/api/wms/issues/${issued.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(IssueActionRequest(refused.version, newKey())))
        }
        assertEquals(HttpStatusCode.Conflict, cancelAfterIssue.status)
    }

    @Test
    fun `return goes to quarantine and stays unavailable until inspection`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        createUserWithRole(admin, "inspector", "password", "Inspector User", wmsPermissions() + CreatePermissionRequest("wms", "inspect_returns"))
        val client = loginClient("keeper", "password")
        val inspectorClient = loginClient("inspector", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val quarantine = createLocation(client, warehouse.id, "Q-01", StorageLocationPurpose.QUARANTINE)
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "10")
        val issuedOrder = postIssue(client, warehouse.id, cell.id, nomenclature.id, "5")

        val returnOrder = createReturn(client, issuedOrder, quarantine.id, "5")
        assertEquals(ReturnStatus.DRAFT, returnOrder.status)
        val received = receiveReturn(client, returnOrder)
        assertEquals(ReturnStatus.RECEIVED, received.status)

        // Возврат лежит в карантине: исходная ячейка не восстановилась,
        // карантин недоступен для резерва.
        val stock = getStock(client, warehouse.id, nomenclature.id)
        val cellRow = stock.first { it.locationId == cell.id }
        assertQty("5", cellRow.physicalQty)
        val quarantineRow = stock.first { it.locationId == quarantine.id }
        assertQty("5", quarantineRow.physicalQty)
        assertEquals(StorageLocationPurpose.QUARANTINE, quarantineRow.locationPurpose)

        val reserveFromQuarantine = client.post("/api/wms/reservations") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateReservationRequest(
                        reasonType = ReservationReasonType.OTHER,
                        nomenclatureId = nomenclature.id,
                        warehouseId = warehouse.id,
                        locationId = quarantine.id,
                        quantity = "1"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Conflict, reserveFromQuarantine.status)

        // До решения инспектора остаток в карантине недоступен; после GOOD —
        // размещается в разрешённой ячейке.
        val inspectResponse = inspectorClient.post("/api/wms/returns/${received.id}/lines/${received.lines[0].id}/inspect") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    InspectReturnLineRequest(
                        version = received.version,
                        decision = ReturnInspectionDecision.GOOD,
                        targetLocationId = cell.id,
                        idempotencyKey = newKey()
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, inspectResponse.status, inspectResponse.bodyAsText())
        val inspected = inspectResponse.bodyAs<ApiResponse<ReturnOrderDto>>().data!!
        assertEquals(ReturnStatus.INSPECTED, inspected.status)
        assertEquals(ReturnInspection.GOOD, inspected.lines[0].inspection)
        assertNotNull(inspected.lines[0].inspectedBy)

        val afterInspection = getStock(client, warehouse.id, nomenclature.id)
        assertQty("10", afterInspection.first { it.locationId == cell.id }.physicalQty)
        assertQty("0", afterInspection.first { it.locationId == quarantine.id }.physicalQty)
    }

    @Test
    fun `partial return cannot exceed issued quantity`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val quarantine = createLocation(client, warehouse.id, "Q-01", StorageLocationPurpose.QUARANTINE)
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "10")
        val issuedOrder = postIssue(client, warehouse.id, cell.id, nomenclature.id, "5")

        val first = createReturnResponse(client, issuedOrder, quarantine.id, "3")
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())

        // Уже оформлено 3 из 5: ещё 3 превышают выданное.
        val exceeding = createReturnResponse(client, issuedOrder, quarantine.id, "3")
        assertEquals(HttpStatusCode.Conflict, exceeding.status)

        val fits = createReturnResponse(client, issuedOrder, quarantine.id, "2")
        assertEquals(HttpStatusCode.Created, fits.status, fits.bodyAsText())
    }

    @Test
    fun `defect inspection keeps stock unavailable`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions() + CreatePermissionRequest("wms", "inspect_returns"))
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val quarantine = createLocation(client, warehouse.id, "Q-01", StorageLocationPurpose.QUARANTINE)
        val defect = createLocation(client, warehouse.id, "D-01", StorageLocationPurpose.DEFECT)
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "10")
        val issuedOrder = postIssue(client, warehouse.id, cell.id, nomenclature.id, "4")

        val returnOrder = receiveReturn(client, createReturn(client, issuedOrder, quarantine.id, "4"))
        val inspectResponse = client.post("/api/wms/returns/${returnOrder.id}/lines/${returnOrder.lines[0].id}/inspect") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    InspectReturnLineRequest(
                        version = returnOrder.version,
                        decision = ReturnInspectionDecision.DEFECT,
                        targetLocationId = defect.id,
                        note = "Повреждено",
                        idempotencyKey = newKey()
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, inspectResponse.status, inspectResponse.bodyAsText())
        val inspected = inspectResponse.bodyAs<ApiResponse<ReturnOrderDto>>().data!!
        assertEquals(ReturnInspection.DEFECT, inspected.lines[0].inspection)

        // Брак не доступен обычной выдаче: резерв из зоны DEFECT отклоняется.
        val stock = getStock(client, warehouse.id, nomenclature.id)
        assertQty("4", stock.first { it.locationId == defect.id }.physicalQty)
        assertQty("0", stock.first { it.locationId == quarantine.id }.physicalQty)

        val reserveFromDefect = client.post("/api/wms/reservations") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateReservationRequest(
                        reasonType = ReservationReasonType.OTHER,
                        nomenclatureId = nomenclature.id,
                        warehouseId = warehouse.id,
                        locationId = defect.id,
                        quantity = "1"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Conflict, reserveFromDefect.status)
    }

    @Test
    fun `inspection requires dedicated permission and read rights are enforced`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions() + CreatePermissionRequest("wms", "inspect_returns"))
        createUserWithRole(admin, "poster", "password", "Poster User", wmsPermissions())
        createUserWithRole(admin, "nobody", "password", "No Rights", emptyList())
        val client = loginClient("keeper", "password")
        val posterClient = loginClient("poster", "password")
        val nobodyClient = loginClient("nobody", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val quarantine = createLocation(client, warehouse.id, "Q-01", StorageLocationPurpose.QUARANTINE)
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "5")
        val issuedOrder = postIssue(client, warehouse.id, cell.id, nomenclature.id, "2")
        val returnOrder = receiveReturn(client, createReturn(client, issuedOrder, quarantine.id, "2"))

        val inspectDenied = posterClient.post("/api/wms/returns/${returnOrder.id}/lines/${returnOrder.lines[0].id}/inspect") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    InspectReturnLineRequest(
                        version = returnOrder.version,
                        decision = ReturnInspectionDecision.GOOD,
                        targetLocationId = cell.id,
                        idempotencyKey = newKey()
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Forbidden, inspectDenied.status)

        val readDenied = nobodyClient.get("/api/wms/issues")
        assertEquals(HttpStatusCode.Forbidden, readDenied.status)

        val createDenied = nobodyClient.post("/api/wms/issues") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateIssueOrderRequest(
                        warehouseId = warehouse.id,
                        reasonType = ReservationReasonType.OTHER,
                        lines = listOf(IssueLineInput(1, nomenclature.id, "1")),
                        idempotencyKey = newKey()
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Forbidden, createDenied.status)
    }

    private fun newKey() = UUID.randomUUID().toString()

    private fun wmsPermissions() = listOf(
        CreatePermissionRequest("wms", "manage"),
        CreatePermissionRequest("wms", "post"),
        CreatePermissionRequest("wms", "read"),
        CreatePermissionRequest("wms", "issue_without_reservation")
    )

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
        val loginResponse = adminClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status, loginResponse.bodyAsText())

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

    private suspend fun createLocation(
        client: HttpClient,
        warehouseId: Long,
        code: String,
        purpose: StorageLocationPurpose = StorageLocationPurpose.STORAGE
    ): StorageLocationDto {
        val response = client.post("/api/wms/locations") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateStorageLocationRequest(
                        warehouseId = warehouseId,
                        code = code,
                        name = "Location $code",
                        kind = StorageLocationKind.CELL,
                        purpose = purpose
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

    private suspend fun reserve(
        client: HttpClient,
        warehouseId: Long,
        locationId: Long,
        nomenclatureId: Long,
        quantity: String
    ): ReservationDto {
        val response = client.post("/api/wms/reservations") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateReservationRequest(
                        reasonType = ReservationReasonType.OTHER,
                        nomenclatureId = nomenclatureId,
                        warehouseId = warehouseId,
                        locationId = locationId,
                        quantity = quantity
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ReservationDto>>().data!!
    }

    private suspend fun createIssueResponse(
        client: HttpClient,
        warehouseId: Long,
        nomenclatureId: Long,
        quantity: String,
        reservationId: Long? = null,
        idempotencyKey: String = newKey()
    ): HttpResponse {
        return client.post("/api/wms/issues") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateIssueOrderRequest(
                        warehouseId = warehouseId,
                        reasonType = ReservationReasonType.OTHER,
                        lines = listOf(
                            IssueLineInput(
                                lineNo = 1,
                                nomenclatureId = nomenclatureId,
                                requestedQuantity = quantity,
                                reservationId = reservationId
                            )
                        ),
                        idempotencyKey = idempotencyKey
                    )
                )
            )
        }
    }

    private suspend fun createIssue(
        client: HttpClient,
        warehouseId: Long,
        nomenclatureId: Long,
        quantity: String,
        reservationId: Long? = null
    ): IssueOrderDto {
        val response = createIssueResponse(client, warehouseId, nomenclatureId, quantity, reservationId)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<IssueOrderDto>>().data!!
    }

    private suspend fun pickAll(client: HttpClient, order: IssueOrderDto, locationId: Long): IssueOrderDto {
        val response = client.post("/api/wms/issues/${order.id}/picks") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    PickIssueRequest(
                        version = order.version,
                        picks = order.lines.map {
                            IssuePickInput(lineId = it.id, locationId = locationId, quantity = it.requestedQuantity)
                        },
                        idempotencyKey = newKey()
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<IssueOrderDto>>().data!!
    }

    private suspend fun issueOrder(client: HttpClient, order: IssueOrderDto): IssueOrderDto {
        val response = client.post("/api/wms/issues/${order.id}/issue") {
            contentType(ContentType.Application.Json)
            setBody(stringify(IssueActionRequest(order.version, newKey())))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<IssueOrderDto>>().data!!
    }

    private suspend fun confirmIssueResponse(
        client: HttpClient,
        order: IssueOrderDto,
        idempotencyKey: String = newKey()
    ): HttpResponse {
        return client.post("/api/wms/issues/${order.id}/confirm") {
            contentType(ContentType.Application.Json)
            setBody(stringify(IssueActionRequest(order.version, idempotencyKey)))
        }
    }

    private suspend fun confirmIssue(client: HttpClient, order: IssueOrderDto): IssueOrderDto {
        val response = confirmIssueResponse(client, order)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<IssueOrderDto>>().data!!
    }

    // Полный цикл выдачи без резерва: create -> pick -> issue -> confirm.
    private suspend fun postIssue(
        client: HttpClient,
        warehouseId: Long,
        locationId: Long,
        nomenclatureId: Long,
        quantity: String
    ): IssueOrderDto {
        val order = createIssue(client, warehouseId, nomenclatureId, quantity)
        return confirmIssue(client, issueOrder(client, pickAll(client, order, locationId)))
    }

    private suspend fun createReturnResponse(
        client: HttpClient,
        issuedOrder: IssueOrderDto,
        quarantineLocationId: Long,
        quantity: String
    ): HttpResponse {
        val line = issuedOrder.lines[0]
        return client.post("/api/wms/returns") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateReturnOrderRequest(
                        issueOrderId = issuedOrder.id,
                        quarantineLocationId = quarantineLocationId,
                        reason = "Излишек",
                        lines = listOf(ReturnLineInput(issueLineId = line.id, quantity = quantity)),
                        idempotencyKey = newKey()
                    )
                )
            )
        }
    }

    private suspend fun createReturn(
        client: HttpClient,
        issuedOrder: IssueOrderDto,
        quarantineLocationId: Long,
        quantity: String
    ): ReturnOrderDto {
        val response = createReturnResponse(client, issuedOrder, quarantineLocationId, quantity)
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ReturnOrderDto>>().data!!
    }

    private suspend fun receiveReturn(client: HttpClient, returnOrder: ReturnOrderDto): ReturnOrderDto {
        val response = client.post("/api/wms/returns/${returnOrder.id}/receive") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ReturnActionRequest(returnOrder.version, newKey())))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ReturnOrderDto>>().data!!
    }

    private suspend fun getStock(client: HttpClient, warehouseId: Long, nomenclatureId: Long): List<StockBalanceDto> {
        val response = client.get("/api/wms/stock?warehouseId=$warehouseId&nomenclatureId=$nomenclatureId")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<List<StockBalanceDto>>>().data!!
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
                  wms_issue_idempotency_keys, return_lines, return_orders,
                  issue_picks, issue_lines, issue_orders,
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
        fun tearDown() {
            TestDatabase.destroy()
        }
    }
}
