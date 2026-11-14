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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DemandRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
        truncateDemand()
    }

    @Test
    fun `create manual demand submit approve and cancel`() = testApp {
        val admin = jsonClient()
        val creator = createUserWithRole(
            admin, "demand-creator", "password", "Demand Creator",
            demandCreatorPermissions()
        )
        val approver = createUserWithRole(
            admin, "demand-approver", "password", "Demand Approver",
            demandApproverPermissions()
        )
        val creatorClient = loginClient("demand-creator", "password")
        val approverClient = loginClient("demand-approver", "password")

        val material = createNomenclature(admin, "MAT-1", NomenclatureType.RAW_MATERIAL)
        val demand = createManualDemand(
            creatorClient,
            lines = listOf(DemandLineInput(1, material.id, "10", "kg"))
        )
        assertEquals(DemandStatus.DRAFT, demand.header.status)
        assertEquals(1, demand.lines.size)
        assertTrue(demand.header.number.startsWith("DM-"))

        val submitted = submitDemand(creatorClient, demand.header.id, demand.header.version)
        assertEquals(DemandStatus.PENDING_APPROVAL, submitted.header.status)

        val approved = approveDemand(approverClient, demand.header.id, submitted.header.version)
        assertEquals(DemandStatus.APPROVED, approved.header.status)
        assertEquals(approver.id, approved.header.approvedBy)
        assertNotNull(approved.header.approvedAt)

        val cancelled = cancelDemand(creatorClient, demand.header.id, approved.header.version, "No longer needed")
        assertEquals(DemandStatus.CANCELLED, cancelled.header.status)
        assertTrue(cancelled.lines.all { it.status == DemandLineStatus.CANCELLED })
    }

    @Test
    fun `creation idempotency is scoped to user and request content`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "idem-first", "password", "First User", demandCreatorPermissions())
        createUserWithRole(admin, "idem-second", "password", "Second User", demandCreatorPermissions())
        val firstClient = loginClient("idem-first", "password")
        val secondClient = loginClient("idem-second", "password")
        val material = createNomenclature(admin, "IDEM-MAT", NomenclatureType.RAW_MATERIAL)
        val request = CreateDemandRequest(
            sourceType = DemandSourceType.MANUAL,
            idempotencyKey = "shared-key",
            lines = listOf(DemandLineInput(1, material.id, "2", "pcs"))
        )

        suspend fun send(client: HttpClient, body: CreateDemandRequest) = client.post("/api/demands") {
            contentType(ContentType.Application.Json)
            setBody(stringify(body))
        }

        val created = send(firstClient, request)
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val createdId = created.bodyAs<ApiResponse<DemandDetailDto>>().data!!.header.id

        val replay = send(firstClient, request)
        assertEquals(HttpStatusCode.Created, replay.status, replay.bodyAsText())
        assertEquals(createdId, replay.bodyAs<ApiResponse<DemandDetailDto>>().data!!.header.id)

        val changed = send(firstClient, request.copy(lines = request.lines.map { it.copy(quantity = "3") }))
        assertEquals(HttpStatusCode.Conflict, changed.status, changed.bodyAsText())

        val otherUser = send(secondClient, request)
        assertEquals(HttpStatusCode.Created, otherUser.status, otherUser.bodyAsText())
        assertTrue(otherUser.bodyAs<ApiResponse<DemandDetailDto>>().data!!.header.id != createdId)
    }

    @Test
    fun `create demand from approved bom revision`() = testApp {
        val admin = jsonClient()
        val user = createUserWithRole(
            admin, "bom-demand-user", "password", "BOM Demand User",
            demandCreatorPermissions() + bomPermissions() + wmsPermissions() + projectPermissions()
        )
        val client = loginClient("bom-demand-user", "password")

        val project = createProject(client, user.id)
        val fp = createNomenclature(client, "FP-1", NomenclatureType.FINISHED_PRODUCT)
        val comp = createNomenclature(client, "COMP-A", NomenclatureType.PURCHASED_COMPONENT)

        val model = createProductModel(client, "MODEL-1")
        val revision = createRevision(client, model.id, "REV-1")
        createBomLine(client, revision.id, fp.id, comp.id, "2")
        val approved = approveRevision(client, revision.id, revision.version)

        val demand = createDemandFromBom(
            client,
            projectId = project.id,
            revisionId = approved.id,
            releaseQuantity = "5"
        )
        assertEquals(DemandStatus.DRAFT, demand.header.status)
        assertEquals(DemandSourceType.PROJECT, demand.header.sourceType)
        assertEquals(approved.id, demand.header.sourceBomRevisionId)
        assertEquals(approved.version, demand.header.bomRevisionVersion)
        assertEquals("5", demand.header.bomReleaseQuantity)
        assertEquals(1, demand.lines.size)
        assertEquals(comp.id, demand.lines.first().nomenclatureId)
        assertEquals(approved.id, demand.lines.first().sourceBomRevisionId)
    }

    @Test
    fun `coverage preview shows stock and reservations`() = testApp {
        val admin = jsonClient()
        val user = createUserWithRole(
            admin, "coverage-user", "password", "Coverage User",
            demandApproverPermissions() + bomPermissions() + wmsPermissions() + projectPermissions()
        )
        val client = loginClient("coverage-user", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val location = createLocation(client, warehouse.id, "A-01")
        val project = createProject(client, user.id)
        val fp = createNomenclature(client, "FP-1", NomenclatureType.FINISHED_PRODUCT)
        val comp = createNomenclature(client, "COMP-A", NomenclatureType.PURCHASED_COMPONENT)

        val model = createProductModel(client, "MODEL-1")
        val revision = createRevision(client, model.id, "REV-1")
        createBomLine(client, revision.id, fp.id, comp.id, "2")
        val approved = approveRevision(client, revision.id, revision.version)

        val demand = createDemandFromBom(client, project.id, approved.id, "10")
        val submitted = submitDemand(client, demand.header.id, demand.header.version)
        approveDemand(client, demand.header.id, submitted.header.version)

        postInitialStock(client, warehouse.id, location.id, comp.id, "5")

        val coverage = client.get("/api/demands/${demand.header.id}/coverage")
            .bodyAs<ApiResponse<DemandCoverageDto>>().data!!
        assertEquals(demand.header.id, coverage.demandId)
        val line = coverage.lines.first { it.nomenclatureId == comp.id }
        assertEquals("20", line.requiredQuantity)
        assertEquals("5", line.stockAvailable)
        assertEquals("15", line.deficit)
    }

    @Test
    fun `non member cannot access project demand`() = testApp {
        val admin = jsonClient()
        val member = createUserWithRole(
            admin, "project-member", "password", "Project Member",
            demandCreatorPermissions() + wmsPermissions() + bomPermissions() + projectPermissions()
        )
        val stranger = createUserWithRole(
            admin, "stranger", "password", "Stranger",
            emptyList()
        )
        val memberClient = loginClient("project-member", "password")
        val strangerClient = loginClient("stranger", "password")

        val project = createProject(memberClient, member.id)
        val fp = createNomenclature(memberClient, "FP-2", NomenclatureType.FINISHED_PRODUCT)
        val comp = createNomenclature(memberClient, "COMP-B", NomenclatureType.PURCHASED_COMPONENT)

        val model = createProductModel(memberClient, "MODEL-2")
        val revision = createRevision(memberClient, model.id, "REV-1")
        createBomLine(memberClient, revision.id, fp.id, comp.id, "1")
        val approved = approveRevision(memberClient, revision.id, revision.version)

        val demand = createDemandFromBom(memberClient, project.id, approved.id, "1")

        val response = strangerClient.get("/api/demands/${demand.header.id}")
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    private fun demandCreatorPermissions() = listOf(
        CreatePermissionRequest("demands", "create"),
        CreatePermissionRequest("demands", "read_any"),
        CreatePermissionRequest("demands", "update")
    )

    private fun demandApproverPermissions() = demandCreatorPermissions() + listOf(
        CreatePermissionRequest("demands", "approve")
    )

    private fun bomPermissions() = listOf(
        CreatePermissionRequest("bom", "create"),
        CreatePermissionRequest("bom", "read_any"),
        CreatePermissionRequest("bom", "update"),
        CreatePermissionRequest("bom", "approve"),
        CreatePermissionRequest("bom", "explode")
    )

    private fun wmsPermissions() = listOf(
        CreatePermissionRequest("wms", "manage"),
        CreatePermissionRequest("wms", "post"),
        CreatePermissionRequest("wms", "read")
    )

    private fun projectPermissions() = listOf(
        CreatePermissionRequest("projects", "create")
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

    private suspend fun ApplicationTestBuilder.createProject(client: HttpClient, ownerId: Long): ProjectDto {
        val request = CreateProjectRequest(
            name = "Test Project",
            projectType = ProjectType.INTERNAL,
            ownerId = ownerId
        )
        val response = client.post("/api/projects") {
            contentType(ContentType.Application.Json)
            setBody(stringify(request))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ProjectDto>>().data!!
    }

    private suspend fun createManualDemand(
        client: HttpClient,
        sourceType: DemandSourceType = DemandSourceType.MANUAL,
        projectId: Long? = null,
        lines: List<DemandLineInput>
    ): DemandDetailDto {
        val request = CreateDemandRequest(
            sourceType = sourceType,
            projectId = projectId,
            priority = DemandPriority.NORMAL,
            lines = lines
        )
        val response = client.post("/api/demands") {
            contentType(ContentType.Application.Json)
            setBody(stringify(request))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<DemandDetailDto>>().data!!
    }

    private suspend fun createDemandFromBom(
        client: HttpClient,
        projectId: Long,
        revisionId: Long,
        releaseQuantity: String
    ): DemandDetailDto {
        val request = CreateDemandFromBomRequest(
            projectId = projectId,
            revisionId = revisionId,
            releaseQuantity = releaseQuantity
        )
        val response = client.post("/api/demands/from-bom") {
            contentType(ContentType.Application.Json)
            setBody(stringify(request))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<DemandDetailDto>>().data!!
    }

    private suspend fun submitDemand(client: HttpClient, demandId: Long, version: Long): DemandDetailDto {
        val response = client.post("/api/demands/$demandId/submit") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SubmitDemandRequest(version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<DemandDetailDto>>().data!!
    }

    private suspend fun approveDemand(client: HttpClient, demandId: Long, version: Long): DemandDetailDto {
        val response = client.post("/api/demands/$demandId/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ApproveDemandRequest(version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<DemandDetailDto>>().data!!
    }

    private suspend fun cancelDemand(
        client: HttpClient,
        demandId: Long,
        version: Long,
        reason: String
    ): DemandDetailDto {
        val response = client.post("/api/demands/$demandId/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CancelDemandRequest(version, reason)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<DemandDetailDto>>().data!!
    }

    private suspend fun createNomenclature(
        client: HttpClient,
        code: String,
        type: NomenclatureType
    ): NomenclatureDto {
        val response = client.post("/api/wms/nomenclature") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateNomenclatureRequest(
                        code = code,
                        name = "Nomenclature $code",
                        type = type,
                        unit = "pcs",
                        barcodes = listOf(CreateNomenclatureBarcodeRequest("BC-$code"))
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<NomenclatureDto>>().data!!
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

    private suspend fun createProductModel(client: HttpClient, code: String): ProductModelDto {
        val response = client.post("/api/bom/models") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateProductModelRequest(code = code, name = "Model $code")))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ProductModelDto>>().data!!
    }

    private suspend fun createRevision(client: HttpClient, modelId: Long, number: String): BomRevisionDto {
        val response = client.post("/api/bom/models/$modelId/revisions") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateBomRevisionRequest(revisionNumber = number)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<BomRevisionDto>>().data!!
    }

    private suspend fun createBomLine(
        client: HttpClient,
        revisionId: Long,
        parentNomenclatureId: Long,
        childNomenclatureId: Long,
        quantity: String,
        parentLineId: Long? = null
    ): BomLineDto {
        val response = client.post("/api/bom/revisions/$revisionId/lines") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateBomLineRequest(
                        parentLineId = parentLineId,
                        parentNomenclatureId = parentNomenclatureId,
                        childNomenclatureId = childNomenclatureId,
                        quantity = quantity,
                        unit = "pcs"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<BomLineDto>>().data!!
    }

    private suspend fun approveRevision(client: HttpClient, revisionId: Long, version: Long): BomRevisionDto {
        val response = client.post("/api/bom/revisions/$revisionId/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ApproveBomRevisionRequest(version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<BomRevisionDto>>().data!!
    }

    private suspend fun postInitialStock(
        client: HttpClient,
        warehouseId: Long,
        locationId: Long,
        nomenclatureId: Long,
        quantity: String
    ) {
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
                                expectedQuantity = quantity,
                                locationId = locationId
                            )
                        )
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val receipt = response.bodyAs<ApiResponse<ReceiptDto>>().data!!
        val postResponse = client.post("/api/wms/receipts/${receipt.id}/post") {
            contentType(ContentType.Application.Json)
            setBody(stringify(PostReceiptRequest(receipt.version)))
        }
        assertEquals(HttpStatusCode.OK, postResponse.status, postResponse.bodyAsText())
    }

    private fun truncateDemand() {
        PGSimpleDataSource().apply {
            setUrl(TEST_URL)
            user = USER
            password = PASSWORD
        }.connection.use { conn ->
            conn.createStatement().execute(
                """
                TRUNCATE TABLE
                  demand_procurements, demand_line_history, demand_history,
                  demand_lines, demands
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
