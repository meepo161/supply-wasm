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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
class WmsRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
        truncateWms()
    }

    @Test
    fun `receipt posts and stock becomes available`() = testApp {
        val admin = jsonClient()
        val keeper = createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val location = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")

        val receipt = createReceipt(client, warehouse.id, location.id, nomenclature.id, expected = "10")
        assertEquals(ReceiptStatus.DRAFT, receipt.status)
        assertTrue(receipt.number.startsWith("RCP-"))
        assertEquals(keeper.id, receipt.createdBy)

        val postResponse = client.post("/api/wms/receipts/${receipt.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PostReceiptRequest(receipt.version)))
        }
        assertEquals(HttpStatusCode.OK, postResponse.status, postResponse.bodyAsText())
        val posted = postResponse.bodyAs<ApiResponse<ReceiptDto>>().data!!
        assertEquals(ReceiptStatus.POSTED, posted.status)
        assertEquals(keeper.id, posted.postedBy)
        assertNotNull(posted.postedAt)

        val stock = getStock(client, warehouse.id, nomenclature.id)
        assertEquals(1, stock.size)
        assertQty("10", stock[0].physicalQty)
        assertQty("0", stock[0].reservedQty)
        assertQty("10", stock[0].availableQty)
        assertEquals(location.id, stock[0].locationId)
    }

    @Test
    fun `nomenclature details contain stock lots and movement journal`() = testApp {
        val admin = jsonClient()
        val keeper = createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        createUserWithRole(
            admin, "reader", "password", "Reader User",
            listOf(CreatePermissionRequest("wms", "read"))
        )
        createUserWithRole(admin, "nobody", "password", "No Rights", emptyList())
        val client = loginClient("keeper", "password")
        val warehouse = createWarehouse(client, "WH-1")
        val location = createLocation(client, warehouse.id, "A-01")
        val nomenclatureResponse = client.post("/api/wms/nomenclature") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateNomenclatureRequest(
                code = "MAT-LOT",
                name = "Lot material",
                type = NomenclatureType.MATERIAL,
                unit = "pcs",
                lotTracked = true
            )))
        }
        val nomenclature = nomenclatureResponse.bodyAs<ApiResponse<NomenclatureDto>>().data!!
        val receiptResponse = client.post("/api/wms/receipts") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateReceiptRequest(
                sourceType = ReceiptSourceType.INITIAL_STOCK,
                warehouseId = warehouse.id,
                lines = listOf(ReceiptLineInput(
                    lineNo = 1,
                    nomenclatureId = nomenclature.id,
                    expectedQuantity = "5",
                    lotNumber = "LOT-2026-01",
                    locationId = location.id
                ))
            )))
        }
        val receipt = receiptResponse.bodyAs<ApiResponse<ReceiptDto>>().data!!
        val postResponse = client.post("/api/wms/receipts/${receipt.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PostReceiptRequest(receipt.version)))
        }
        assertEquals(HttpStatusCode.OK, postResponse.status, postResponse.bodyAsText())

        val readerClient = loginClient("reader", "password")
        val response = readerClient.get("/api/wms/nomenclature/${nomenclature.id}/details")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val details = response.bodyAs<ApiResponse<NomenclatureDetailsDto>>().data!!
        assertEquals(nomenclature.id, details.item.id)
        assertEquals(1, details.stock.size)
        assertQty("5", details.physicalQty)
        assertQty("0", details.reservedQty)
        assertQty("5", details.availableQty)
        assertEquals("A-01", details.stock[0].locationCode)
        assertQty("5", details.stock[0].physicalQty)
        assertEquals(listOf("LOT-2026-01"), details.lots.map { it.lotNumber })
        assertEquals(1, details.movements.size)
        assertEquals(StockMovementType.RECEIPT, details.movements[0].type)
        assertEquals("A-01", details.movements[0].toLocationCode)
        assertEquals(keeper.id, details.movements[0].createdBy)
        assertEquals("Keeper User", details.movements[0].createdByName)
        assertEquals(receipt.id, details.movements[0].documentId)

        val nobodyClient = loginClient("nobody", "password")
        assertEquals(
            HttpStatusCode.Forbidden,
            nobodyClient.get("/api/wms/nomenclature/${nomenclature.id}/details").status
        )
        assertEquals(
            HttpStatusCode.NotFound,
            readerClient.get("/api/wms/nomenclature/999999/details").status
        )
    }

    @Test
    fun `partial receipt posts received quantity and keeps expected`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val location = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")

        val receipt = createReceipt(client, warehouse.id, location.id, nomenclature.id, expected = "10", received = "4")
        assertEquals(1, receipt.lines.size)
        assertQty("10", receipt.lines[0].expectedQuantity)
        assertQty("4", receipt.lines[0].receivedQuantity)

        val postResponse = client.post("/api/wms/receipts/${receipt.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PostReceiptRequest(receipt.version)))
        }
        assertEquals(HttpStatusCode.OK, postResponse.status, postResponse.bodyAsText())

        val stock = getStock(client, warehouse.id, nomenclature.id)
        assertEquals(1, stock.size)
        assertQty("4", stock[0].physicalQty)
        assertQty("4", stock[0].availableQty)

        val reread = client.get("/api/wms/receipts/${receipt.id}")
            .bodyAs<ApiResponse<ReceiptDto>>().data!!
        assertQty("10", reread.lines[0].expectedQuantity)
        assertQty("4", reread.lines[0].receivedQuantity)
    }

    @Test
    fun `second post is rejected and stock is not duplicated`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val location = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        val receipt = createReceipt(client, warehouse.id, location.id, nomenclature.id, expected = "10")

        val first = client.post("/api/wms/receipts/${receipt.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PostReceiptRequest(receipt.version)))
        }
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val posted = first.bodyAs<ApiResponse<ReceiptDto>>().data!!

        val second = client.post("/api/wms/receipts/${receipt.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PostReceiptRequest(posted.version)))
        }
        assertEquals(HttpStatusCode.Conflict, second.status)

        val stock = getStock(client, warehouse.id, nomenclature.id)
        assertEquals(1, stock.size)
        assertQty("10", stock[0].physicalQty)
    }

    @Test
    fun `reservation for project reduces available and release restores it`() = testApp {
        val admin = jsonClient()
        val keeper = createUserWithRole(
            admin, "keeper", "password", "Keeper User",
            wmsPermissions() + CreatePermissionRequest("projects", "create")
        )
        val client = loginClient("keeper", "password")

        val projectResponse = client.post("/api/projects") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateProjectRequest(
                        name = "Reserve Project",
                        projectType = ProjectType.ORDER_DEVELOPMENT,
                        ownerId = keeper.id
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, projectResponse.status, projectResponse.bodyAsText())
        val project = projectResponse.bodyAs<ApiResponse<ProjectDto>>().data!!

        val warehouse = createWarehouse(client, "WH-1")
        val location = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, location.id, nomenclature.id, "10")

        val reserveResponse = client.post("/api/wms/reservations") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateReservationRequest(
                        reasonType = ReservationReasonType.PROJECT,
                        projectId = project.id,
                        nomenclatureId = nomenclature.id,
                        warehouseId = warehouse.id,
                        locationId = location.id,
                        quantity = "6"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, reserveResponse.status, reserveResponse.bodyAsText())
        val reservation = reserveResponse.bodyAs<ApiResponse<ReservationDto>>().data!!
        assertEquals(ReservationStatus.ACTIVE, reservation.status)
        assertTrue(reservation.number.startsWith("RSV-"))
        assertEquals(project.id, reservation.projectId)
        assertEquals(keeper.id, reservation.ownerId)
        assertEquals(1, reservation.history.size)
        assertEquals(ReservationHistoryAction.CREATED, reservation.history[0].action)

        val afterReserve = getStock(client, warehouse.id, nomenclature.id)
        assertQty("10", afterReserve[0].physicalQty)
        assertQty("6", afterReserve[0].reservedQty)
        assertQty("4", afterReserve[0].availableQty)

        val releaseResponse = client.post("/api/wms/reservations/${reservation.id}/release") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ReleaseReservationRequest(reservation.version, "Project cancelled")))
        }
        assertEquals(HttpStatusCode.OK, releaseResponse.status, releaseResponse.bodyAsText())
        val released = releaseResponse.bodyAs<ApiResponse<ReservationDto>>().data!!
        assertEquals(ReservationStatus.RELEASED, released.status)
        assertEquals(2, released.history.size)
        assertEquals(ReservationHistoryAction.RELEASED, released.history[1].action)
        assertQty("6", released.history[1].quantityBefore!!)
        assertQty("0", released.history[1].quantityAfter!!)

        val afterRelease = getStock(client, warehouse.id, nomenclature.id)
        assertQty("10", afterRelease[0].physicalQty)
        assertQty("0", afterRelease[0].reservedQty)
        assertQty("10", afterRelease[0].availableQty)
    }

    @Test
    fun `reservation above available is rejected`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val location = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, location.id, nomenclature.id, "10")

        val response = reserveOther(client, warehouse.id, location.id, nomenclature.id, "11")
        assertEquals(HttpStatusCode.Conflict, response.status)

        val stock = getStock(client, warehouse.id, nomenclature.id)
        assertQty("10", stock[0].availableQty)
        assertQty("0", stock[0].reservedQty)
    }

    @Test
    fun `quarantine location cannot be reserved`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val quarantine = createLocation(client, warehouse.id, "Q-01", StorageLocationPurpose.QUARANTINE)
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, quarantine.id, nomenclature.id, "5")

        val response = reserveOther(client, warehouse.id, quarantine.id, nomenclature.id, "1")
        assertEquals(HttpStatusCode.Conflict, response.status)

        val stock = getStock(client, warehouse.id, nomenclature.id)
        assertQty("5", stock[0].physicalQty)
        assertQty("0", stock[0].reservedQty)
    }

    @Test
    fun `archived nomenclature cannot be received or reserved`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val location = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, location.id, nomenclature.id, "10")

        val archiveResponse = client.post("/api/wms/nomenclature/${nomenclature.id}/archive")
        assertEquals(HttpStatusCode.OK, archiveResponse.status, archiveResponse.bodyAsText())
        assertTrue(archiveResponse.bodyAs<ApiResponse<NomenclatureDto>>().data!!.archived)

        val receiptResponse = client.post("/api/wms/receipts") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateReceiptRequest(
                        sourceType = ReceiptSourceType.OTHER,
                        warehouseId = warehouse.id,
                        lines = listOf(
                            ReceiptLineInput(
                                lineNo = 1,
                                nomenclatureId = nomenclature.id,
                                expectedQuantity = "1",
                                locationId = location.id
                            )
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Conflict, receiptResponse.status)

        val reserveResponse = reserveOther(client, warehouse.id, location.id, nomenclature.id, "1")
        assertEquals(HttpStatusCode.Conflict, reserveResponse.status)
    }

    @Test
    fun `receipt with unknown nomenclature or foreign location is rejected`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val otherWarehouse = createWarehouse(client, "WH-2")
        val location = createLocation(client, warehouse.id, "A-01")
        val foreignLocation = createLocation(client, otherWarehouse.id, "B-01")
        val nomenclature = createNomenclature(client, "MAT-1")

        val unknownNomenclature = client.post("/api/wms/receipts") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateReceiptRequest(
                        sourceType = ReceiptSourceType.INITIAL_STOCK,
                        warehouseId = warehouse.id,
                        lines = listOf(
                            ReceiptLineInput(lineNo = 1, nomenclatureId = 999999L, expectedQuantity = "1", locationId = location.id)
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.NotFound, unknownNomenclature.status)

        val unknownLocation = client.post("/api/wms/receipts") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateReceiptRequest(
                        sourceType = ReceiptSourceType.INITIAL_STOCK,
                        warehouseId = warehouse.id,
                        lines = listOf(
                            ReceiptLineInput(lineNo = 1, nomenclatureId = nomenclature.id, expectedQuantity = "1", locationId = 999999L)
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.NotFound, unknownLocation.status)

        val foreign = client.post("/api/wms/receipts") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateReceiptRequest(
                        sourceType = ReceiptSourceType.INITIAL_STOCK,
                        warehouseId = warehouse.id,
                        lines = listOf(
                            ReceiptLineInput(lineNo = 1, nomenclatureId = nomenclature.id, expectedQuantity = "1", locationId = foreignLocation.id)
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, foreign.status)
    }

    @Test
    fun `release with wrong version or repeated release is rejected`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val location = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, location.id, nomenclature.id, "10")
        val reserveResponse = reserveOther(client, warehouse.id, location.id, nomenclature.id, "5")
        assertEquals(HttpStatusCode.Created, reserveResponse.status, reserveResponse.bodyAsText())
        val reservation = reserveResponse.bodyAs<ApiResponse<ReservationDto>>().data!!

        val wrongVersion = client.post("/api/wms/reservations/${reservation.id}/release") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ReleaseReservationRequest(reservation.version + 5)))
        }
        assertEquals(HttpStatusCode.Conflict, wrongVersion.status)

        val release = client.post("/api/wms/reservations/${reservation.id}/release") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ReleaseReservationRequest(reservation.version)))
        }
        assertEquals(HttpStatusCode.OK, release.status, release.bodyAsText())
        val released = release.bodyAs<ApiResponse<ReservationDto>>().data!!

        val repeated = client.post("/api/wms/reservations/${reservation.id}/release") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ReleaseReservationRequest(released.version)))
        }
        assertEquals(HttpStatusCode.Conflict, repeated.status)

        val stock = getStock(client, warehouse.id, nomenclature.id)
        assertQty("10", stock[0].availableQty)
    }

    @Test
    fun `concurrent reservations compete for the same stock and only one succeeds`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val location = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, location.id, nomenclature.id, "10")

        val firstClient = loginClient("keeper", "password")
        val secondClient = loginClient("keeper", "password")
        val statuses = coroutineScope {
            val first = async { reserveOther(firstClient, warehouse.id, location.id, nomenclature.id, "6").status }
            val second = async { reserveOther(secondClient, warehouse.id, location.id, nomenclature.id, "6").status }
            listOf(first.await(), second.await())
        }
        assertEquals(1, statuses.count { it == HttpStatusCode.Created }, "statuses: $statuses")
        assertEquals(1, statuses.count { it == HttpStatusCode.Conflict }, "statuses: $statuses")

        val stock = getStock(client, warehouse.id, nomenclature.id)
        assertQty("10", stock[0].physicalQty)
        assertQty("6", stock[0].reservedQty)
        assertQty("4", stock[0].availableQty)
    }

    @Test
    fun `wms permissions are enforced on server`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "manager", "password", "Manager User", wmsPermissions())
        createUserWithRole(
            admin, "reader", "password", "Reader User",
            listOf(CreatePermissionRequest("wms", "read"))
        )
        createUserWithRole(admin, "nobody", "password", "No Rights", emptyList())

        val managerClient = loginClient("manager", "password")
        val readerClient = loginClient("reader", "password")
        val nobodyClient = loginClient("nobody", "password")

        val warehouse = createWarehouse(managerClient, "WH-1")
        val location = createLocation(managerClient, warehouse.id, "A-01")
        val nomenclature = createNomenclature(managerClient, "MAT-1")
        postInitialStock(managerClient, warehouse.id, location.id, nomenclature.id, "3")

        val manageDenied = readerClient.post("/api/wms/warehouses") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateWarehouseRequest("WH-X", "Forbidden Warehouse")))
        }
        assertEquals(HttpStatusCode.Forbidden, manageDenied.status)

        val postDenied = readerClient.post("/api/wms/receipts") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateReceiptRequest(
                        sourceType = ReceiptSourceType.OTHER,
                        warehouseId = warehouse.id,
                        lines = listOf(
                            ReceiptLineInput(lineNo = 1, nomenclatureId = nomenclature.id, expectedQuantity = "1", locationId = location.id)
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Forbidden, postDenied.status)

        val readAllowed = readerClient.get("/api/wms/stock?warehouseId=${warehouse.id}")
        assertEquals(HttpStatusCode.OK, readAllowed.status)

        val readDenied = nobodyClient.get("/api/wms/stock")
        assertEquals(HttpStatusCode.Forbidden, readDenied.status)
    }

    @Test
    fun `receipts list returns created receipts with lines ordered by id desc`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val location = createLocation(client, warehouse.id, "A-01")
        val nomenclature = createNomenclature(client, "MAT-1")

        val first = createReceipt(client, warehouse.id, location.id, nomenclature.id, expected = "10")
        val second = createReceipt(client, warehouse.id, location.id, nomenclature.id, expected = "5", received = "4")

        val response = client.get("/api/wms/receipts")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val list = response.bodyAs<ApiResponse<List<ReceiptDto>>>().data!!
        assertEquals(listOf(second.id, first.id), list.map { it.id })
        assertEquals(1, list[0].lines.size)
        assertQty("5", list[0].lines[0].expectedQuantity)
        assertQty("4", list[0].lines[0].receivedQuantity)
    }

    @Test
    fun `receipts list filters by warehouse and status`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse1 = createWarehouse(client, "WH-1")
        val warehouse2 = createWarehouse(client, "WH-2")
        val location1 = createLocation(client, warehouse1.id, "A-01")
        val location2 = createLocation(client, warehouse2.id, "B-01")
        val nomenclature = createNomenclature(client, "MAT-1")

        val postedReceipt = createReceipt(client, warehouse1.id, location1.id, nomenclature.id, expected = "10")
        val postResponse = client.post("/api/wms/receipts/${postedReceipt.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PostReceiptRequest(postedReceipt.version)))
        }
        assertEquals(HttpStatusCode.OK, postResponse.status, postResponse.bodyAsText())
        val draftReceipt = createReceipt(client, warehouse2.id, location2.id, nomenclature.id, expected = "3")

        val byWarehouse = client.get("/api/wms/receipts?warehouseId=${warehouse1.id}")
            .bodyAs<ApiResponse<List<ReceiptDto>>>().data!!
        assertEquals(listOf(postedReceipt.id), byWarehouse.map { it.id })

        val posted = client.get("/api/wms/receipts?status=POSTED")
            .bodyAs<ApiResponse<List<ReceiptDto>>>().data!!
        assertEquals(listOf(postedReceipt.id), posted.map { it.id })

        val draft = client.get("/api/wms/receipts?status=DRAFT")
            .bodyAs<ApiResponse<List<ReceiptDto>>>().data!!
        assertEquals(listOf(draftReceipt.id), draft.map { it.id })

        val combined = client.get("/api/wms/receipts?warehouseId=${warehouse2.id}&status=POSTED")
            .bodyAs<ApiResponse<List<ReceiptDto>>>().data!!
        assertTrue(combined.isEmpty())

        val invalidStatus = client.get("/api/wms/receipts?status=UNKNOWN")
        assertEquals(HttpStatusCode.BadRequest, invalidStatus.status)
    }

    @Test
    fun `receipts list requires wms read permission`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        createUserWithRole(
            admin, "reader", "password", "Reader User",
            listOf(CreatePermissionRequest("wms", "read"))
        )
        createUserWithRole(admin, "nobody", "password", "No Rights", emptyList())

        val keeperClient = loginClient("keeper", "password")
        val readerClient = loginClient("reader", "password")
        val nobodyClient = loginClient("nobody", "password")

        val warehouse = createWarehouse(keeperClient, "WH-1")
        val location = createLocation(keeperClient, warehouse.id, "A-01")
        val nomenclature = createNomenclature(keeperClient, "MAT-1")
        createReceipt(keeperClient, warehouse.id, location.id, nomenclature.id, expected = "1")

        val readAllowed = readerClient.get("/api/wms/receipts")
        assertEquals(HttpStatusCode.OK, readAllowed.status, readAllowed.bodyAsText())
        assertEquals(1, readAllowed.bodyAs<ApiResponse<List<ReceiptDto>>>().data!!.size)

        val readDenied = nobodyClient.get("/api/wms/receipts")
        assertEquals(HttpStatusCode.Forbidden, readDenied.status)
    }

    private fun wmsPermissions() = listOf(
        CreatePermissionRequest("wms", "manage"),
        CreatePermissionRequest("wms", "post"),
        CreatePermissionRequest("wms", "read")
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
                        unit = "pcs",
                        barcodes = listOf(CreateNomenclatureBarcodeRequest("BC-$code"))
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<NomenclatureDto>>().data!!
    }

    private suspend fun createReceipt(
        client: HttpClient,
        warehouseId: Long,
        locationId: Long,
        nomenclatureId: Long,
        expected: String,
        received: String? = null
    ): ReceiptDto {
        val response = client.post("/api/wms/receipts") {
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
                                expectedQuantity = expected,
                                receivedQuantity = received,
                                locationId = locationId
                            )
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ReceiptDto>>().data!!
    }

    private suspend fun postInitialStock(
        client: HttpClient,
        warehouseId: Long,
        locationId: Long,
        nomenclatureId: Long,
        quantity: String
    ) {
        val receipt = createReceipt(client, warehouseId, locationId, nomenclatureId, expected = quantity)
        val response = client.post("/api/wms/receipts/${receipt.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PostReceiptRequest(receipt.version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun reserveOther(
        client: HttpClient,
        warehouseId: Long,
        locationId: Long,
        nomenclatureId: Long,
        quantity: String
    ): HttpResponse {
        return client.post("/api/wms/reservations") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateReservationRequest(
                        reasonType = ReservationReasonType.OTHER,
                        reasonNote = "Test reservation",
                        nomenclatureId = nomenclatureId,
                        warehouseId = warehouseId,
                        locationId = locationId,
                        quantity = quantity
                    )
                )
            )
        }
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
                          url = "jdbc:postgresql://localhost:5433/erp_test"
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
        private const val TEST_URL = "jdbc:postgresql://localhost:5433/erp_test"
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
