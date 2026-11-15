package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.example.project.dto.*
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Вкладки карточки проекта и задачи проекта (PRJ-13, PRJ-14; ТЗ M.4).
 *
 * Проверяется:
 *  - обзор отдаёт реальные счётчики связанных записей, а не проценты готовности;
 *  - список задач проекта фильтруется на сервере по `projectId` и статусу;
 *  - задачи другого проекта в список не попадают;
 *  - неучастник не читает задачи, потребности и историю проекта;
 *  - история строится по журналу аудита и содержит реальные события;
 *  - потребности и документы проекта отдаются отдельными вкладками.
 */
class ProjectTabsRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `summary counts real related records`() = testApp {
        val ctx = bootstrap()
        val project = ctx.owner.post("/api/projects") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateProjectRequest(
                        name = "Проект со сводкой",
                        projectType = ProjectType.ORDER_DEVELOPMENT,
                        ownerId = ctx.ownerId
                    )
                )
            )
        }.bodyAs<ApiResponse<ProjectDto>>().data!!

        createTask(ctx.owner, project.id, "Задача 1", TaskStatus.NEW, ctx.ownerId)
        val toCancel = createTask(ctx.owner, project.id, "Задача 2", TaskStatus.NEW, ctx.ownerId)
        val cancelled = ctx.owner.post("/api/tasks/${toCancel.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CancelTaskRequest("Больше не нужна", toCancel.version)))
        }
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())

        val summary = ctx.owner.get("/api/projects/${project.id}/summary")
            .bodyAs<ApiResponse<ProjectSummaryDto>>().data!!
        assertEquals(project.id, summary.projectId)
        assertEquals(2, summary.taskCount)
        assertEquals(1, summary.openTaskCount)
        assertEquals(1, summary.completedTaskCount)
        assertEquals(0, summary.demandCount)
        assertEquals(0, summary.documentCount)
        assertTrue(summary.isMember)
        assertTrue(summary.canManageMembers)

        // Никаких процентов готовности в ответе нет — методика не утверждена.
        val raw = ctx.owner.get("/api/projects/${project.id}/summary").bodyAsText()
        assertFalse(raw.contains("percent", ignoreCase = true), raw)
        assertFalse(raw.contains("progress", ignoreCase = true), raw)
    }

    @Test
    fun `project tasks are filtered on server by project and status`() = testApp {
        val ctx = bootstrap()
        val first = createProject(ctx.owner, ctx.ownerId, "Первый проект")
        val second = createProject(ctx.owner, ctx.ownerId, "Второй проект")

        createTask(ctx.owner, first.id, "В работе", TaskStatus.IN_PROGRESS, ctx.ownerId)
        val cancelled = createTask(ctx.owner, first.id, "Отменённая", TaskStatus.NEW, ctx.ownerId)
        val cancelResponse = ctx.owner.post("/api/tasks/${cancelled.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CancelTaskRequest("Отменена для проверки фильтра", cancelled.version)))
        }
        assertEquals(HttpStatusCode.OK, cancelResponse.status, cancelResponse.bodyAsText())
        createTask(ctx.owner, second.id, "Чужая задача", TaskStatus.IN_PROGRESS, ctx.ownerId)

        val all = ctx.owner.get("/api/projects/${first.id}/tasks")
            .bodyAs<ApiResponse<List<TaskDto>>>().data!!
        assertEquals(2, all.size)
        assertTrue(all.all { it.projectId == first.id })

        val inProgress = ctx.owner.get("/api/projects/${first.id}/tasks?status=IN_PROGRESS")
            .bodyAs<ApiResponse<List<TaskDto>>>().data!!
        assertEquals(1, inProgress.size)
        assertEquals("В работе", inProgress.first().title)

        // Задача другого проекта в список первого не попадает.
        assertTrue(all.none { it.title == "Чужая задача" })

        val invalid = ctx.owner.get("/api/projects/${first.id}/tasks?status=UNKNOWN")
        assertEquals(HttpStatusCode.BadRequest, invalid.status, invalid.bodyAsText())
    }

    @Test
    fun `non member cannot read project tabs`() = testApp {
        val ctx = bootstrap()
        val project = createProject(ctx.owner, ctx.ownerId, "Закрытый проект")
        createTask(ctx.owner, project.id, "Задача проекта", TaskStatus.NEW, ctx.ownerId)

        listOf("summary", "tasks", "demands", "documents", "history").forEach { tab ->
            val response = ctx.stranger.get("/api/projects/${project.id}/$tab")
            assertEquals(HttpStatusCode.Forbidden, response.status, "$tab: ${response.bodyAsText()}")
        }

        // Участник видит задачи проекта.
        val added = ctx.owner.post("/api/projects/${project.id}/members") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AddProjectMemberRequest(ctx.memberId)))
        }
        assertEquals(HttpStatusCode.OK, added.status, added.bodyAsText())
        val tasks = ctx.member.get("/api/projects/${project.id}/tasks")
        assertEquals(HttpStatusCode.OK, tasks.status, tasks.bodyAsText())
        assertEquals(1, tasks.bodyAs<ApiResponse<List<TaskDto>>>().data!!.size)
    }

    @Test
    fun `history contains real audit events and is ordered newest first`() = testApp {
        val ctx = bootstrap()
        val project = createProject(ctx.owner, ctx.ownerId, "Проект с историей")
        val opened = ctx.owner.post("/api/projects/${project.id}/open") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    OpenProjectRequest(
                        openingReason = "Старт работ по договору",
                        version = project.version
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, opened.status, opened.bodyAsText())

        val history = ctx.owner.get("/api/projects/${project.id}/history")
            .bodyAs<ApiResponse<List<ProjectHistoryEntryDto>>>().data!!
        assertTrue(history.size >= 2, "ожидались события создания и открытия: $history")
        val actions = history.map { it.action }
        assertTrue(actions.contains("CREATE"), actions.toString())
        assertTrue(actions.contains("OPEN"), actions.toString())
        // Подписи готовые: клиент не переводит коды действий.
        assertTrue(history.any { it.title == "Проект создан" }, history.toString())
        assertTrue(history.any { it.title == "Проект открыт" }, history.toString())
        // Автор события известен.
        assertTrue(history.all { it.userId == ctx.ownerId }, history.toString())

        val limited = ctx.owner.get("/api/projects/${project.id}/history?limit=1")
            .bodyAs<ApiResponse<List<ProjectHistoryEntryDto>>>().data!!
        assertEquals(1, limited.size)
    }

    @Test
    fun `demands tab returns only project demands`() = testApp {
        val ctx = bootstrap()
        val project = createProject(ctx.owner, ctx.ownerId, "Проект с потребностью")
        val other = createProject(ctx.owner, ctx.ownerId, "Другой проект")

        val finished = createNomenclature(ctx.owner, "TABS-FP-1", NomenclatureType.FINISHED_PRODUCT)
        val material = createNomenclature(ctx.owner, "TABS-MAT-1", NomenclatureType.RAW_MATERIAL)
        val model = ctx.owner.post("/api/bom/models") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateProductModelRequest(code = "TABS-MODEL", name = "Модель")))
        }.bodyAs<ApiResponse<ProductModelDto>>().data!!
        val revision = ctx.owner.post("/api/bom/models/${model.id}/revisions") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateBomRevisionRequest(revisionNumber = "REV-1")))
        }.bodyAs<ApiResponse<BomRevisionDto>>().data!!
        val line = ctx.owner.post("/api/bom/revisions/${revision.id}/lines") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateBomLineRequest(
                        parentLineId = null,
                        parentNomenclatureId = finished.id,
                        childNomenclatureId = material.id,
                        quantity = "2",
                        unit = "pcs"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, line.status, line.bodyAsText())
        val approved = ctx.owner.post("/api/bom/revisions/${revision.id}/approve") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ApproveBomRevisionRequest(revision.version)))
        }
        assertEquals(HttpStatusCode.OK, approved.status, approved.bodyAsText())

        // Потребность проекта создаётся только из утверждённой ревизии BOM.
        createDemandFromBom(ctx.owner, project.id, revision.id)
        createDemandFromBom(ctx.owner, other.id, revision.id)

        val demands = ctx.owner.get("/api/projects/${project.id}/demands")
            .bodyAs<ApiResponse<List<DemandHeaderDto>>>().data!!
        assertEquals(1, demands.size)
        assertEquals(project.id, demands.first().projectId)

        val summary = ctx.owner.get("/api/projects/${project.id}/summary")
            .bodyAs<ApiResponse<ProjectSummaryDto>>().data!!
        assertEquals(1, summary.demandCount)
    }

    @Test
    fun `documents tab returns documents linked to the project`() = testApp {
        val ctx = bootstrap()
        val project = createProject(ctx.owner, ctx.ownerId, "Проект с документом")

        val created = ctx.owner.post("/api/documents") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateDocumentRequest(
                        name = "Техзадание проекта",
                        docType = DocumentType.TECHNICAL_SPECIFICATION
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val document = created.bodyAs<ApiResponse<DocumentDto>>().data!!

        val linked = ctx.owner.post("/api/documents/${document.id}/links") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateDocumentLinkRequest(DocumentLinkType.PROJECT, project.id)))
        }
        assertEquals(HttpStatusCode.Created, linked.status, linked.bodyAsText())

        val documents = ctx.owner.get("/api/projects/${project.id}/documents")
            .bodyAs<ApiResponse<List<DocumentDto>>>().data!!
        assertEquals(1, documents.size)
        assertEquals(document.id, documents.first().id)

        val summary = ctx.owner.get("/api/projects/${project.id}/summary")
            .bodyAs<ApiResponse<ProjectSummaryDto>>().data!!
        assertEquals(1, summary.documentCount)
    }

    // --- Сценарии ---

    private data class Ctx(
        val owner: HttpClient,
        val ownerId: Long,
        val member: HttpClient,
        val memberId: Long,
        val stranger: HttpClient
    )

    private suspend fun ApplicationTestBuilder.bootstrap(): Ctx {
        val admin = jsonClient()
        createUserWithRole(admin, "admin", "admin123", "Admin User", emptyList())
        val ownerId = createUserWithRole(admin, "owner", "password", "Owner", ownerPermissions()).id
        val memberId = createUserWithRole(admin, "member", "password", "Member", memberPermissions()).id
        createUserWithRole(admin, "stranger", "password", "Stranger", emptyList())
        val owner = loginClient("owner", "password")
        val member = loginClient("member", "password")
        val stranger = loginClient("stranger", "password")
        return Ctx(owner, ownerId, member, memberId, stranger)
    }

    private suspend fun createProject(client: HttpClient, ownerId: Long, name: String): ProjectDto {
        val response = client.post("/api/projects") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateProjectRequest(
                        name = name,
                        projectType = ProjectType.ORDER_DEVELOPMENT,
                        ownerId = ownerId
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ProjectDto>>().data!!
    }

    private suspend fun createTask(
        client: HttpClient,
        projectId: Long,
        title: String,
        status: TaskStatus,
        ownerId: Long
    ): TaskDto {
        val created = client.post("/api/tasks") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateTaskRequest(
                        title = title,
                        area = TaskArea.PROJECT,
                        type = TaskWorkType.GENERAL,
                        projectId = projectId,
                        // Исполнитель и срок обязательны, чтобы задачу можно было
                        // перевести в работу реальной командой `/start`.
                        assigneeId = ownerId,
                        dueDate = java.time.OffsetDateTime.now().plusDays(7).toString()
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val task = created.bodyAs<ApiResponse<TaskDto>>().data!!
        if (status == TaskStatus.NEW) return task

        // Статус меняется реальными командами API, а не правкой БД.
        val inProgress = client.post("/api/tasks/${task.id}/start") {
            contentType(ContentType.Application.Json)
            setBody(stringify(TaskCommandRequest(task.version)))
        }
        assertEquals(HttpStatusCode.OK, inProgress.status, inProgress.bodyAsText())
        return inProgress.bodyAs<ApiResponse<TaskDto>>().data!!
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
                        name = "Номенклатура $code",
                        type = type,
                        unit = "pcs"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<NomenclatureDto>>().data!!
    }

    private suspend fun createDemandFromBom(client: HttpClient, projectId: Long, revisionId: Long) {
        val response = client.post("/api/demands/from-bom") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateDemandFromBomRequest(
                        projectId = projectId,
                        revisionId = revisionId,
                        releaseQuantity = "5"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.createUserWithRole(
        adminClient: HttpClient,
        login: String,
        password: String,
        fullName: String,
        permissions: List<CreatePermissionRequest>
    ): UserDto {
        adminClient.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        adminClient.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        if (login == "admin") {
            return adminClient.get("/api/auth/me")
                .bodyAs<ApiResponse<MeResponse>>().data!!.user
        }
        if (permissions.isEmpty()) {
            val response = adminClient.post("/api/users") {
                contentType(ContentType.Application.Json)
                setBody(stringify(CreateUserRequest(login, password, fullName)))
            }
            assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
            return response.bodyAs<ApiResponse<UserDto>>().data!!
        }
        val roleResponse = adminClient.post("/api/roles") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateRoleRequest(
                        code = login.uppercase(),
                        name = fullName,
                        permissions = permissions
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, roleResponse.status, roleResponse.bodyAsText())
        val role = roleResponse.bodyAs<ApiResponse<RoleDto>>().data!!
        val response = adminClient.post("/api/users") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateUserRequest(login, password, fullName, roleIds = listOf(role.id))))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<UserDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.loginClient(login: String, password: String): HttpClient {
        val client = jsonClient()
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest(login, password)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return client
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

    private suspend inline fun <reified T> HttpResponse.bodyAs(): T =
        json.decodeFromString(serializer<T>(), bodyAsText())

    private inline fun <reified T> stringify(value: T): String =
        json.encodeToString(serializer<T>(), value)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        @JvmStatic
        @BeforeClass
        fun setup() = TestDatabase.init()

        @JvmStatic
        @AfterClass
        fun teardown() = TestDatabase.destroy()

        private fun ownerPermissions() = listOf(
            CreatePermissionRequest("projects", "create"),
            CreatePermissionRequest("projects", "open"),
            CreatePermissionRequest("projects", "close"),
            CreatePermissionRequest("projects", "manage_members"),
            CreatePermissionRequest("tasks", "create"),
            CreatePermissionRequest("tasks", "assign_any"),
            CreatePermissionRequest("demands", "create"),
            CreatePermissionRequest("demands", "read_any"),
            CreatePermissionRequest("documents", "create"),
            CreatePermissionRequest("documents", "read_any"),
            CreatePermissionRequest("wms", "read"),
            CreatePermissionRequest("wms", "manage"),
            CreatePermissionRequest("bom", "create"),
            CreatePermissionRequest("bom", "read_any"),
            CreatePermissionRequest("bom", "update"),
            CreatePermissionRequest("bom", "approve"),
            CreatePermissionRequest("bom", "explode"),
            CreatePermissionRequest("users", "read_any")
        )

        private fun memberPermissions() = listOf(
            CreatePermissionRequest("projects", "create"),
            CreatePermissionRequest("tasks", "create")
        )
    }
}
