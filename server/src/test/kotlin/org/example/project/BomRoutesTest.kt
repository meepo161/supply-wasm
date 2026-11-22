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

class BomRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
        truncateBom()
    }

    @Test
    fun `create model revision lines and approve`() = testApp {
        val admin = jsonClient()
        val editor = createUserWithRole(admin, "bom-editor", "password", "BOM Editor", bomEditorPermissions() + wmsPermissions())
        val approver = createUserWithRole(admin, "bom-approver", "password", "BOM Approver", bomApproverPermissions())
        val editorClient = loginClient("bom-editor", "password")
        val approverClient = loginClient("bom-approver", "password")

        val finishedProduct = createNomenclature(editorClient, "FP-1", NomenclatureType.FINISHED_PRODUCT)
        val componentA = createNomenclature(editorClient, "COMP-A", NomenclatureType.PURCHASED_COMPONENT)

        val model = createProductModel(editorClient, "MODEL-1")
        val revision = createRevision(editorClient, model.id, "REV-1")
        assertEquals(BomRevisionStatus.DRAFT, revision.status)

        val line = createBomLine(
            editorClient,
            revision.id,
            parentNomenclatureId = finishedProduct.id,
            childNomenclatureId = componentA.id,
            quantity = "2"
        )
        assertEquals(1, line.level)
        assertEquals(revision.id, line.revisionId)

        val approveResponse = approverClient.post("/api/bom/revisions/${revision.id}/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ApproveBomRevisionRequest(revision.version)))
        }
        assertEquals(HttpStatusCode.OK, approveResponse.status, approveResponse.bodyAsText())
        val approved = approveResponse.bodyAs<ApiResponse<BomRevisionDto>>().data!!
        assertEquals(BomRevisionStatus.APPROVED, approved.status)
        assertEquals(approver.id, approved.approvedBy)
        assertNotNull(approved.approvedAt)

        val editAfterApprove = editorClient.put("/api/bom/revisions/${revision.id}/lines/${line.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(UpdateBomLineRequest(quantity = "5")))
        }
        assertEquals(HttpStatusCode.Conflict, editAfterApprove.status)
    }

    @Test
    fun `copy revision requires reason and keeps lines`() = testApp {
        val admin = jsonClient()
        val editor = createUserWithRole(admin, "bom-editor", "password", "BOM Editor", bomEditorPermissions() + wmsPermissions())
        val client = loginClient("bom-editor", "password")

        val finishedProduct = createNomenclature(client, "FP-1", NomenclatureType.FINISHED_PRODUCT)
        val componentA = createNomenclature(client, "COMP-A", NomenclatureType.PURCHASED_COMPONENT)

        val model = createProductModel(client, "MODEL-1")
        val revision = createRevision(client, model.id, "REV-1")
        createBomLine(client, revision.id, finishedProduct.id, componentA.id, "2")

        val copyNoReason = client.post("/api/bom/revisions/${revision.id}/copy") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CopyBomRevisionRequest(revisionNumber = "REV-2", reason = "  ")))
        }
        assertEquals(HttpStatusCode.BadRequest, copyNoReason.status)

        val copyResponse = client.post("/api/bom/revisions/${revision.id}/copy") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CopyBomRevisionRequest(revisionNumber = "REV-2", reason = "Design change")))
        }
        assertEquals(HttpStatusCode.Created, copyResponse.status, copyResponse.bodyAsText())
        val copy = copyResponse.bodyAs<ApiResponse<BomRevisionDto>>().data!!
        assertEquals(BomRevisionStatus.DRAFT, copy.status)
        assertEquals("Design change", copy.changeReason)

        val lines = client.get("/api/bom/revisions/${copy.id}/lines")
            .bodyAs<ApiResponse<List<BomLineDto>>>().data!!
        assertEquals(1, lines.size)
        assertEquals(componentA.id, lines[0].childNomenclatureId)
        assertQty("2", lines[0].quantity)
    }

    @Test
    fun `approve and activate replaces previous active revision`() = testApp {
        val admin = jsonClient()
        val approver = createUserWithRole(admin, "bom-approver", "password", "BOM Approver", bomApproverPermissions() + wmsPermissions())
        val client = loginClient("bom-approver", "password")

        val fp = createNomenclature(client, "FP-1", NomenclatureType.FINISHED_PRODUCT)
        val comp = createNomenclature(client, "COMP-A", NomenclatureType.PURCHASED_COMPONENT)

        val model = createProductModel(client, "MODEL-1")
        val rev1 = createRevision(client, model.id, "REV-1")
        createBomLine(client, rev1.id, fp.id, comp.id, "2")
        val approved1 = approveRevision(client, rev1.id, rev1.version)
        val active1 = activateRevision(client, approved1.id, approved1.version)
        assertEquals(BomRevisionStatus.ACTIVE, active1.status)

        val rev2 = createRevision(client, model.id, "REV-2")
        createBomLine(client, rev2.id, fp.id, comp.id, "3")
        val approved2 = approveRevision(client, rev2.id, rev2.version)
        val active2 = activateRevision(client, approved2.id, approved2.version)
        assertEquals(BomRevisionStatus.ACTIVE, active2.status)

        val rereadActive1 = client.get("/api/bom/revisions/${active1.id}")
            .bodyAs<ApiResponse<BomRevisionDto>>().data!!
        assertEquals(BomRevisionStatus.REPLACED, rereadActive1.status)
    }

    @Test
    fun `cycle in bom is rejected on approve`() = testApp {
        val admin = jsonClient()
        val editor = createUserWithRole(admin, "bom-editor", "password", "BOM Editor", bomEditorPermissions() + wmsPermissions())
        val approver = createUserWithRole(admin, "bom-approver", "password", "BOM Approver", bomApproverPermissions())
        val editorClient = loginClient("bom-editor", "password")
        val approverClient = loginClient("bom-approver", "password")

        val fp = createNomenclature(editorClient, "FP-1", NomenclatureType.FINISHED_PRODUCT)
        val a = createNomenclature(editorClient, "A", NomenclatureType.PURCHASED_COMPONENT)
        val b = createNomenclature(editorClient, "B", NomenclatureType.PURCHASED_COMPONENT)

        val model = createProductModel(editorClient, "MODEL-1")
        val revision = createRevision(editorClient, model.id, "REV-1")
        val lineA = createBomLine(editorClient, revision.id, fp.id, a.id, "1")
        val lineB = createBomLine(editorClient, revision.id, a.id, b.id, "1", parentLineId = lineA.id)
        createBomLine(editorClient, revision.id, b.id, a.id, "1", parentLineId = lineB.id)

        val approveResponse = approverClient.post("/api/bom/revisions/${revision.id}/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ApproveBomRevisionRequest(revision.version)))
        }
        assertEquals(HttpStatusCode.BadRequest, approveResponse.status)
    }

    @Test
    fun `loss pct 100 is rejected`() = testApp {
        val admin = jsonClient()
        val editor = createUserWithRole(admin, "bom-editor", "password", "BOM Editor", bomEditorPermissions() + wmsPermissions())
        val client = loginClient("bom-editor", "password")

        val fp = createNomenclature(client, "FP-1", NomenclatureType.FINISHED_PRODUCT)
        val comp = createNomenclature(client, "COMP-A", NomenclatureType.PURCHASED_COMPONENT)

        val model = createProductModel(client, "MODEL-1")
        val revision = createRevision(client, model.id, "REV-1")
        val response = client.post("/api/bom/revisions/${revision.id}/lines") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateBomLineRequest(
                        parentNomenclatureId = fp.id,
                        childNomenclatureId = comp.id,
                        quantity = "1",
                        unit = "pcs",
                        lossPct = "100"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `explode sums requirements and shows wms indicators`() = testApp {
        val admin = jsonClient()
        val user = createUserWithRole(
            admin, "bom-user", "password", "BOM User",
            bomApproverPermissions() + wmsPermissions()
        )
        val client = loginClient("bom-user", "password")

        val warehouse = createWarehouse(client, "WH-1")
        val location = createLocation(client, warehouse.id, "A-01")

        val fp = createNomenclature(client, "FP-1", NomenclatureType.FINISHED_PRODUCT)
        val a = createNomenclature(client, "COMP-A", NomenclatureType.PURCHASED_COMPONENT)
        val b = createNomenclature(client, "COMP-B", NomenclatureType.PURCHASED_COMPONENT)

        val model = createProductModel(client, "MODEL-1")
        val revision = createRevision(client, model.id, "REV-1")
        val lineA = createBomLine(client, revision.id, fp.id, a.id, "2")
        createBomLine(client, revision.id, a.id, b.id, "3", parentLineId = lineA.id)

        val approved = approveRevision(client, revision.id, revision.version)

        postInitialStock(client, warehouse.id, location.id, a.id, "5")
        postInitialStock(client, warehouse.id, location.id, b.id, "70")

        val explodeResponse = client.post("/api/bom/revisions/${approved.id}/explode") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BomExplodeRequest(releaseQuantity = "10")))
        }
        assertEquals(HttpStatusCode.OK, explodeResponse.status, explodeResponse.bodyAsText())
        val report = explodeResponse.bodyAs<ApiResponse<BomExplodeReportDto>>().data!!
        assertEquals(approved.id, report.revisionId)
        assertEquals("10", report.releaseQuantity)
        assertEquals(2, report.lines.size)
        assertEquals(2, report.summary.size)

        val summaryA = report.summary.first { it.nomenclatureId == a.id }
        val summaryB = report.summary.first { it.nomenclatureId == b.id }
        assertQty("20", summaryA.totalRequired)
        assertQty("5", summaryA.stockAvailable)
        assertQty("15", summaryA.deficit)
        assertQty("15", summaryA.recommendedPurchase)

        assertQty("60", summaryB.totalRequired)
        assertQty("70", summaryB.stockAvailable)
        assertQty("0", summaryB.deficit)
        assertQty("0", summaryB.recommendedPurchase)
    }

    @Test
    fun `permissions are enforced on server`() = testApp {
        val admin = jsonClient()
        val editor = createUserWithRole(admin, "editor", "password", "Editor", bomEditorPermissions() + wmsPermissions())
        val reader = createUserWithRole(admin, "reader", "password", "Reader", listOf(CreatePermissionRequest("bom", "read_any")))
        val nobody = createUserWithRole(admin, "nobody", "password", "Nobody", emptyList())

        val editorClient = loginClient("editor", "password")
        val readerClient = loginClient("reader", "password")
        val nobodyClient = loginClient("nobody", "password")

        val fp = createNomenclature(editorClient, "FP-1", NomenclatureType.FINISHED_PRODUCT)
        val comp = createNomenclature(editorClient, "COMP-A", NomenclatureType.PURCHASED_COMPONENT)

        val model = createProductModel(editorClient, "MODEL-1")
        val revision = createRevision(editorClient, model.id, "REV-1")
        createBomLine(editorClient, revision.id, fp.id, comp.id, "2")

        val readDenied = nobodyClient.get("/api/bom/models")
        assertEquals(HttpStatusCode.Forbidden, readDenied.status)

        val readAllowed = readerClient.get("/api/bom/models")
        assertEquals(HttpStatusCode.OK, readAllowed.status)

        val createDenied = readerClient.post("/api/bom/models") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateProductModelRequest("MODEL-X", "Forbidden")))
        }
        assertEquals(HttpStatusCode.Forbidden, createDenied.status)
    }

    private fun bomEditorPermissions() = listOf(
        CreatePermissionRequest("bom", "create"),
        CreatePermissionRequest("bom", "read_any"),
        CreatePermissionRequest("bom", "update"),
        CreatePermissionRequest("bom", "delete_draft"),
        CreatePermissionRequest("bom", "explode")
    )

    private fun bomApproverPermissions() = bomEditorPermissions() + listOf(
        CreatePermissionRequest("bom", "approve")
    )

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

    private suspend fun activateRevision(client: HttpClient, revisionId: Long, version: Long): BomRevisionDto {
        val response = client.post("/api/bom/revisions/$revisionId/activate") {
            contentType(ContentType.Application.Json)
            setBody(stringify(TransitionBomRevisionRequest(version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<BomRevisionDto>>().data!!
    }

    private suspend fun createNomenclature(
        client: HttpClient,
        code: String,
        type: NomenclatureType = NomenclatureType.MATERIAL
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

    private suspend fun createLocation(
        client: HttpClient,
        warehouseId: Long,
        code: String
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
                        purpose = StorageLocationPurpose.STORAGE
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<StorageLocationDto>>().data!!
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

    private fun assertQty(expected: String, actual: String) {
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(actual)), "expected $expected but was $actual")
    }

    private fun truncateBom() {
        PGSimpleDataSource().apply {
            setUrl(TEST_URL)
            user = USER
            password = PASSWORD
        }.connection.use { conn ->
            conn.createStatement().execute(
                """
                TRUNCATE TABLE
                  bom_lines, product_revisions, product_models
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
