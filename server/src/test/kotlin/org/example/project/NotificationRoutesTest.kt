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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `new user message creates notification for other participant only`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member1 = createUserWithRole(admin, "member1", "password", "Member One", emptyList())
        val member2 = createUserWithRole(admin, "member2", "password", "Member Two", emptyList())
        val project = createProject(admin, adminUser.id)
        val taskId = createTask(
            number = "T-N01",
            area = "PROJECT",
            title = "Notification task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = member1.id,
            projectId = project.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member1.id, "COEXECUTOR")
        addTaskParticipant(taskId, member2.id, "OBSERVER")

        val member1Client = loginClient("member1", "password")
        val sendResponse = member1Client.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Secret text", "notif-key-1")))
        }
        assertEquals(HttpStatusCode.Created, sendResponse.status)
        val sent = sendResponse.bodyAs<ApiResponse<ChatMessageDto>>().data!!

        val member2Client = loginClient("member2", "password")
        val listResponse = member2Client.get("/api/notifications")
        assertEquals(HttpStatusCode.OK, listResponse.status)
        val page = listResponse.bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(1, page.notifications.size)

        val notification = page.notifications.single()
        assertEquals("NEW_TASK_MESSAGE", notification.type)
        assertEquals("TASK_CHAT_MESSAGE", notification.entityType)
        assertEquals(sent.id, notification.entityId)
        assertEquals(false, notification.isRead)
        assertFalse(notification.title.contains("Secret text"))
        assertFalse(notification.title.contains("Notification task"))

        val member1ListResponse = member1Client.get("/api/notifications")
        assertEquals(HttpStatusCode.OK, member1ListResponse.status)
        val member1Page = member1ListResponse.bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(0, member1Page.notifications.size)
    }

    @Test
    fun `notification list is accessible only to owner`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member1 = createUserWithRole(admin, "member3", "password", "Member Three", emptyList())
        val member2 = createUserWithRole(admin, "member4", "password", "Member Four", emptyList())
        val project = createProject(admin, adminUser.id)
        val taskId = createTask(
            number = "T-N02",
            area = "PROJECT",
            title = "Private notification task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = member1.id,
            projectId = project.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member1.id, "ASSIGNEE")
        addTaskParticipant(taskId, member2.id, "OBSERVER")

        val member1Client = loginClient("member3", "password")
        member1Client.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Hello", "notif-key-2")))
        }

        val member2Client = loginClient("member4", "password")
        val member2Page = member2Client.get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(1, member2Page.notifications.size)

        val adminClient = loginClient("admin", "admin123")
        val adminPage = adminClient.get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(0, adminPage.notifications.size)

        val member1Page = member1Client.get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(0, member1Page.notifications.size)
    }

    @Test
    fun `mark read is idempotent`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "member5", "password", "Member Five", emptyList())
        val taskId = createTask(
            number = "T-N03",
            area = "PERSONAL",
            title = "Read task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member.id, "COEXECUTOR")
        addTaskParticipant(taskId, adminUser.id, "INITIATOR")

        val memberClient = loginClient("member5", "password")
        memberClient.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("For read", "notif-key-3")))
        }

        val adminClient = loginClient("admin", "admin123")
        val notificationId = adminClient.get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!.notifications.single().id

        val firstRead = adminClient.post("/api/notifications/$notificationId/read")
        assertEquals(HttpStatusCode.OK, firstRead.status)
        val firstDto = firstRead.bodyAs<ApiResponse<NotificationDto>>().data!!
        assertTrue(firstDto.isRead)

        val secondRead = adminClient.post("/api/notifications/$notificationId/read")
        assertEquals(HttpStatusCode.OK, secondRead.status)
        val secondDto = secondRead.bodyAs<ApiResponse<NotificationDto>>().data!!
        assertTrue(secondDto.isRead)
    }

    @Test
    fun `foreign notification is not accessible`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member1 = createUserWithRole(admin, "member6", "password", "Member Six", emptyList())
        val member2 = createUserWithRole(admin, "member7", "password", "Member Seven", emptyList())
        val taskId = createTask(
            number = "T-N04",
            area = "PERSONAL",
            title = "Foreign task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member1.id, "ASSIGNEE")
        addTaskParticipant(taskId, member2.id, "OBSERVER")

        val member1Client = loginClient("member6", "password")
        member1Client.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Foreign", "notif-key-4")))
        }

        val member2Client = loginClient("member7", "password")
        val notificationId = member2Client.get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!.notifications.single().id

        val member1List = member1Client.get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(0, member1List.notifications.size)

        val foreignRead = member1Client.post("/api/notifications/$notificationId/read")
        assertEquals(HttpStatusCode.NotFound, foreignRead.status)
    }

    @Test
    fun `project member without participant record gets no notification`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member1 = createUserWithRole(admin, "member8", "password", "Member Eight", emptyList())
        val member2 = createUserWithRole(admin, "member9", "password", "Member Nine", emptyList())
        val projectMember = createUserWithRole(admin, "member10", "password", "Project Only Member", emptyList())
        val project = createProject(admin, adminUser.id, listOf(projectMember.id))
        val taskId = createTask(
            number = "T-N05",
            area = "PROJECT",
            title = "Project task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = member1.id,
            projectId = project.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member1.id, "ASSIGNEE")
        addTaskParticipant(taskId, member2.id, "OBSERVER")

        val member1Client = loginClient("member8", "password")
        val sendResponse = member1Client.post("/api/tasks/$taskId/chat/messages") {
            contentType(ContentType.Application.Json)
            setBody(stringify(SendChatMessageRequest("Hello", "notif-key-5")))
        }
        assertEquals(HttpStatusCode.Created, sendResponse.status)

        val member2Page = loginClient("member9", "password").get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(1, member2Page.notifications.size)

        val projectMemberPage = loginClient("member10", "password").get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(0, projectMemberPage.notifications.size)
    }

    @Test
    fun `system event creates no notifications`() = testApp {
        val admin = jsonClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "member11", "password", "Member Eleven", emptyList())
        val taskId = createTask(
            number = "T-N06",
            area = "PERSONAL",
            title = "Event task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, adminUser.id, "INITIATOR")
        addTaskParticipant(taskId, member.id, "OBSERVER")

        val adminClient = loginClient("admin", "admin123")
        val eventResponse = adminClient.post("/api/tasks/$taskId/chat/system-events") {
            contentType(ContentType.Application.Json)
            setBody(stringify(AddSystemEventRequest("Status changed")))
        }
        assertEquals(HttpStatusCode.Created, eventResponse.status)

        val memberPage = loginClient("member11", "password").get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(0, memberPage.notifications.size)

        val adminPage = adminClient.get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!
        assertEquals(0, adminPage.notifications.size)
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
