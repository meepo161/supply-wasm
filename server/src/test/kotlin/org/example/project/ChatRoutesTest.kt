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
import org.jooq.DSLContext
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.koin.ktor.ext.getKoin
import java.sql.Types
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChatRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `task participant can read history and send idempotent messages`() = testApp {
        val admin = jsonClient()
        val member = createUserWithRole(admin, "member1", "password", "Member One", emptyList())
        val adminUser = ensureAdmin(admin)
        val project = createProject(admin, adminUser.id)
        val taskId = createTask(
            number = "T-001",
            area = "PROJECT",
            title = "Project task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = member.id,
            projectId = project.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member.id, "ASSIGNEE")

        val memberClient = loginClient("member1", "password")

        val emptyHistory = memberClient.get("/api/tasks/$taskId/chat/messages")
        assertEquals(HttpStatusCode.OK, emptyHistory.status)
        val emptyBody = emptyHistory.bodyAs<ApiResponse<ChatMessagePageDto>>().data!!
        assertEquals(0, emptyBody.messages.size)

        val sendResponse = memberClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Hello", "key-1")))
        }
        assertEquals(HttpStatusCode.Created, sendResponse.status)
        val sent = sendResponse.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        assertEquals("Hello", sent.content)
        assertEquals(ChatMessageType.USER, sent.type)
        assertEquals(member.id, sent.author?.userId)

        val duplicateResponse = memberClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Different text", "key-1")))
        }
        assertEquals(HttpStatusCode.Created, duplicateResponse.status)
        val duplicate = duplicateResponse.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        assertEquals(sent.id, duplicate.id)
        assertEquals("Hello", duplicate.content)

        val missingKeyResponse = memberClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("No key", "")))
        }
        assertEquals(HttpStatusCode.BadRequest, missingKeyResponse.status)
    }

    @Test
    fun `closed task is read only`() = testApp {
        val admin = jsonClient()
        val member = createUserWithRole(admin, "member2", "password", "Member Two", emptyList())
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-002",
            area = "PERSONAL",
            title = "Personal task",
            status = "CLOSED",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = member.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member.id, "ASSIGNEE")

        val memberClient = loginClient("member2", "password")

        val readResponse = memberClient.get("/api/tasks/$taskId/chat/messages")
        assertEquals(HttpStatusCode.OK, readResponse.status)

        val sendResponse = memberClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Hello", "key-2")))
        }
        assertEquals(HttpStatusCode.Forbidden, sendResponse.status)
    }

    @Test
    fun `project member can read project task chat without task participant record`() = testApp {
        val admin = jsonClient()
        val owner = createUserWithRole(
            admin,
            "owner",
            "password",
            "Owner User",
            listOf(CreatePermissionRequest("projects", "create"))
        )
        val member = createUserWithRole(admin, "projectmember", "password", "Project Member", emptyList())
        val ownerClient = loginClient("owner", "password")
        val project = createProject(ownerClient, owner.id, listOf(member.id))

        val taskId = createTask(
            number = "T-003",
            area = "PROJECT",
            title = "Project task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = owner.id,
            assigneeId = owner.id,
            projectId = project.id,
            createdBy = owner.id
        )

        val memberClient = loginClient("projectmember", "password")
        val response = memberClient.get("/api/tasks/$taskId/chat/messages")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `department employee can read department task chat`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val dept = createDepartment(admin, "DEPT1", "Department One")
        val employee = createUserWithRole(
            admin,
            "employee",
            "password",
            "Department Employee",
            emptyList(),
            departmentId = dept.id
        )
        val taskId = createTask(
            number = "T-004",
            area = "DEPARTMENT",
            title = "Department task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            departmentId = dept.id,
            createdBy = adminUser.id
        )

        val employeeClient = loginClient("employee", "password")
        val response = employeeClient.get("/api/tasks/$taskId/chat/messages")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun `stranger cannot access task chat`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val stranger = createUserWithRole(admin, "stranger", "password", "Stranger", emptyList())
        val taskId = createTask(
            number = "T-005",
            area = "PERSONAL",
            title = "Private task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )

        val strangerClient = loginClient("stranger", "password")
        val readResponse = strangerClient.get("/api/tasks/$taskId/chat/messages")
        assertEquals(HttpStatusCode.Forbidden, readResponse.status)

        val sendResponse = strangerClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Hello", "key-5")))
        }
        assertEquals(HttpStatusCode.Forbidden, sendResponse.status)
    }

    @Test
    fun `system event has separate type and is visible in history`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-006",
            area = "PERSONAL",
            title = "Task with event",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )

        val adminClient = loginClient("admin", "admin123")
        val eventResponse = adminClient.post("/api/tasks/$taskId/chat/system-events") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AddSystemEventRequest("Status changed to IN_PROGRESS")))
        }
        assertEquals(HttpStatusCode.Created, eventResponse.status)
        val event = eventResponse.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        assertEquals(ChatMessageType.SYSTEM, event.type)

        val historyResponse = adminClient.get("/api/tasks/$taskId/chat/messages")
        assertEquals(HttpStatusCode.OK, historyResponse.status)
        val history = historyResponse.bodyAs<ApiResponse<ChatMessagePageDto>>().data!!
        assertEquals(1, history.messages.size)
        assertEquals(ChatMessageType.SYSTEM, history.messages.single().type)
    }

    @Test
    fun `cursor pagination returns messages after last seen id`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-007",
            area = "PERSONAL",
            title = "Paginated task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )

        val adminClient = loginClient("admin", "admin123")
        repeat(5) { index ->
            val response = adminClient.post("/api/tasks/$taskId/chat/messages") {
                contentType(ContentType.Application.Json)
                setBody(stringify(SendChatMessageRequest("Message $index", "page-key-$index")))
            }
            assertEquals(HttpStatusCode.Created, response.status)
        }

        val firstPageResponse = adminClient.get("/api/tasks/$taskId/chat/messages?limit=2")
        assertEquals(HttpStatusCode.OK, firstPageResponse.status)
        val firstPage = firstPageResponse.bodyAs<ApiResponse<ChatMessagePageDto>>().data!!
        assertEquals(2, firstPage.messages.size)
        assertTrue(firstPage.hasMore)
        val nextAfter = assertNotNull(firstPage.nextAfter)

        val secondPageResponse = adminClient.get("/api/tasks/$taskId/chat/messages?after=$nextAfter")
        assertEquals(HttpStatusCode.OK, secondPageResponse.status)
        val secondPage = secondPageResponse.bodyAs<ApiResponse<ChatMessagePageDto>>().data!!
        assertEquals(3, secondPage.messages.size)
        assertEquals(false, secondPage.hasMore)
    }

    @Test
    fun `observer participant has read only access`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val observer = createUserWithRole(admin, "observer1", "password", "Observer One", emptyList())
        val taskId = createTask(
            number = "T-008",
            area = "PERSONAL",
            title = "Observed task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, observer.id, "OBSERVER")

        val observerClient = loginClient("observer1", "password")
        val readResponse = observerClient.get("/api/tasks/$taskId/chat/messages")
        assertEquals(HttpStatusCode.OK, readResponse.status)

        val sendResponse = observerClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Hello", "key-8")))
        }
        assertEquals(HttpStatusCode.Forbidden, sendResponse.status)
    }

    @Test
    fun `coexecutor participant can send messages`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val coexecutor = createUserWithRole(admin, "coexec1", "password", "Coexecutor One", emptyList())
        val taskId = createTask(
            number = "T-009",
            area = "PERSONAL",
            title = "Coexecuted task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, coexecutor.id, "COEXECUTOR")

        val coexecutorClient = loginClient("coexec1", "password")
        val sendResponse = coexecutorClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Working on it", "key-9")))
        }
        assertEquals(HttpStatusCode.Created, sendResponse.status)
        val sent = sendResponse.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        assertEquals(coexecutor.id, sent.author?.userId)
    }

    @Test
    fun `project member without participant record has view only access`() = testApp {
        val admin = jsonClient()
        val owner = createUserWithRole(
            admin,
            "owner2",
            "password",
            "Owner Two",
            listOf(CreatePermissionRequest("projects", "create"))
        )
        val member = createUserWithRole(admin, "projectmember2", "password", "Project Member Two", emptyList())
        val ownerClient = loginClient("owner2", "password")
        val project = createProject(ownerClient, owner.id, listOf(member.id))

        val taskId = createTask(
            number = "T-010",
            area = "PROJECT",
            title = "Project task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = owner.id,
            assigneeId = owner.id,
            projectId = project.id,
            createdBy = owner.id
        )

        val memberClient = loginClient("projectmember2", "password")
        val readResponse = memberClient.get("/api/tasks/$taskId/chat/messages")
        assertEquals(HttpStatusCode.OK, readResponse.status)

        val sendResponse = memberClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Hello", "key-10")))
        }
        assertEquals(HttpStatusCode.Forbidden, sendResponse.status)
    }

    @Test
    fun `department employee has view only access`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val dept = createDepartment(admin, "DEPT2", "Department Two")
        val employee = createUserWithRole(
            admin,
            "employee2",
            "password",
            "Department Employee Two",
            emptyList(),
            departmentId = dept.id
        )
        val taskId = createTask(
            number = "T-011",
            area = "DEPARTMENT",
            title = "Department task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            departmentId = dept.id,
            createdBy = adminUser.id
        )

        val employeeClient = loginClient("employee2", "password")
        val readResponse = employeeClient.get("/api/tasks/$taskId/chat/messages")
        assertEquals(HttpStatusCode.OK, readResponse.status)

        val sendResponse = employeeClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Hello", "key-11")))
        }
        assertEquals(HttpStatusCode.Forbidden, sendResponse.status)
    }

    @Test
    fun `same idempotency key is allowed in different tasks`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val firstTaskId = createTask(
            number = "T-012",
            area = "PERSONAL",
            title = "First task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )
        val secondTaskId = createTask(
            number = "T-013",
            area = "PERSONAL",
            title = "Second task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )

        val adminClient = loginClient("admin", "admin123")
        val firstResponse = adminClient.post("/api/tasks/$firstTaskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("First", "shared-key")))
        }
        assertEquals(HttpStatusCode.Created, firstResponse.status)
        val secondResponse = adminClient.post("/api/tasks/$secondTaskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Second", "shared-key")))
        }
        assertEquals(HttpStatusCode.Created, secondResponse.status)

        val first = firstResponse.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        val second = secondResponse.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        assertTrue(first.id != second.id)
        assertEquals("First", first.content)
        assertEquals("Second", second.content)
    }

    @Test
    fun `system event is forbidden in closed task`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-014",
            area = "PERSONAL",
            title = "Closed task",
            status = "CLOSED",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )

        val adminClient = loginClient("admin", "admin123")
        val eventResponse = adminClient.post("/api/tasks/$taskId/chat/system-events") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AddSystemEventRequest("Late event")))
        }
        assertEquals(HttpStatusCode.Forbidden, eventResponse.status)
    }

    @Test
    fun `system event has author and timestamp`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-015",
            area = "PERSONAL",
            title = "Event task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )

        val adminClient = loginClient("admin", "admin123")
        val eventResponse = adminClient.post("/api/tasks/$taskId/chat/system-events") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AddSystemEventRequest("Task created")))
        }
        assertEquals(HttpStatusCode.Created, eventResponse.status)
        val event = eventResponse.bodyAs<ApiResponse<ChatMessageDto>>().data!!
        assertEquals(ChatMessageType.SYSTEM, event.type)
        assertEquals(adminUser.id, event.author?.userId)
        assertTrue(event.createdAt.isNotBlank())
    }

    private suspend fun ApplicationTestBuilder.ensureAdmin(client: HttpClient): UserDto {
        val bootstrapResponse = client.post("/api/admin/bootstrap") {
            contentType(ContentType.Application.Json)
            setBody(stringify(BootstrapRequest("admin", "admin123", "Admin User")))
        }
        if (bootstrapResponse.status != HttpStatusCode.Created && bootstrapResponse.status != HttpStatusCode.Unauthorized) {
            assertEquals(HttpStatusCode.Created, bootstrapResponse.status, bootstrapResponse.bodyAsText())
        }
        val loginResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest("admin", "admin123")))
        }
        assertEquals(HttpStatusCode.OK, loginResponse.status, loginResponse.bodyAsText())
        val meResponse = client.get("/api/auth/me")
        assertEquals(HttpStatusCode.OK, meResponse.status)
        return meResponse.bodyAs<ApiResponse<MeResponse>>().data!!.user
    }

    private suspend fun ApplicationTestBuilder.createUserWithRole(
        adminClient: HttpClient,
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

    private suspend fun ApplicationTestBuilder.loginClient(login: String, password: String): HttpClient {
        val client = jsonClient()
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest(login, password)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return client
    }

    private suspend fun ApplicationTestBuilder.createDepartment(
        client: HttpClient,
        code: String,
        name: String
    ): DepartmentDto {
        val response = client.post("/api/departments") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateDepartmentRequest(code, name)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<DepartmentDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.createProject(
        client: HttpClient,
        ownerId: Long,
        memberIds: List<Long> = emptyList()
    ): ProjectDto {
        val response = client.post("/api/projects") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateProjectRequest(
                name = "Test Project",
                projectType = ProjectType.ORDER_DEVELOPMENT,
                ownerId = ownerId
            )))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val project = response.bodyAs<ApiResponse<ProjectDto>>().data!!
        memberIds.forEach { userId ->
            val addResponse = client.post("/api/projects/${project.id}/members") {
                contentType(ContentType.Application.Json)
                setBody(stringify(AddProjectMemberRequest(userId)))
            }
            assertEquals(HttpStatusCode.OK, addResponse.status, addResponse.bodyAsText())
        }
        return project
    }

    private fun ApplicationTestBuilder.createTask(
        number: String,
        area: String,
        title: String,
        status: String,
        type: String,
        initiatorId: Long,
        assigneeId: Long,
        projectId: Long? = null,
        departmentId: Long? = null,
        createdBy: Long
    ): Long {
        val dsl = application.getKoin().get<DSLContext>()
        return dsl.connectionResult { conn ->
            conn.prepareStatement(
                """
                INSERT INTO tasks (number, area, status, type, title, initiator_id, assignee_id, project_id, department_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, number)
                stmt.setString(2, area)
                stmt.setString(3, status)
                stmt.setString(4, type)
                stmt.setString(5, title)
                stmt.setLong(6, initiatorId)
                stmt.setLong(7, assigneeId)
                if (projectId != null) stmt.setLong(8, projectId) else stmt.setNull(8, Types.BIGINT)
                if (departmentId != null) stmt.setLong(9, departmentId) else stmt.setNull(9, Types.BIGINT)
                stmt.setLong(10, createdBy)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
    }

    private fun ApplicationTestBuilder.addTaskParticipant(taskId: Long, userId: Long, role: String) {
        val dsl = application.getKoin().get<DSLContext>()
        dsl.execute(
            "INSERT INTO task_participants (task_id, user_id, role) VALUES (?, ?, ?)",
            taskId,
            userId,
            role
        )
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
}
