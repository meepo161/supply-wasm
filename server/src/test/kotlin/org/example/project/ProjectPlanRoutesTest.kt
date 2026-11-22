package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * План проекта: этапы, контрольные точки и риски (PRJ-08, PRJ-09, PRJ-10;
 * 5.3 и 5.5 ТЗ).
 *
 * Проверяется:
 *  - этапы создаются с реальной последовательностью и не дублируются;
 *  - переходы START -> COMPLETE фиксируют фактические даты, отменить
 *    завершённый этап нельзя;
 *  - работающий этап нельзя удалить, плановый — можно;
 *  - контрольные точки привязаны к проекту и своему этапу, отметка «достигнута»
 *    ставит фактическую дату;
 *  - риск нельзя закрыть без исхода, задача-связь должна принадлежать проекту;
 *  - сводка плана считает реальные значения и не содержит процентов;
 *  - участник читает план, но менять его может только владелец или
 *    `projects:manage_plan`.
 */
class ProjectPlanRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `stages are numbered and follow the start-complete flow`() = testApp {
        val ctx = bootstrap()
        val project = createProject(ctx.owner, ctx.ownerId, "Проект с этапами")

        val first = createStage(ctx.owner, project.id, "Анализ")
        val second = createStage(ctx.owner, project.id, "Разработка", sequence = 2)
        assertEquals(1, first.sequence)
        assertEquals(2, second.sequence)
        assertEquals(ProjectStageStatus.PLANNED, first.status)

        // Дублирующаяся последовательность отклоняется.
        val duplicate = ctx.owner.post("/api/projects/${project.id}/stages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateProjectStageRequest(name = "Дубль", sequence = 2)))
        }
        assertEquals(HttpStatusCode.Conflict, duplicate.status, duplicate.bodyAsText())

        val started = ctx.owner.post("/api/project-stages/${first.id}/start") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ProjectStageCommandRequest(first.version)))
        }
        assertEquals(HttpStatusCode.OK, started.status, started.bodyAsText())
        val running = started.bodyAs<ApiResponse<ProjectStageDto>>().data!!
        assertEquals(ProjectStageStatus.IN_PROGRESS, running.status)
        assertTrue(running.actualStartAt != null, "фактическая дата начала должна быть заполнена")

        // Работающий этап удалять нельзя — он остаётся в истории.
        val deleteRunning = ctx.owner.delete("/api/project-stages/${running.id}?version=${running.version}")
        assertEquals(HttpStatusCode.Conflict, deleteRunning.status, deleteRunning.bodyAsText())

        val completed = ctx.owner.post("/api/project-stages/${running.id}/complete") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ProjectStageCommandRequest(running.version)))
        }
        assertEquals(HttpStatusCode.OK, completed.status, completed.bodyAsText())
        val done = completed.bodyAs<ApiResponse<ProjectStageDto>>().data!!
        assertEquals(ProjectStageStatus.COMPLETED, done.status)
        assertTrue(done.actualEndAt != null, "фактическая дата завершения должна быть заполнена")

        // Завершённый этап нельзя отменить.
        val cancelDone = ctx.owner.post("/api/project-stages/${done.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ProjectStageCommandRequest(done.version, "Поздно отменять")))
        }
        assertEquals(HttpStatusCode.Conflict, cancelDone.status, cancelDone.bodyAsText())

        // Плановый этап удаляется.
        val deletePlanned = ctx.owner.delete("/api/project-stages/${second.id}?version=${second.version}")
        assertEquals(HttpStatusCode.NoContent, deletePlanned.status, deletePlanned.bodyAsText())

        val remaining = ctx.owner.get("/api/projects/${project.id}/stages")
            .bodyAs<ApiResponse<List<ProjectStageDto>>>().data!!
        assertEquals(1, remaining.size)
    }

    @Test
    fun `stage cancel requires a reason`() = testApp {
        val ctx = bootstrap()
        val project = createProject(ctx.owner, ctx.ownerId, "Проект с отменой")
        val stage = createStage(ctx.owner, project.id, "Отменяемый")

        val withoutReason = ctx.owner.post("/api/project-stages/${stage.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ProjectStageCommandRequest(stage.version)))
        }
        assertEquals(HttpStatusCode.BadRequest, withoutReason.status, withoutReason.bodyAsText())

        val cancelled = ctx.owner.post("/api/project-stages/${stage.id}/cancel") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ProjectStageCommandRequest(stage.version, "Отменено руководством")))
        }
        assertEquals(HttpStatusCode.OK, cancelled.status, cancelled.bodyAsText())
        val dto = cancelled.bodyAs<ApiResponse<ProjectStageDto>>().data!!
        assertEquals(ProjectStageStatus.CANCELLED, dto.status)
        assertEquals("Отменено руководством", dto.cancelReason)
    }

    @Test
    fun `milestones belong to project stage and record actual date`() = testApp {
        val ctx = bootstrap()
        val project = createProject(ctx.owner, ctx.ownerId, "Проект с точками")
        val stage = createStage(ctx.owner, project.id, "Этап с точкой")
        val otherProject = createProject(ctx.owner, ctx.ownerId, "Другой проект")
        val foreignStage = createStage(ctx.owner, otherProject.id, "Чужой этап")

        val created = ctx.owner.post("/api/projects/${project.id}/milestones") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateProjectMilestoneRequest(
                        name = "Прототип готов",
                        stageId = stage.id,
                        plannedAt = "2026-12-01T10:00:00Z"
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val milestone = created.bodyAs<ApiResponse<ProjectMilestoneDto>>().data!!
        assertEquals(ProjectMilestoneStatus.PLANNED, milestone.status)

        // Этап другого проекта в контрольную точку не привязывается.
        val foreign = ctx.owner.post("/api/projects/${project.id}/milestones") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateProjectMilestoneRequest(name = "Чужая", stageId = foreignStage.id)))
        }
        assertEquals(HttpStatusCode.BadRequest, foreign.status, foreign.bodyAsText())

        // Контрольные точки видны по этапу и по проекту.
        val byStage = ctx.owner.get("/api/projects/${project.id}/milestones?stageId=${stage.id}")
            .bodyAs<ApiResponse<List<ProjectMilestoneDto>>>().data!!
        assertEquals(1, byStage.size)
        val byProject = ctx.owner.get("/api/projects/${project.id}/milestones")
            .bodyAs<ApiResponse<List<ProjectMilestoneDto>>>().data!!
        assertEquals(1, byProject.size)

        val reached = ctx.owner.post("/api/project-milestones/${milestone.id}/reach") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ProjectMilestoneCommandRequest(milestone.version)))
        }
        assertEquals(HttpStatusCode.OK, reached.status, reached.bodyAsText())
        val reachedDto = reached.bodyAs<ApiResponse<ProjectMilestoneDto>>().data!!
        assertEquals(ProjectMilestoneStatus.REACHED, reachedDto.status)
        assertTrue(reachedDto.actualAt != null, "фактическая дата должна быть заполнена")

        // Достигнутую точку нельзя удалить — она остаётся фактом.
        val deleteReached = ctx.owner.delete("/api/project-milestones/${reachedDto.id}?version=${reachedDto.version}")
        assertEquals(HttpStatusCode.Conflict, deleteReached.status, deleteReached.bodyAsText())
    }

    @Test
    fun `risk closure requires an outcome`() = testApp {
        val ctx = bootstrap()
        val project = createProject(ctx.owner, ctx.ownerId, "Проект с рисками")

        val created = createRisk(ctx.owner, project.id, "Срыв поставки")
        assertEquals(ProjectRiskStatus.OPEN, created.status)

        val noOutcome = ctx.owner.post("/api/project-risks/${created.id}/close") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CloseProjectRiskRequest(created.version, outcome = "   ")))
        }
        assertEquals(HttpStatusCode.BadRequest, noOutcome.status, noOutcome.bodyAsText())

        val mitigated = ctx.owner.post("/api/project-risks/${created.id}/mitigate") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ProjectMilestoneCommandRequest(created.version)))
        }
        assertEquals(HttpStatusCode.OK, mitigated.status, mitigated.bodyAsText())
        val mitigatedDto = mitigated.bodyAs<ApiResponse<ProjectRiskDto>>().data!!
        assertEquals(ProjectRiskStatus.MITIGATING, mitigatedDto.status)

        val closed = ctx.owner.post("/api/project-risks/${mitigatedDto.id}/close") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CloseProjectRiskRequest(mitigatedDto.version, "Поставщик подтвердил срок")))
        }
        assertEquals(HttpStatusCode.OK, closed.status, closed.bodyAsText())
        val closedDto = closed.bodyAs<ApiResponse<ProjectRiskDto>>().data!!
        assertEquals(ProjectRiskStatus.CLOSED, closedDto.status)
        assertEquals("Поставщик подтвердил срок", closedDto.outcome)
        assertEquals(ctx.ownerId, closedDto.closedBy)

        // Закрытый риск не редактируется и не удаляется.
        val editClosed = ctx.owner.put("/api/project-risks/${closedDto.id}") {
            contentType(ContentType.Application.Json)
            setBody(stringify(UpdateProjectRiskRequest(title = "Новое имя", version = closedDto.version)))
        }
        assertEquals(HttpStatusCode.Conflict, editClosed.status, editClosed.bodyAsText())

        // Переоткрытие возвращает риск в работу и снимает исход.
        val reopened = ctx.owner.post("/api/project-risks/${closedDto.id}/reopen") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ProjectMilestoneCommandRequest(closedDto.version)))
        }
        assertEquals(HttpStatusCode.OK, reopened.status, reopened.bodyAsText())
        val reopenedDto = reopened.bodyAs<ApiResponse<ProjectRiskDto>>().data!!
        assertEquals(ProjectRiskStatus.OPEN, reopenedDto.status)
        assertNull(reopenedDto.outcome)
        assertNull(reopenedDto.closedAt)
    }

    @Test
    fun `risk task link must belong to the project`() = testApp {
        val ctx = bootstrap()
        val project = createProject(ctx.owner, ctx.ownerId, "Проект с задачей")
        val other = createProject(ctx.owner, ctx.ownerId, "Второй проект")

        val ownTask = createTask(ctx.owner, project.id, "Задача проекта", ctx.ownerId)
        val foreignTask = createTask(ctx.owner, other.id, "Чужая задача", ctx.ownerId)

        val linked = ctx.owner.post("/api/projects/${project.id}/risks") {
            contentType(ContentType.Application.Json)
            setBody(stringify(riskRequest("Риск по задаче", taskId = ownTask.id)))
        }
        assertEquals(HttpStatusCode.Created, linked.status, linked.bodyAsText())
        assertEquals(
            ownTask.id,
            linked.bodyAs<ApiResponse<ProjectRiskDto>>().data!!.taskId
        )

        val foreign = ctx.owner.post("/api/projects/${project.id}/risks") {
            contentType(ContentType.Application.Json)
            setBody(stringify(riskRequest("Риск по чужой задаче", taskId = foreignTask.id)))
        }
        assertEquals(HttpStatusCode.BadRequest, foreign.status, foreign.bodyAsText())
    }

    @Test
    fun `plan summary counts real values without percentages`() = testApp {
        val ctx = bootstrap()
        val project = createProject(ctx.owner, ctx.ownerId, "Проект со сводкой плана")
        val stage = createStage(ctx.owner, project.id, "Первый этап")
        createStage(ctx.owner, project.id, "Второй этап", sequence = 2)
        ctx.owner.post("/api/project-stages/${stage.id}/start") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ProjectStageCommandRequest(stage.version)))
        }

        val milestone = ctx.owner.post("/api/projects/${project.id}/milestones") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateProjectMilestoneRequest(
                        name = "Приёмка",
                        plannedAt = "2026-12-15T09:00:00Z"
                    )
                )
            )
        }.bodyAs<ApiResponse<ProjectMilestoneDto>>().data!!
        ctx.owner.post("/api/project-milestones/${milestone.id}/reach") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ProjectMilestoneCommandRequest(milestone.version)))
        }

        val critical = ctx.owner.post("/api/projects/${project.id}/risks") {
            contentType(ContentType.Application.Json)
            setBody(stringify(riskRequest("Критичный риск", criticality = ProjectRiskCriticality.CRITICAL)))
        }
        assertEquals(HttpStatusCode.Created, critical.status, critical.bodyAsText())

        val summary = ctx.owner.get("/api/projects/${project.id}/plan-summary")
            .bodyAs<ApiResponse<ProjectPlanSummaryDto>>().data!!
        assertEquals(2, summary.stageCount)
        assertEquals(0, summary.completedStageCount)
        assertEquals(stage.id, summary.currentStageId)
        assertEquals("Первый этап", summary.currentStageName)
        assertEquals(1, summary.milestoneCount)
        assertEquals(1, summary.reachedMilestoneCount)
        assertNull(summary.nextMilestoneId)
        assertEquals(1, summary.openRiskCount)
        assertEquals(1, summary.criticalRiskCount)

        // Процентов выполнения в ответе нет: методика не утверждена.
        val raw = ctx.owner.get("/api/projects/${project.id}/plan-summary").bodyAsText()
        assertTrue(!raw.contains("percent", ignoreCase = true), raw)
        assertTrue(!raw.contains("progress", ignoreCase = true), raw)
    }

    @Test
    fun `member reads the plan but only owner changes it`() = testApp {
        val ctx = bootstrap()
        val project = createProject(ctx.owner, ctx.ownerId, "Проект с участником")
        val stage = createStage(ctx.owner, project.id, "Этап владельца")

        val added = ctx.owner.post("/api/projects/${project.id}/members") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AddProjectMemberRequest(ctx.memberId)))
        }
        assertEquals(HttpStatusCode.OK, added.status, added.bodyAsText())

        // Участник видит план.
        val stages = ctx.member.get("/api/projects/${project.id}/stages")
        assertEquals(HttpStatusCode.OK, stages.status, stages.bodyAsText())
        assertEquals(1, stages.bodyAs<ApiResponse<List<ProjectStageDto>>>().data!!.size)
        val risks = ctx.member.get("/api/projects/${project.id}/risks")
        assertEquals(HttpStatusCode.OK, risks.status, risks.bodyAsText())

        // Но менять план не может.
        val forbidden = ctx.member.post("/api/projects/${project.id}/stages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateProjectStageRequest(name = "Этап участника")))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status, forbidden.bodyAsText())

        val forbiddenCommand = ctx.member.delete("/api/project-stages/${stage.id}?version=${stage.version}")
        assertEquals(HttpStatusCode.Forbidden, forbiddenCommand.status, forbiddenCommand.bodyAsText())

        // Неучастник не видит план вовсе.
        listOf("stages", "milestones", "risks", "plan-summary").forEach { path ->
            val response = ctx.stranger.get("/api/projects/${project.id}/$path")
            assertEquals(HttpStatusCode.Forbidden, response.status, "$path: ${response.bodyAsText()}")
        }
    }

    // --- Сценарии ----------------------------------------------------------

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

    private suspend fun createStage(
        client: HttpClient,
        projectId: Long,
        name: String,
        sequence: Int? = null
    ): ProjectStageDto {
        val response = client.post("/api/projects/$projectId/stages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateProjectStageRequest(name = name, sequence = sequence)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ProjectStageDto>>().data!!
    }

    private suspend fun createRisk(
        client: HttpClient,
        projectId: Long,
        title: String
    ): ProjectRiskDto {
        val response = client.post("/api/projects/$projectId/risks") {
            contentType(ContentType.Application.Json)
            setBody(stringify(riskRequest(title)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ProjectRiskDto>>().data!!
    }

    private fun riskRequest(
        title: String,
        criticality: ProjectRiskCriticality = ProjectRiskCriticality.HIGH,
        taskId: Long? = null
    ) = CreateProjectRiskRequest(
        title = title,
        category = ProjectRiskCategory.SUPPLY,
        probability = ProjectRiskLevel.MEDIUM,
        impact = ProjectRiskLevel.HIGH,
        criticality = criticality,
        responsePlan = "Согласовать резервного поставщика",
        taskId = taskId
    )

    private suspend fun createTask(
        client: HttpClient,
        projectId: Long,
        title: String,
        assigneeId: Long
    ): TaskDto {
        val response = client.post("/api/tasks") {
            contentType(ContentType.Application.Json)
            setBody(
                stringify(
                    CreateTaskRequest(
                        title = title,
                        area = TaskArea.PROJECT,
                        type = TaskWorkType.GENERAL,
                        projectId = projectId,
                        assigneeId = assigneeId
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<TaskDto>>().data!!
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
            CreatePermissionRequest("projects", "manage_plan"),
            CreatePermissionRequest("projects", "manage_members"),
            CreatePermissionRequest("tasks", "create")
        )

        private fun memberPermissions() = listOf(
            CreatePermissionRequest("projects", "create")
        )
    }
}
