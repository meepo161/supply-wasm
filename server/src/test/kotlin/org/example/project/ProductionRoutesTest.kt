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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProductionRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
        truncateProduction()
    }

    @Test
    fun `production order lifecycle creates instances on completion`() = testApp {
        val admin = jsonClient()
        makerId = createUserWithRole(admin, "maker", "password", "Maker User", productionPermissions()).id
        val client = loginClient("maker", "password")
        val fixtures = createFixtures(client)

        val created = createOrder(
            client,
            fixtures.projectId,
            fixtures.modelId,
            fixtures.approvedRevisionId,
            plannedQuantity = 2,
            stages = listOf(
                CreateProductionStageInput(ProductionStageType.ASSEMBLY, "Сборка", 1),
                CreateProductionStageInput(ProductionStageType.TESTING, "Испытания", 2)
            )
        )
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val order = created.bodyAs<ApiResponse<ProductionOrderDto>>().data!!
        assertEquals(ProductionOrderStatus.DRAFT, order.status)
        assertTrue(order.number.startsWith("PRO-"))
        assertEquals(2, order.stages.size)

        val planned = action(client, order.id, "plan", order.version)
        assertEquals(ProductionOrderStatus.PLANNED, planned.status)

        val started = action(client, order.id, "start", planned.version)
        assertEquals(ProductionOrderStatus.IN_PROGRESS, started.status)
        assertNotNull(started.actualStartAt)
        // Первый этап стартует вместе с заказом.
        assertEquals(ProductionStageStatus.IN_PROGRESS, started.stages.first().status)

        // Завершение при незакрытых этапах отклоняется.
        val tooEarly = client.post("/api/production/orders/${order.id}/complete") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CompleteProductionOrderRequest(started.version, 2, listOf("SN-1", "SN-2"))))
        }
        assertEquals(HttpStatusCode.Conflict, tooEarly.status, tooEarly.bodyAsText())

        var current = started
        current.stages.forEach { stage ->
            val runningStage = if (stage.status == ProductionStageStatus.IN_PROGRESS) {
                stage
            } else {
                val stageStarted = client.post("/api/production/orders/${order.id}/stages/${stage.id}/start") {
                    contentType(ContentType.Application.Json)
                    setBody(stringify(ProductionStageActionRequest(stage.version)))
                }
                assertEquals(HttpStatusCode.OK, stageStarted.status, stageStarted.bodyAsText())
                stageStarted.bodyAs<ApiResponse<ProductionOrderDto>>().data!!.stages.first { it.id == stage.id }
            }
            val stageCompleted = client.post("/api/production/orders/${order.id}/stages/${stage.id}/complete") {
                contentType(ContentType.Application.Json)
                setBody(stringify(ProductionStageActionRequest(runningStage.version)))
            }
            assertEquals(HttpStatusCode.OK, stageCompleted.status, stageCompleted.bodyAsText())
            current = stageCompleted.bodyAs<ApiResponse<ProductionOrderDto>>().data!!
        }
        assertTrue(current.stages.all { it.status == ProductionStageStatus.COMPLETED })

        // Серийных номеров должно быть ровно по количеству единиц.
        val wrongSerials = client.post("/api/production/orders/${order.id}/complete") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CompleteProductionOrderRequest(current.version, 2, listOf("SN-1"))))
        }
        assertEquals(HttpStatusCode.BadRequest, wrongSerials.status)

        val completed = client.post("/api/production/orders/${order.id}/complete") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CompleteProductionOrderRequest(current.version, 2, listOf("SN-1", "SN-2"))))
        }
        assertEquals(HttpStatusCode.OK, completed.status, completed.bodyAsText())
        val completedDto = completed.bodyAs<ApiResponse<ProductionOrderDto>>().data!!
        assertEquals(ProductionOrderStatus.COMPLETED, completedDto.status)
        assertEquals(2, completedDto.actualQuantity)

        val instances = client.get("/api/production/orders/${order.id}/instances")
        assertEquals(HttpStatusCode.OK, instances.status, instances.bodyAsText())
        val instanceList = instances.bodyAs<ApiResponse<List<ProductInstanceDto>>>().data!!
        assertEquals(2, instanceList.size)
        assertEquals(setOf("SN-1", "SN-2"), instanceList.map { it.serialNumber }.toSet())

        val firstInstance = instanceList.first { it.serialNumber == "SN-1" }
        val detailResponse = client.get("/api/production/instances/${firstInstance.id}")
        assertEquals(HttpStatusCode.OK, detailResponse.status, detailResponse.bodyAsText())
        val detail = detailResponse.bodyAs<ApiResponse<org.example.project.dto.ProductInstanceDetailDto>>().data!!
        assertEquals(firstInstance.id, detail.id)
        assertEquals("SN-1", detail.serialNumber)
        assertEquals(order.number, detail.orderNumber)
        assertEquals(0, detail.components.size)
        assertEquals(0, detail.tests.size)

        val missingInstance = client.get("/api/production/instances/999999")
        assertEquals(HttpStatusCode.NotFound, missingInstance.status)

        val addSw = client.post("/api/production/instances/${firstInstance.id}/software") {
            contentType(ContentType.Application.Json)
            setBody(stringify(org.example.project.dto.AddInstanceSoftwareRequest("Firmware-A", "1.0.2", "b42")))
        }
        assertEquals(HttpStatusCode.Created, addSw.status, addSw.bodyAsText())
        val afterSw = addSw.bodyAs<ApiResponse<org.example.project.dto.ProductInstanceDetailDto>>().data!!
        assertEquals(1, afterSw.software.size)
        assertEquals("Firmware-A", afterSw.software.first().name)

        val addTest = client.post("/api/production/instances/${firstInstance.id}/tests") {
            contentType(ContentType.Application.Json)
            setBody(stringify(org.example.project.dto.RecordInstanceTestRequest("GOOD", "Стендовая проверка ОК")))
        }
        assertEquals(HttpStatusCode.Created, addTest.status, addTest.bodyAsText())
        val afterTest = addTest.bodyAs<ApiResponse<org.example.project.dto.ProductInstanceDetailDto>>().data!!
        assertEquals(1, afterTest.tests.size)
        assertEquals("GOOD", afterTest.tests.first().result)

        val addRemark = client.post("/api/production/instances/${firstInstance.id}/remarks") {
            contentType(ContentType.Application.Json)
            setBody(stringify(org.example.project.dto.RecordInstanceRemarkRequest("Критичный дефект", "CRITICAL", true)))
        }
        assertEquals(HttpStatusCode.Created, addRemark.status, addRemark.bodyAsText())
        val afterRemark = addRemark.bodyAs<ApiResponse<org.example.project.dto.ProductInstanceDetailDto>>().data!!
        assertEquals(1, afterRemark.remarks.size)
        val remarkId = afterRemark.remarks.first().id

        val blockedAccept = client.post("/api/production/instances/${firstInstance.id}/accept")
        assertEquals(HttpStatusCode.Conflict, blockedAccept.status)

        val resolved = client.post("/api/production/instances/${firstInstance.id}/remarks/$remarkId/resolve")
        assertEquals(HttpStatusCode.OK, resolved.status, resolved.bodyAsText())

        val accepted = client.post("/api/production/instances/${firstInstance.id}/accept")
        assertEquals(HttpStatusCode.OK, accepted.status, accepted.bodyAsText())
        assertEquals("ACCEPTED", accepted.bodyAs<ApiResponse<org.example.project.dto.ProductInstanceDetailDto>>().data!!.status)

        val shipped = client.post("/api/production/instances/${firstInstance.id}/ship")
        assertEquals(HttpStatusCode.OK, shipped.status, shipped.bodyAsText())
        assertEquals("SHIPPED", shipped.bodyAs<ApiResponse<org.example.project.dto.ProductInstanceDetailDto>>().data!!.status)

        val closed = action(client, order.id, "close", completedDto.version)
        assertEquals(ProductionOrderStatus.CLOSED, closed.status)
    }

    @Test
    fun `production order requires approved or active revision of the same model`() = testApp {
        val admin = jsonClient()
        makerId = createUserWithRole(admin, "maker", "password", "Maker User", productionPermissions()).id
        val client = loginClient("maker", "password")
        val fixtures = createFixtures(client)

        val draftRevision = createRevision(client, fixtures.modelId, "DRAFT")
        val draftAttempt = createOrder(
            client, fixtures.projectId, fixtures.modelId, draftRevision,
            plannedQuantity = 1
        )
        assertEquals(HttpStatusCode.Conflict, draftAttempt.status, draftAttempt.bodyAsText())

        val otherModel = createProductModel(client, "MOD-OTHER")
        val foreignRevision = createRevision(client, otherModel.id, "APPROVED")
        val foreignAttempt = createOrder(client, fixtures.projectId, fixtures.modelId, foreignRevision, 1)
        assertEquals(HttpStatusCode.BadRequest, foreignAttempt.status, foreignAttempt.bodyAsText())

        val activeRevision = createRevision(client, fixtures.modelId, "ACTIVE")
        val activeAttempt = createOrder(client, fixtures.projectId, fixtures.modelId, activeRevision, 1)
        assertEquals(HttpStatusCode.Created, activeAttempt.status, activeAttempt.bodyAsText())
    }

    @Test
    fun `hold and resume keep the order in progress flow`() = testApp {
        val admin = jsonClient()
        makerId = createUserWithRole(admin, "maker", "password", "Maker User", productionPermissions()).id
        val client = loginClient("maker", "password")
        val fixtures = createFixtures(client)

        val order = createOrder(client, fixtures.projectId, fixtures.modelId, fixtures.approvedRevisionId, 1)
            .bodyAs<ApiResponse<ProductionOrderDto>>().data!!
        val planned = action(client, order.id, "plan", order.version)
        val started = action(client, order.id, "start", planned.version)

        val holdAttempt = client.post("/api/production/orders/${order.id}/hold") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ProductionOrderActionRequest(started.version, "Ожидание материалов")))
        }
        assertEquals(HttpStatusCode.OK, holdAttempt.status, holdAttempt.bodyAsText())
        val held = holdAttempt.bodyAs<ApiResponse<ProductionOrderDto>>().data!!
        assertEquals(ProductionOrderStatus.ON_HOLD, held.status)

        val resumed = action(client, order.id, "resume", held.version)
        assertEquals(ProductionOrderStatus.IN_PROGRESS, resumed.status)

        // Отмена из IN_PROGRESS запрещена политикой сервиса.
        val cancelAttempt = client.post("/api/production/orders/${order.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CancelProductionOrderRequest(resumed.version, "Не нужен")))
        }
        assertEquals(HttpStatusCode.Conflict, cancelAttempt.status, cancelAttempt.bodyAsText())
    }

    @Test
    fun `stale version is rejected with conflict`() = testApp {
        val admin = jsonClient()
        makerId = createUserWithRole(admin, "maker", "password", "Maker User", productionPermissions()).id
        val client = loginClient("maker", "password")
        val fixtures = createFixtures(client)

        val order = createOrder(client, fixtures.projectId, fixtures.modelId, fixtures.approvedRevisionId, 1)
            .bodyAs<ApiResponse<ProductionOrderDto>>().data!!
        val planned = action(client, order.id, "plan", order.version)
        // Повтор с устаревшей версией.
        val stale = client.post("/api/production/orders/${order.id}/plan") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ProductionOrderActionRequest(order.version)))
        }
        assertEquals(HttpStatusCode.Conflict, stale.status, stale.bodyAsText())
        // Планирование из PLANNED отклоняется.
        val again = client.post("/api/production/orders/${order.id}/plan") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ProductionOrderActionRequest(planned.version)))
        }
        assertEquals(HttpStatusCode.Conflict, again.status, again.bodyAsText())
    }

    @Test
    fun `production endpoints require production permission`() = testApp {
        val admin = jsonClient()
        makerId = createUserWithRole(
            admin, "maker", "password", "Maker User", productionPermissions()
        ).id
        createUserWithRole(admin, "reader", "password", "Reader User", emptyList())
        val maker = loginClient("maker", "password")
        val reader = loginClient("reader", "password")
        val fixtures = createFixtures(maker)

        val denied = createOrder(reader, fixtures.projectId, fixtures.modelId, fixtures.approvedRevisionId, 1)
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        val readDenied = reader.get("/api/production/orders")
        assertEquals(HttpStatusCode.Forbidden, readDenied.status)
        val instanceReadDenied = reader.get("/api/production/instances/1")
        assertEquals(HttpStatusCode.Forbidden, instanceReadDenied.status)
    }

    // --- вспомогательные методы ---

    // ID пользователя-исполнителя, который создаётся первым в каждом тесте.
    private var makerId: Long = 0

    private data class Fixtures(
        val projectId: Long,
        val modelId: Long,
        val approvedRevisionId: Long,
        val nomenclatureId: Long
    )

    // Ревизия BOM должна принадлежать изделию, поэтому модель создаётся
    // внутри фикстур (POST /api/bom/models требует право bom:create).
    private suspend fun ApplicationTestBuilder.createFixtures(client: HttpClient): Fixtures {
        val project = client.post("/api/projects") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateProjectRequest(
                        name = "Производственный проект ${UUID.randomUUID().toString().take(6)}",
                        description = "Проект для тестов производства",
                        projectType = ProjectType.ORDER_DEVELOPMENT,
                        ownerId = makerId
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, project.status, project.bodyAsText())
        val projectId = project.bodyAs<ApiResponse<ProjectDto>>().data!!.id

        val model = createProductModel(client, "MOD-1")
        val revision = createRevision(client, model.id, "APPROVED")
        return Fixtures(projectId, model.id, revision, 0)
    }

    private suspend fun createProductModel(client: HttpClient, code: String): ProductModelDto {
        val response = client.post("/api/bom/models") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateProductModelRequest(code, "Изделие $code")))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ProductModelDto>>().data!!
    }

    private suspend fun createRevision(
        client: HttpClient,
        modelId: Long,
        status: String
    ): Long {
        val createResponse = client.post("/api/bom/models/$modelId/revisions") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateBomRevisionRequest("REV-${UUID.randomUUID().toString().take(6)}")))
        }
        assertEquals(HttpStatusCode.Created, createResponse.status, createResponse.bodyAsText())
        val revision = createResponse.bodyAs<ApiResponse<BomRevisionDto>>().data!!
        if (status == "DRAFT") return revision.id

        val approvedResponse = client.post("/api/bom/revisions/${revision.id}/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ApproveBomRevisionRequest(revision.version)))
        }
        assertEquals(HttpStatusCode.OK, approvedResponse.status, approvedResponse.bodyAsText())
        val approved = approvedResponse.bodyAs<ApiResponse<BomRevisionDto>>().data!!
        if (status == "APPROVED") return approved.id

        val activatedResponse = client.post("/api/bom/revisions/${revision.id}/activate") {
            contentType(ContentType.Application.Json)
            setBody(stringify(TransitionBomRevisionRequest(approved.version)))
        }
        assertEquals(HttpStatusCode.OK, activatedResponse.status, activatedResponse.bodyAsText())
        return revision.id
    }

    private suspend fun createOrder(
        client: HttpClient,
        projectId: Long,
        modelId: Long,
        revisionId: Long,
        plannedQuantity: Int,
        stages: List<CreateProductionStageInput> = emptyList(),
        responsibleId: Long = makerId
    ): HttpResponse {
        return client.post("/api/production/orders") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateProductionOrderRequest(
                        projectId = projectId,
                        productModelId = modelId,
                        bomRevisionId = revisionId,
                        plannedQuantity = plannedQuantity,
                        responsibleId = responsibleId,
                        stages = stages
                    )
                )
            )
        }
    }

    private suspend fun action(
        client: HttpClient,
        orderId: Long,
        action: String,
        version: Long
    ): ProductionOrderDto {
        val response = client.post("/api/production/orders/$orderId/$action") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ProductionOrderActionRequest(version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ProductionOrderDto>>().data!!
    }

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

    private fun truncateProduction() {
        PGSimpleDataSource().apply {
            setUrl(TEST_URL)
            user = USER
            password = PASSWORD
        }.connection.use { conn ->
            conn.createStatement().execute(
                """
                TRUNCATE TABLE
                  nonconformity_history, nonconformities,
                  instance_remark_history, instance_remarks, instance_tests,
                  instance_software, instance_documents, instance_components,
                  product_instances, production_stage_assignees,
                  production_order_stages, production_orders
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

        // Заказы создаёт пользователь с правами производства; фикстуры BOM и
        // проектов он готовит сам, поэтому нужны и права bom/projects.
        private fun productionPermissions() = listOf(
            CreatePermissionRequest("production", "manage"),
            CreatePermissionRequest("production", "read"),
            CreatePermissionRequest("bom", "manage"),
            CreatePermissionRequest("bom", "create"),
            CreatePermissionRequest("bom", "approve"),
            CreatePermissionRequest("bom", "read"),
            CreatePermissionRequest("bom", "read_any"),
            CreatePermissionRequest("projects", "create"),
            CreatePermissionRequest("projects", "read")
        )
    }
}
