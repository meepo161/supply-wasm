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
import org.koin.ktor.ext.inject
import org.postgresql.ds.PGSimpleDataSource
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WmsTransferRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
        truncateWms()
    }

    @Test
    fun `internal transfer moves free stock atomically`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val from = createLocation(client, warehouse.id, "A-01")
        val to = createLocation(client, warehouse.id, "A-02")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, from.id, nomenclature.id, "10")

        val response = createInternalTransfer(client, warehouse.id, from.id, to.id, nomenclature.id, "4")
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val transfer = response.bodyAs<ApiResponse<TransferDto>>().data!!
        assertEquals(TransferStatus.RECEIVED, transfer.status)
        assertTrue(transfer.number.startsWith("TRF-"))
        assertQty("4", transfer.quantity)
        assertQty("4", transfer.receivedQuantity!!)
        assertEquals(2, transfer.events.size)
        assertEquals(TransferEventAction.CREATED, transfer.events[0].action)
        assertEquals(TransferEventAction.RECEIVED, transfer.events[1].action)

        val stock = getStock(client, warehouse.id, nomenclature.id)
        assertEquals(2, stock.size)
        val fromRow = stock.first { it.locationId == from.id }
        val toRow = stock.first { it.locationId == to.id }
        assertQty("6", fromRow.physicalQty)
        assertQty("6", fromRow.availableQty)
        assertQty("4", toRow.physicalQty)
        assertQty("4", toRow.availableQty)
    }

    @Test
    fun `internal transfer is limited to free stock`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val from = createLocation(client, warehouse.id, "A-01")
        val to = createLocation(client, warehouse.id, "A-02")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, from.id, nomenclature.id, "10")

        val reserveResponse = client.post("/api/wms/reservations") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateReservationRequest(
                        reasonType = ReservationReasonType.OTHER,
                        nomenclatureId = nomenclature.id,
                        warehouseId = warehouse.id,
                        locationId = from.id,
                        quantity = "6"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, reserveResponse.status, reserveResponse.bodyAsText())

        // Доступно 4 из 10: перемещение 5 отклоняется, 4 проходит.
        val tooMuch = createInternalTransfer(client, warehouse.id, from.id, to.id, nomenclature.id, "5")
        assertEquals(HttpStatusCode.Conflict, tooMuch.status)

        val fits = createInternalTransfer(client, warehouse.id, from.id, to.id, nomenclature.id, "4")
        assertEquals(HttpStatusCode.Created, fits.status, fits.bodyAsText())

        val fromRow = getStock(client, warehouse.id, nomenclature.id).first { it.locationId == from.id }
        assertQty("6", fromRow.physicalQty)
        assertQty("6", fromRow.reservedQty)
        assertQty("0", fromRow.availableQty)
    }

    @Test
    fun `quarantine and defect locations are rejected for transfers`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val cell = createLocation(client, warehouse.id, "A-01")
        val quarantine = createLocation(client, warehouse.id, "Q-01", StorageLocationPurpose.QUARANTINE)
        val defect = createLocation(client, warehouse.id, "D-01", StorageLocationPurpose.DEFECT)
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, cell.id, nomenclature.id, "10")
        postInitialStock(client, warehouse.id, quarantine.id, nomenclature.id, "5")

        val fromQuarantine = createInternalTransfer(client, warehouse.id, quarantine.id, cell.id, nomenclature.id, "1")
        assertEquals(HttpStatusCode.Conflict, fromQuarantine.status)

        val toDefect = createInternalTransfer(client, warehouse.id, cell.id, defect.id, nomenclature.id, "1")
        assertEquals(HttpStatusCode.Conflict, toDefect.status)
    }

    @Test
    fun `inter warehouse lifecycle keeps single availability`() = testApp {
        val admin = jsonClient()
        val keeper = createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val fromWarehouse = createWarehouse(client, "WH-1")
        val toWarehouse = createWarehouse(client, "WH-2")
        val fromCell = createLocation(client, fromWarehouse.id, "A-01")
        val toCell = createLocation(client, toWarehouse.id, "B-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, fromWarehouse.id, fromCell.id, nomenclature.id, "10")

        val createResponse = createInterTransfer(
            client, fromWarehouse.id, fromCell.id, toWarehouse.id, toCell.id, nomenclature.id, "10"
        )
        assertEquals(HttpStatusCode.Created, createResponse.status, createResponse.bodyAsText())
        val created = createResponse.bodyAs<ApiResponse<TransferDto>>().data!!
        assertEquals(TransferStatus.CREATED, created.status)

        // До отгрузки остаток не тронут.
        val beforeShip = getStock(client, fromWarehouse.id, nomenclature.id)
        assertQty("10", beforeShip.first { it.locationId == fromCell.id }.availableQty)

        val shipResponse = client.post("/api/wms/transfers/${created.id}/ship") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ShipTransferRequest(created.version, newKey())))
        }
        assertEquals(HttpStatusCode.OK, shipResponse.status, shipResponse.bodyAsText())
        val shipped = shipResponse.bodyAs<ApiResponse<TransferDto>>().data!!
        assertEquals(TransferStatus.SHIPPED, shipped.status)
        assertEquals(keeper.id, shipped.shippedBy)
        assertNotNull(shipped.shippedAt)
        assertNotNull(shipped.transitLocationId)

        // Нет двойной доступности: на исходном складе свободно 0, на целевом
        // складу остатка нет вовсе; количество лежит в транзитной зоне TEMPORARY.
        val fromStock = getStock(client, fromWarehouse.id, nomenclature.id)
        val transitRow = fromStock.first { it.locationPurpose == StorageLocationPurpose.TEMPORARY }
        assertQty("10", transitRow.physicalQty)
        assertQty("0", fromStock.first { it.locationId == fromCell.id }.availableQty)
        assertTrue(getStock(client, toWarehouse.id, nomenclature.id).isEmpty())

        val transitResponse = client.post("/api/wms/transfers/${created.id}/in-transit") {
            contentType(ContentType.Application.Json)
            setBody(stringify(MarkTransferInTransitRequest(shipped.version, newKey())))
        }
        assertEquals(HttpStatusCode.OK, transitResponse.status, transitResponse.bodyAsText())
        val inTransit = transitResponse.bodyAs<ApiResponse<TransferDto>>().data!!
        assertEquals(TransferStatus.IN_TRANSIT, inTransit.status)

        val receiveResponse = client.post("/api/wms/transfers/${created.id}/receive") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ReceiveTransferRequest(inTransit.version, "10", idempotencyKey = newKey())))
        }
        assertEquals(HttpStatusCode.OK, receiveResponse.status, receiveResponse.bodyAsText())
        val received = receiveResponse.bodyAs<ApiResponse<TransferDto>>().data!!
        assertEquals(TransferStatus.RECEIVED, received.status)
        assertQty("10", received.receivedQuantity!!)

        val toStock = getStock(client, toWarehouse.id, nomenclature.id)
        assertEquals(1, toStock.size)
        assertQty("10", toStock[0].physicalQty)
        assertQty("10", toStock[0].availableQty)
        val afterTransit = getStock(client, fromWarehouse.id, nomenclature.id)
        assertQty("0", afterTransit.first { it.locationPurpose == StorageLocationPurpose.TEMPORARY }.physicalQty)
    }

    @Test
    fun `partial receive records discrepancy and keeps remainder in transit`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val fromWarehouse = createWarehouse(client, "WH-1")
        val toWarehouse = createWarehouse(client, "WH-2")
        val fromCell = createLocation(client, fromWarehouse.id, "A-01")
        val toCell = createLocation(client, toWarehouse.id, "B-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, fromWarehouse.id, fromCell.id, nomenclature.id, "10")
        val transfer = shipInTransit(client, fromWarehouse.id, fromCell.id, toWarehouse.id, toCell.id, nomenclature.id, "10")

        val receiveResponse = client.post("/api/wms/transfers/${transfer.id}/receive") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ReceiveTransferRequest(transfer.version, "4", "Недостача 6", idempotencyKey = newKey())))
        }
        assertEquals(HttpStatusCode.OK, receiveResponse.status, receiveResponse.bodyAsText())
        val received = receiveResponse.bodyAs<ApiResponse<TransferDto>>().data!!
        assertEquals(TransferStatus.DISCREPANCY, received.status)
        assertQty("4", received.receivedQuantity!!)
        assertQty("6", received.discrepancyQuantity!!)
        val discrepancyEvent = received.events.last()
        assertEquals(TransferEventAction.DISCREPANCY, discrepancyEvent.action)
        assertQty("4", discrepancyEvent.quantity!!)
        assertQty("6", discrepancyEvent.discrepancyQuantity!!)

        val toStock = getStock(client, toWarehouse.id, nomenclature.id)
        assertQty("4", toStock[0].physicalQty)
        val transitRow = getStock(client, fromWarehouse.id, nomenclature.id)
            .first { it.locationPurpose == StorageLocationPurpose.TEMPORARY }
        assertQty("6", transitRow.physicalQty)
    }

    @Test
    fun `receive above shipped quantity is rejected`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val fromWarehouse = createWarehouse(client, "WH-1")
        val toWarehouse = createWarehouse(client, "WH-2")
        val fromCell = createLocation(client, fromWarehouse.id, "A-01")
        val toCell = createLocation(client, toWarehouse.id, "B-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, fromWarehouse.id, fromCell.id, nomenclature.id, "10")
        val transfer = shipInTransit(client, fromWarehouse.id, fromCell.id, toWarehouse.id, toCell.id, nomenclature.id, "5")

        val response = client.post("/api/wms/transfers/${transfer.id}/receive") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ReceiveTransferRequest(transfer.version, "6", idempotencyKey = newKey())))
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)

        val reread = client.get("/api/wms/transfers/${transfer.id}")
            .bodyAs<ApiResponse<TransferDto>>().data!!
        assertEquals(TransferStatus.IN_TRANSIT, reread.status)
    }

    @Test
    fun `cancel is allowed only before shipping`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val fromWarehouse = createWarehouse(client, "WH-1")
        val toWarehouse = createWarehouse(client, "WH-2")
        val fromCell = createLocation(client, fromWarehouse.id, "A-01")
        val toCell = createLocation(client, toWarehouse.id, "B-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, fromWarehouse.id, fromCell.id, nomenclature.id, "10")

        val created = createInterTransfer(client, fromWarehouse.id, fromCell.id, toWarehouse.id, toCell.id, nomenclature.id, "3")
            .bodyAs<ApiResponse<TransferDto>>().data!!
        val cancelResponse = client.post("/api/wms/transfers/${created.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CancelTransferRequest(created.version, "Передумали", newKey())))
        }
        assertEquals(HttpStatusCode.OK, cancelResponse.status, cancelResponse.bodyAsText())
        val cancelled = cancelResponse.bodyAs<ApiResponse<TransferDto>>().data!!
        assertEquals(TransferStatus.CANCELLED, cancelled.status)
        assertEquals("Передумали", cancelled.cancelReason)
        // Отмена до отправки не трогает остаток.
        assertQty("10", getStock(client, fromWarehouse.id, nomenclature.id)
            .first { it.locationId == fromCell.id }.availableQty)

        val shippedTransfer = shipInTransit(client, fromWarehouse.id, fromCell.id, toWarehouse.id, toCell.id, nomenclature.id, "2")
        val lateCancel = client.post("/api/wms/transfers/${shippedTransfer.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CancelTransferRequest(shippedTransfer.version, "Поздно", newKey())))
        }
        assertEquals(HttpStatusCode.Conflict, lateCancel.status)
    }

    @Test
    fun `idempotent replay returns previous result without new movement`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val from = createLocation(client, warehouse.id, "A-01")
        val to = createLocation(client, warehouse.id, "A-02")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, from.id, nomenclature.id, "10")

        val key = newKey()
        val first = createInternalTransfer(client, warehouse.id, from.id, to.id, nomenclature.id, "4", key)
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())
        val firstDto = first.bodyAs<ApiResponse<TransferDto>>().data!!

        val replay = createInternalTransfer(client, warehouse.id, from.id, to.id, nomenclature.id, "4", key)
        assertEquals(HttpStatusCode.Created, replay.status, replay.bodyAsText())
        val replayDto = replay.bodyAs<ApiResponse<TransferDto>>().data!!
        assertEquals(firstDto.id, replayDto.id)
        assertEquals(firstDto.number, replayDto.number)

        // Движение не задвоилось: источник потерял 4 один раз.
        val fromRow = getStock(client, warehouse.id, nomenclature.id).first { it.locationId == from.id }
        assertQty("6", fromRow.physicalQty)
        val listed = client.get("/api/wms/transfers").bodyAs<ApiResponse<List<TransferDto>>>().data!!
        assertEquals(1, listed.size)
    }

    @Test
    fun `idempotent ship replay does not move stock twice`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val fromWarehouse = createWarehouse(client, "WH-1")
        val toWarehouse = createWarehouse(client, "WH-2")
        val fromCell = createLocation(client, fromWarehouse.id, "A-01")
        val toCell = createLocation(client, toWarehouse.id, "B-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, fromWarehouse.id, fromCell.id, nomenclature.id, "10")
        val transfer = createInterTransfer(client, fromWarehouse.id, fromCell.id, toWarehouse.id, toCell.id, nomenclature.id, "10")
            .bodyAs<ApiResponse<TransferDto>>().data!!

        val key = newKey()
        val first = client.post("/api/wms/transfers/${transfer.id}/ship") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ShipTransferRequest(transfer.version, key)))
        }
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())

        val replay = client.post("/api/wms/transfers/${transfer.id}/ship") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ShipTransferRequest(transfer.version, key)))
        }
        assertEquals(HttpStatusCode.OK, replay.status, replay.bodyAsText())
        assertEquals(
            first.bodyAs<ApiResponse<TransferDto>>().data!!.id,
            replay.bodyAs<ApiResponse<TransferDto>>().data!!.id
        )

        val fromStock = getStock(client, fromWarehouse.id, nomenclature.id)
        assertQty("10", fromStock.first { it.locationPurpose == StorageLocationPurpose.TEMPORARY }.physicalQty)
        assertQty("0", fromStock.first { it.locationId == fromCell.id }.physicalQty)
    }

    @Test
    fun `idempotency key with different body is rejected`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val from = createLocation(client, warehouse.id, "A-01")
        val to = createLocation(client, warehouse.id, "A-02")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, warehouse.id, from.id, nomenclature.id, "10")

        val key = newKey()
        val first = createInternalTransfer(client, warehouse.id, from.id, to.id, nomenclature.id, "4", key)
        assertEquals(HttpStatusCode.Created, first.status, first.bodyAsText())

        val differentBody = createInternalTransfer(client, warehouse.id, from.id, to.id, nomenclature.id, "5", key)
        assertEquals(HttpStatusCode.Conflict, differentBody.status)

        val fromRow = getStock(client, warehouse.id, nomenclature.id).first { it.locationId == from.id }
        assertQty("6", fromRow.physicalQty)
    }

    @Test
    fun `ship with wrong version is rejected`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val fromWarehouse = createWarehouse(client, "WH-1")
        val toWarehouse = createWarehouse(client, "WH-2")
        val fromCell = createLocation(client, fromWarehouse.id, "A-01")
        val toCell = createLocation(client, toWarehouse.id, "B-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, fromWarehouse.id, fromCell.id, nomenclature.id, "10")
        val transfer = createInterTransfer(client, fromWarehouse.id, fromCell.id, toWarehouse.id, toCell.id, nomenclature.id, "5")
            .bodyAs<ApiResponse<TransferDto>>().data!!

        val wrongVersion = client.post("/api/wms/transfers/${transfer.id}/ship") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ShipTransferRequest(transfer.version + 5, newKey())))
        }
        assertEquals(HttpStatusCode.Conflict, wrongVersion.status)

        val reread = client.get("/api/wms/transfers/${transfer.id}")
            .bodyAs<ApiResponse<TransferDto>>().data!!
        assertEquals(TransferStatus.CREATED, reread.status)
        assertQty("10", getStock(client, fromWarehouse.id, nomenclature.id)
            .first { it.locationId == fromCell.id }.availableQty)
    }

    @Test
    fun `concurrent ships compete for the same free stock and only one succeeds`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "keeper", "password", "Keeper User", wmsPermissions())
        val client = loginClient("keeper", "password")

        val fromWarehouse = createWarehouse(client, "WH-1")
        val toWarehouse = createWarehouse(client, "WH-2")
        val fromCell = createLocation(client, fromWarehouse.id, "A-01")
        val toCell = createLocation(client, toWarehouse.id, "B-01")
        val nomenclature = createNomenclature(client, "MAT-1")
        postInitialStock(client, fromWarehouse.id, fromCell.id, nomenclature.id, "10")
        val firstTransfer = createInterTransfer(client, fromWarehouse.id, fromCell.id, toWarehouse.id, toCell.id, nomenclature.id, "6")
            .bodyAs<ApiResponse<TransferDto>>().data!!
        val secondTransfer = createInterTransfer(client, fromWarehouse.id, fromCell.id, toWarehouse.id, toCell.id, nomenclature.id, "6")
            .bodyAs<ApiResponse<TransferDto>>().data!!

        val firstClient = loginClient("keeper", "password")
        val secondClient = loginClient("keeper", "password")
        val statuses = coroutineScope {
            val first = async {
                firstClient.post("/api/wms/transfers/${firstTransfer.id}/ship") {
                    contentType(ContentType.Application.Json)
                    setBody(stringify(ShipTransferRequest(firstTransfer.version, newKey())))
                }.status
            }
            val second = async {
                secondClient.post("/api/wms/transfers/${secondTransfer.id}/ship") {
                    contentType(ContentType.Application.Json)
                    setBody(stringify(ShipTransferRequest(secondTransfer.version, newKey())))
                }.status
            }
            listOf(first.await(), second.await())
        }
        assertEquals(1, statuses.count { it == HttpStatusCode.OK }, "statuses: $statuses")
        assertEquals(1, statuses.count { it == HttpStatusCode.Conflict }, "statuses: $statuses")

        // В транзит ушло ровно 6; отрицательного доступного нет.
        val fromStock = getStock(client, fromWarehouse.id, nomenclature.id)
        assertQty("6", fromStock.first { it.locationPurpose == StorageLocationPurpose.TEMPORARY }.physicalQty)
        assertQty("4", fromStock.first { it.locationId == fromCell.id }.availableQty)
    }

    @Test
    fun `wms permissions are enforced on transfer endpoints`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "manager", "password", "Manager User", wmsPermissions())
        createUserWithRole(admin, "reader", "password", "Reader User", listOf(CreatePermissionRequest("wms", "read")))
        createUserWithRole(admin, "nobody", "password", "No Rights", emptyList())

        val managerClient = loginClient("manager", "password")
        val readerClient = loginClient("reader", "password")
        val nobodyClient = loginClient("nobody", "password")

        val warehouse = createWarehouse(managerClient, "WH-1")
        val from = createLocation(managerClient, warehouse.id, "A-01")
        val to = createLocation(managerClient, warehouse.id, "A-02")
        val nomenclature = createNomenclature(managerClient, "MAT-1")
        postInitialStock(managerClient, warehouse.id, from.id, nomenclature.id, "3")
        val transfer = createInternalTransfer(managerClient, warehouse.id, from.id, to.id, nomenclature.id, "1")
            .bodyAs<ApiResponse<TransferDto>>().data!!

        val createDenied = createInternalTransfer(readerClient, warehouse.id, from.id, to.id, nomenclature.id, "1")
        assertEquals(HttpStatusCode.Forbidden, createDenied.status)

        val readAllowed = readerClient.get("/api/wms/transfers/${transfer.id}")
        assertEquals(HttpStatusCode.OK, readAllowed.status)

        val readDenied = nobodyClient.get("/api/wms/transfers")
        assertEquals(HttpStatusCode.Forbidden, readDenied.status)
    }

    private fun newKey() = UUID.randomUUID().toString()

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

    private suspend fun createInternalTransfer(
        client: HttpClient,
        warehouseId: Long,
        fromLocationId: Long,
        toLocationId: Long,
        nomenclatureId: Long,
        quantity: String,
        idempotencyKey: String = newKey()
    ): HttpResponse {
        return client.post("/api/wms/transfers") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateTransferRequest(
                        kind = TransferKind.INTERNAL,
                        fromWarehouseId = warehouseId,
                        fromLocationId = fromLocationId,
                        toWarehouseId = warehouseId,
                        toLocationId = toLocationId,
                        nomenclatureId = nomenclatureId,
                        quantity = quantity,
                        idempotencyKey = idempotencyKey
                    )
                )
            )
        }
    }

    private suspend fun createInterTransfer(
        client: HttpClient,
        fromWarehouseId: Long,
        fromLocationId: Long,
        toWarehouseId: Long,
        toLocationId: Long,
        nomenclatureId: Long,
        quantity: String
    ): HttpResponse {
        return client.post("/api/wms/transfers") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateTransferRequest(
                        kind = TransferKind.INTER_WAREHOUSE,
                        fromWarehouseId = fromWarehouseId,
                        fromLocationId = fromLocationId,
                        toWarehouseId = toWarehouseId,
                        toLocationId = toLocationId,
                        nomenclatureId = nomenclatureId,
                        quantity = quantity,
                        idempotencyKey = newKey()
                    )
                )
            )
        }
    }

    // Создаёт межскладской transfer и доводит его до IN_TRANSIT.
    private suspend fun shipInTransit(
        client: HttpClient,
        fromWarehouseId: Long,
        fromLocationId: Long,
        toWarehouseId: Long,
        toLocationId: Long,
        nomenclatureId: Long,
        quantity: String
    ): TransferDto {
        val created = createInterTransfer(client, fromWarehouseId, fromLocationId, toWarehouseId, toLocationId, nomenclatureId, quantity)
            .bodyAs<ApiResponse<TransferDto>>().data!!
        val shipResponse = client.post("/api/wms/transfers/${created.id}/ship") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ShipTransferRequest(created.version, newKey())))
        }
        assertEquals(HttpStatusCode.OK, shipResponse.status, shipResponse.bodyAsText())
        val shipped = shipResponse.bodyAs<ApiResponse<TransferDto>>().data!!
        val transitResponse = client.post("/api/wms/transfers/${created.id}/in-transit") {
            contentType(ContentType.Application.Json)
            setBody(stringify(MarkTransferInTransitRequest(shipped.version, newKey())))
        }
        assertEquals(HttpStatusCode.OK, transitResponse.status, transitResponse.bodyAsText())
        return transitResponse.bodyAs<ApiResponse<TransferDto>>().data!!
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
