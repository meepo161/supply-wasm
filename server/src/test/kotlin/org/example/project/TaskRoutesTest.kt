package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.plugins.cookies.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.example.project.dto.*
import org.example.project.repository.TaskRepository
import org.jooq.DSLContext
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.koin.ktor.ext.getKoin
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `personal task lifecycle`() = testApp {
        val admin = jsonClient()
        val worker = createUserWithRole(
            admin,
            "worker",
            "password",
            "Worker User",
            listOf(CreatePermissionRequest("tasks", "create"))
        )
        val workerClient = loginClient("worker", "password")

        val task = createTask(
            workerClient,
            CreateTaskRequest(
                title = "My personal task",
                area = TaskArea.PERSONAL,
                type = TaskWorkType.GENERAL,
                dueDate = "2026-12-31T00:00:00Z"
            )
        )
        assertTrue(task.number.startsWith("T-"))
        assertEquals(TaskArea.PERSONAL, task.area)
        assertEquals(TaskStatus.ASSIGNED, task.status)
        assertEquals(worker.id, task.assigneeId)

        val fetched = workerClient.getTask(task.id)
        assertEquals(task.id, fetched.id)

        val accepted = workerClient.accept(task.id, task.version)
        assertEquals(TaskStatus.IN_PROGRESS, accepted.status)
        assertEquals(task.version + 1, accepted.version)
        val started = workerClient.start(task.id, accepted.version)
        assertEquals(TaskStatus.IN_PROGRESS, started.status)

        val withResult = workerClient.submitResult(task.id, started.version, "Done")
        assertEquals("Done", withResult.result)

        val closed = workerClient.close(task.id, withResult.version)
        assertEquals(TaskStatus.CLOSED, closed.status)
        assertNotNull(closed.closedAt)

        val reopened = workerClient.reopen(task.id, closed.version, "Need additional work")
        assertEquals(TaskStatus.NEW, reopened.status)
        assertEquals(null, reopened.closedAt)
    }

    @Test
    fun `project task visible only to project members`() = testApp {
        val admin = jsonClient()
        val opener = createUserWithRole(
            admin,
            "opener",
            "password",
            "Opener User",
            listOf(
                CreatePermissionRequest("projects", "create"),
                CreatePermissionRequest("tasks", "create"),
                CreatePermissionRequest("tasks", "assign_any")
            )
        )
        val worker = createUserWithRole(
            admin,
            "worker",
            "password",
            "Worker User",
            emptyList()
        )
        val openerClient = loginClient("opener", "password")
        val workerClient = loginClient("worker", "password")

        val project = createProject(openerClient, opener.id)
        val task = createTask(
            openerClient,
            CreateTaskRequest(
                title = "Project task",
                description = "Project task description",
                area = TaskArea.PROJECT,
                type = TaskWorkType.GENERAL,
                projectId = project.id,
                assigneeId = opener.id,
                dueDate = "2026-12-31T00:00:00Z"
            )
        )

        val beforeJoin = workerClient.getTaskResponse(task.id)
        assertEquals(HttpStatusCode.Forbidden, beforeJoin.status)

        openerClient.addProjectMember(project.id, worker.id)

        val afterJoin = workerClient.getTaskResponse(task.id)
        assertEquals(HttpStatusCode.OK, afterJoin.status)
    }

    @Test
    fun `reviewed task acceptance flow`() = testApp {
        val admin = jsonClient()
        val opener = createUserWithRole(
            admin,
            "opener",
            "password",
            "Opener User",
            listOf(
                CreatePermissionRequest("projects", "create"),
                CreatePermissionRequest("tasks", "create"),
                CreatePermissionRequest("tasks", "assign_any")
            )
        )
        val worker = createUserWithRole(
            admin,
            "worker",
            "password",
            "Worker User",
            emptyList()
        )
        val reviewer = createUserWithRole(
            admin,
            "reviewer",
            "password",
            "Reviewer User",
            emptyList()
        )
        val openerClient = loginClient("opener", "password")
        val workerClient = loginClient("worker", "password")
        val reviewerClient = loginClient("reviewer", "password")

        val project = createProject(openerClient, opener.id)
        openerClient.addProjectMember(project.id, worker.id)

        val task = createTask(
            openerClient,
            CreateTaskRequest(
                title = "Reviewed task",
                description = "Reviewed task description",
                area = TaskArea.PROJECT,
                type = TaskWorkType.REVIEWED,
                projectId = project.id,
                assigneeId = worker.id,
                reviewerId = reviewer.id,
                acceptanceCriteria = "All tests pass",
                dueDate = "2026-12-31T00:00:00Z"
            )
        )

        val acceptedFirst = workerClient.accept(task.id, task.version)
        val started = workerClient.start(task.id, acceptedFirst.version)
        val withResult = workerClient.submitResult(task.id, started.version, "Implemented")
        val onReview = workerClient.sendToReview(task.id, withResult.version)
        assertEquals(TaskStatus.ON_REVIEW, onReview.status)

        val accepted = reviewerClient.review(onReview.id, onReview.version, ReviewDecision.ACCEPT)
        assertEquals(TaskStatus.CLOSED, accepted.status)

        val returnTask = createTask(
            openerClient,
            CreateTaskRequest(
                title = "Reviewed return task",
                description = "Description",
                area = TaskArea.PROJECT,
                type = TaskWorkType.REVIEWED,
                projectId = project.id,
                assigneeId = worker.id,
                reviewerId = reviewer.id,
                acceptanceCriteria = "All tests pass",
                dueDate = "2026-12-31T00:00:00Z"
            )
        )
        val acceptedReturn = workerClient.accept(returnTask.id, returnTask.version)
        val startedReturn = workerClient.start(returnTask.id, acceptedReturn.version)
        val resultReturn = workerClient.submitResult(returnTask.id, startedReturn.version, "Implemented")
        val reviewReturn = workerClient.sendToReview(resultReturn.id, resultReturn.version)
        val returned = reviewerClient.review(reviewReturn.id, reviewReturn.version, ReviewDecision.RETURN, "Fix errors")
        assertEquals(TaskStatus.REWORK, returned.status)
    }

    @Test
    fun `department task visible to department employees read only`() = testApp {
        val admin = jsonClient()
        createUserWithRole(admin, "bootstrap", "password", "Bootstrap Admin", emptyList())
        val dept = createDepartment(admin, "ENG", "Engineering")
        val head = createUserWithRole(
            admin,
            "head",
            "password",
            "Head User",
            listOf(CreatePermissionRequest("tasks", "create"))
        )
        // employee with no task permissions but same department
        val employee = createUserWithRole(
            admin,
            "employee",
            "password",
            "Employee User",
            emptyList(),
            departmentId = dept.id
        )
        val otherUser = createUserWithRole(
            admin,
            "other",
            "password",
            "Other User",
            emptyList()
        )
        admin.updateUserDepartment(head.id, dept.id)

        val headClient = loginClient("head", "password")
        val employeeClient = loginClient("employee", "password")
        val otherClient = loginClient("other", "password")

        val task = createTask(
            headClient,
            CreateTaskRequest(
                title = "Department task",
                description = "Department task description",
                area = TaskArea.DEPARTMENT,
                type = TaskWorkType.GENERAL,
                departmentId = dept.id,
                assigneeId = head.id,
                dueDate = "2026-12-31T00:00:00Z"
            )
        )

        val employeeGet = employeeClient.getTaskResponse(task.id)
        assertEquals(HttpStatusCode.OK, employeeGet.status)

        val employeeStart = employeeClient.startResponse(task.id, task.version)
        assertEquals(HttpStatusCode.Forbidden, employeeStart.status)

        val otherGet = otherClient.getTaskResponse(task.id)
        assertEquals(HttpStatusCode.Forbidden, otherGet.status)
    }

    @Test
    fun `block and unblock task`() = testApp {
        val admin = jsonClient()
        val worker = createUserWithRole(
            admin,
            "worker",
            "password",
            "Worker User",
            listOf(CreatePermissionRequest("tasks", "create"))
        )
        val workerClient = loginClient("worker", "password")

        val task = createTask(
            workerClient,
            CreateTaskRequest(
                title = "Blocked task",
                area = TaskArea.PERSONAL,
                type = TaskWorkType.GENERAL,
                dueDate = "2026-12-31T00:00:00Z"
            )
        )
        val started = workerClient.start(task.id, task.version)
        val blocked = workerClient.block(started.id, started.version, "Need clarification", worker.id)
        assertEquals(TaskStatus.BLOCKED, blocked.status)
        assertEquals("Need clarification", blocked.blockedReason)
        assertEquals(worker.id, blocked.unblockResponsibleId)

        val unblocked = workerClient.unblock(blocked.id, blocked.version)
        assertEquals(TaskStatus.IN_PROGRESS, unblocked.status)
        assertEquals(null, unblocked.unblockResponsibleId)
    }

    @Test
    fun `reviewed task requires reviewer and criteria`() = testApp {
        val admin = jsonClient()
        val opener = createUserWithRole(
            admin,
            "opener",
            "password",
            "Opener User",
            listOf(
                CreatePermissionRequest("projects", "create"),
                CreatePermissionRequest("tasks", "create"),
                CreatePermissionRequest("tasks", "assign_any")
            )
        )
        val worker = createUserWithRole(
            admin,
            "worker",
            "password",
            "Worker User",
            emptyList()
        )
        val openerClient = loginClient("opener", "password")
        val project = createProject(openerClient, opener.id)

        val missingReviewer = openerClient.post("/api/tasks") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateTaskRequest(
                title = "Reviewed task",
                description = "Description",
                area = TaskArea.PROJECT,
                type = TaskWorkType.REVIEWED,
                projectId = project.id,
                assigneeId = worker.id,
                dueDate = "2026-12-31T00:00:00Z"
            )))
        }
        assertEquals(HttpStatusCode.BadRequest, missingReviewer.status)

        val missingCriteria = openerClient.post("/api/tasks") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateTaskRequest(
                title = "Reviewed task",
                description = "Description",
                area = TaskArea.PROJECT,
                type = TaskWorkType.REVIEWED,
                projectId = project.id,
                assigneeId = worker.id,
                reviewerId = opener.id,
                dueDate = "2026-12-31T00:00:00Z"
            )))
        }
        assertEquals(HttpStatusCode.BadRequest, missingCriteria.status)
    }

    @Test
    fun `start requires due date`() = testApp {
        val admin = jsonClient()
        val worker = createUserWithRole(
            admin,
            "worker",
            "password",
            "Worker User",
            listOf(CreatePermissionRequest("tasks", "create"))
        )
        val workerClient = loginClient("worker", "password")

        val task = createTask(
            workerClient,
            CreateTaskRequest(
                title = "No due date task",
                area = TaskArea.PERSONAL,
                type = TaskWorkType.GENERAL
            )
        )
        val startResponse = workerClient.startResponse(task.id, task.version)
        assertEquals(HttpStatusCode.BadRequest, startResponse.status)
    }

    @Test
    fun `concurrent modification detected`() = testApp {
        val admin = jsonClient()
        val worker = createUserWithRole(
            admin,
            "worker",
            "password",
            "Worker User",
            listOf(CreatePermissionRequest("tasks", "create"))
        )
        val workerClient = loginClient("worker", "password")

        val task = createTask(
            workerClient,
            CreateTaskRequest(
                title = "Concurrent task",
                area = TaskArea.PERSONAL,
                type = TaskWorkType.GENERAL,
                dueDate = "2026-12-31T00:00:00Z"
            )
        )
        val started = workerClient.start(task.id, task.version)
        val secondStart = workerClient.startResponse(task.id, task.version)
        assertEquals(HttpStatusCode.Conflict, secondStart.status)
    }

    @Test
    fun `accept performs real transition and stale version fails`() = testApp {
        val admin = jsonClient()
        val worker = createUserWithRole(
            admin,
            "worker",
            "password",
            "Worker User",
            listOf(CreatePermissionRequest("tasks", "create"))
        )
        val workerClient = loginClient("worker", "password")

        val task = createTask(
            workerClient,
            CreateTaskRequest(
                title = "Accept task",
                area = TaskArea.PERSONAL,
                type = TaskWorkType.GENERAL,
                dueDate = "2026-12-31T00:00:00Z"
            )
        )
        assertEquals(TaskStatus.ASSIGNED, task.status)

        val accepted = workerClient.accept(task.id, task.version)
        assertEquals(TaskStatus.IN_PROGRESS, accepted.status)
        assertEquals(task.version + 1, accepted.version)

        val staleStart = workerClient.startResponse(task.id, task.version)
        assertEquals(HttpStatusCode.Conflict, staleStart.status)

        val secondAccept = workerClient.acceptResponse(task.id, accepted.version)
        assertEquals(HttpStatusCode.Conflict, secondAccept.status)
    }

    @Test
    fun `assignee becomes participant and gets task chat access`() = testApp {
        val admin = jsonClient()
        val opener = createUserWithRole(admin, "opener", "password", "Opener User", emptyList())
        val worker = createUserWithRole(
            admin,
            "worker",
            "password",
            "Worker User",
            listOf(CreatePermissionRequest("tasks", "create"))
        )
        val workerClient = loginClient("worker", "password")
        val openerClient = loginClient("opener", "password")

        val created = createTask(
            workerClient,
            CreateTaskRequest(
                title = "Own task",
                area = TaskArea.PERSONAL,
                type = TaskWorkType.GENERAL
            )
        )
        val participantsAfterCreate = taskRepository().listParticipants(created.id)
        assertTrue(
            participantsAfterCreate.any { it.userId == worker.id && it.role == TaskParticipantRole.ASSIGNEE.name }
        )

        val newTaskId = createNewTaskDirect("T-NEW-1", "Unassigned task", opener.id)
        val chatBefore = workerClient.get("/api/tasks/$newTaskId/chat/messages")
        assertEquals(HttpStatusCode.Forbidden, chatBefore.status)

        val assigned = openerClient.assign(newTaskId, worker.id, 0)
        assertEquals(TaskStatus.ASSIGNED, assigned.status)
        assertEquals(worker.id, assigned.assigneeId)

        val participantsAfterAssign = taskRepository().listParticipants(newTaskId)
        assertTrue(
            participantsAfterAssign.any { it.userId == worker.id && it.role == TaskParticipantRole.ASSIGNEE.name }
        )

        val chatAfter = workerClient.get("/api/tasks/$newTaskId/chat/messages")
        assertEquals(HttpStatusCode.OK, chatAfter.status)
    }

    @Test
    fun `block requires unblock responsible`() = testApp {
        val admin = jsonClient()
        val worker = createUserWithRole(
            admin,
            "worker",
            "password",
            "Worker User",
            listOf(CreatePermissionRequest("tasks", "create"))
        )
        val workerClient = loginClient("worker", "password")

        val task = createTask(
            workerClient,
            CreateTaskRequest(
                title = "Block validation task",
                area = TaskArea.PERSONAL,
                type = TaskWorkType.GENERAL
            )
        )
        val missingResponsible = workerClient.blockResponse(task.id, task.version, "Waiting for input")
        assertEquals(HttpStatusCode.BadRequest, missingResponsible.status)

        val missingReason = workerClient.post("/api/tasks/${task.id}/block") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BlockTaskRequest("", unblockResponsibleId = worker.id, version = task.version)))
        }
        assertEquals(HttpStatusCode.BadRequest, missingReason.status)

        val blocked = workerClient.block(task.id, task.version, "Waiting for input", worker.id)
        assertEquals(TaskStatus.BLOCKED, blocked.status)
        assertEquals(worker.id, blocked.unblockResponsibleId)
    }

    @Test
    fun `unblock restores status before block`() = testApp {
        val admin = jsonClient()
        val worker = createUserWithRole(
            admin,
            "worker",
            "password",
            "Worker User",
            listOf(CreatePermissionRequest("tasks", "create"))
        )
        val workerClient = loginClient("worker", "password")

        val task = createTask(
            workerClient,
            CreateTaskRequest(
                title = "Assigned then blocked task",
                area = TaskArea.PERSONAL,
                type = TaskWorkType.GENERAL
            )
        )
        assertEquals(TaskStatus.ASSIGNED, task.status)

        val blocked = workerClient.block(task.id, task.version, "Waiting", worker.id)
        assertEquals(TaskStatus.BLOCKED, blocked.status)

        val unblocked = workerClient.unblock(blocked.id, blocked.version)
        assertEquals(TaskStatus.ASSIGNED, unblocked.status)
    }

    // region helpers

    private suspend fun ApplicationTestBuilder.createUserWithRole(
        adminClient: io.ktor.client.HttpClient,
        login: String,
        password: String,
        fullName: String,
        permissions: List<CreatePermissionRequest>,
        departmentId: Long? = null
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
            setBody(stringify(CreateUserRequest(login, password, fullName, departmentId = departmentId, roleIds = listOf(role.id))))
        }
        assertEquals(HttpStatusCode.Created, userResponse.status, userResponse.bodyAsText())
        return userResponse.bodyAs<ApiResponse<UserDto>>().data!!
    }

    private suspend fun io.ktor.client.HttpClient.updateUserDepartment(userId: Long, departmentId: Long) {
        val response = put("/api/users/$userId") {
            contentType(ContentType.Application.Json)
            setBody(stringify(UpdateUserRequest(departmentId = departmentId, version = 0)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun ApplicationTestBuilder.loginClient(login: String, password: String): io.ktor.client.HttpClient {
        val client = jsonClient()
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest(login, password)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return client
    }

    private suspend fun ApplicationTestBuilder.createDepartment(
        adminClient: io.ktor.client.HttpClient,
        code: String,
        name: String
    ): DepartmentDto {
        val response = adminClient.post("/api/departments") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateDepartmentRequest(code, name)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<DepartmentDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.createProject(
        client: io.ktor.client.HttpClient,
        ownerId: Long
    ): ProjectDto {
        val request = CreateProjectRequest(
            name = "Test Project",
            projectType = ProjectType.ORDER_DEVELOPMENT,
            ownerId = ownerId,
            plannedStartAt = "2026-01-01T00:00:00Z",
            plannedEndAt = "2026-12-31T00:00:00Z"
        )
        val response = client.post("/api/projects") {
            contentType(ContentType.Application.Json)
            setBody(stringify(request))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ProjectDto>>().data!!
    }

    private suspend fun io.ktor.client.HttpClient.addProjectMember(projectId: Long, userId: Long) {
        val response = post("/api/projects/$projectId/members") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AddProjectMemberRequest(userId)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun io.ktor.client.HttpClient.createTask(request: CreateTaskRequest): TaskDto {
        val response = post("/api/tasks") {
            contentType(ContentType.Application.Json)
            setBody(stringify(request))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<TaskDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.createTask(
        client: io.ktor.client.HttpClient,
        request: CreateTaskRequest
    ): TaskDto = client.createTask(request)

    private suspend fun io.ktor.client.HttpClient.getTask(id: Long): TaskDto {
        val response = get("/api/tasks/$id")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<TaskDto>>().data!!
    }

    private suspend fun io.ktor.client.HttpClient.getTaskResponse(id: Long): HttpResponse = get("/api/tasks/$id")

    private suspend fun io.ktor.client.HttpClient.accept(id: Long, version: Long): TaskDto {
        val response = post("/api/tasks/$id/accept") {
            contentType(ContentType.Application.Json)
            setBody(stringify(TaskCommandRequest(version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<TaskDto>>().data!!
    }

    private suspend fun io.ktor.client.HttpClient.acceptResponse(id: Long, version: Long): HttpResponse {
        return post("/api/tasks/$id/accept") {
            contentType(ContentType.Application.Json)
            setBody(stringify(TaskCommandRequest(version)))
        }
    }

    private suspend fun io.ktor.client.HttpClient.assign(id: Long, assigneeId: Long, version: Long): TaskDto {
        val response = post("/api/tasks/$id/assign") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AssignTaskRequest(assigneeId, version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<TaskDto>>().data!!
    }

    private suspend fun io.ktor.client.HttpClient.start(id: Long, version: Long): TaskDto {
        val response = post("/api/tasks/$id/start") {
            contentType(ContentType.Application.Json)
            setBody(stringify(TaskCommandRequest(version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<TaskDto>>().data!!
    }

    private suspend fun io.ktor.client.HttpClient.startResponse(id: Long, version: Long): HttpResponse {
        return post("/api/tasks/$id/start") {
            contentType(ContentType.Application.Json)
            setBody(stringify(TaskCommandRequest(version)))
        }
    }

    private suspend fun io.ktor.client.HttpClient.submitResult(id: Long, version: Long, result: String): TaskDto {
        val response = post("/api/tasks/$id/result") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SubmitResultRequest(result, version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<TaskDto>>().data!!
    }

    private suspend fun io.ktor.client.HttpClient.sendToReview(id: Long, version: Long): TaskDto {
        val response = post("/api/tasks/$id/send-to-review") {
            contentType(ContentType.Application.Json)
            setBody(stringify(TaskCommandRequest(version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<TaskDto>>().data!!
    }

    private suspend fun io.ktor.client.HttpClient.review(
        id: Long,
        version: Long,
        decision: ReviewDecision,
        reason: String? = null
    ): TaskDto {
        val response = post("/api/tasks/$id/review") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ReviewTaskRequest(decision, reason, version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<TaskDto>>().data!!
    }

    private suspend fun io.ktor.client.HttpClient.close(id: Long, version: Long): TaskDto {
        val response = post("/api/tasks/$id/close") {
            contentType(ContentType.Application.Json)
            setBody(stringify(TaskCommandRequest(version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<TaskDto>>().data!!
    }

    private suspend fun io.ktor.client.HttpClient.reopen(id: Long, version: Long, reason: String): TaskDto {
        val response = post("/api/tasks/$id/reopen") {
            contentType(ContentType.Application.Json)
            setBody(stringify(ReopenTaskRequest(reason, version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<TaskDto>>().data!!
    }

    private suspend fun io.ktor.client.HttpClient.block(id: Long, version: Long, reason: String, unblockResponsibleId: Long): TaskDto {
        val response = post("/api/tasks/$id/block") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BlockTaskRequest(reason, unblockResponsibleId = unblockResponsibleId, version = version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<TaskDto>>().data!!
    }

    private suspend fun io.ktor.client.HttpClient.blockResponse(id: Long, version: Long, reason: String): HttpResponse {
        return post("/api/tasks/$id/block") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BlockTaskRequest(reason, version = version)))
        }
    }

    private suspend fun io.ktor.client.HttpClient.unblock(id: Long, version: Long): TaskDto {
        val response = post("/api/tasks/$id/unblock") {
            contentType(ContentType.Application.Json)
            setBody(stringify(TaskCommandRequest(version)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<TaskDto>>().data!!
    }

    private fun ApplicationTestBuilder.taskRepository(): TaskRepository =
        TaskRepository(application.getKoin().get<DSLContext>())

    private fun ApplicationTestBuilder.createNewTaskDirect(number: String, title: String, initiatorId: Long): Long {
        val dsl = application.getKoin().get<DSLContext>()
        return dsl.connectionResult { conn ->
            conn.prepareStatement(
                """
                INSERT INTO tasks (number, area, status, type, title, description, initiator_id, created_by)
                VALUES (?, 'PERSONAL', 'NEW', 'GENERAL', ?, ?, ?, ?)
                RETURNING id
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, number)
                stmt.setString(2, title)
                stmt.setString(3, "Task description")
                stmt.setLong(4, initiatorId)
                stmt.setLong(5, initiatorId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
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
        private val json = Json {
            ignoreUnknownKeys = true
        }

        @JvmStatic
        @BeforeClass
        fun setup() {
            try {
                TestDatabase.init()
            } catch (e: Exception) {
                e.printStackTrace()
                throw e
            }
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            TestDatabase.destroy()
        }
    }

    // endregion
}
