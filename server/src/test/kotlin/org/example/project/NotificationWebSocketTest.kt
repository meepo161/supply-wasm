package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.*
import io.ktor.client.plugins.websocket.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.*
import io.ktor.server.testing.*
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.serializer
import org.example.project.dto.*
import org.example.project.model.CurrentUser
import org.example.project.service.ChatService
import org.jooq.DSLContext
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.koin.ktor.ext.getKoin
import java.sql.Types
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationWebSocketTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `participant receives notification over websocket when another participant writes`() = testApp { ctx ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        val author = createUserWithRole(admin, "ntfauthor1", "password", "Ntf Author One", emptyList())
        val recipient = createUserWithRole(admin, "ntfrecip1", "password", "Ntf Recipient One", emptyList())
        val taskId = createTask(
            number = "T-NW1",
            area = "PERSONAL",
            title = "Notification ws task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = author.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, author.id, "ASSIGNEE")
        addTaskParticipant(taskId, recipient.id, "OBSERVER")
        val authorUser = CurrentUser(author.id, "ntfauthor1", "Ntf Author One", emptySet())

        val recipientClient = loginClient("ntfrecip1", "password")
        recipientClient.webSocket("/api/notifications/ws") {
            val sent = ctx.sendMessage(
                taskId,
                SendChatMessageRequest("Realtime notification", "ntf-key-1"),
                authorUser
            )
            val envelope = receiveEnvelope()
            assertEquals("NOTIFICATION", envelope.getValue("category").jsonPrimitive.content)
            val notification = json.decodeFromJsonElement(
                NotificationDto.serializer(),
                envelope.getValue("notification")
            )
            assertEquals("NEW_TASK_MESSAGE", notification.type)
            assertEquals("TASK_CHAT_MESSAGE", notification.entityType)
            assertEquals(sent.id, notification.entityId)
            assertEquals(taskId, notification.taskId)
            assertFalse(notification.isRead)
            assertEquals(notification.id, envelope.getValue("id").jsonPrimitive.long)
            assertFalse(notification.title.contains("Realtime notification"))
            assertFalse(notification.title.contains("Notification ws task"))
        }
    }

    @Test
    fun `other users do not receive foreign notification events`() = testApp { ctx ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        val author = createUserWithRole(admin, "ntfauthor2", "password", "Ntf Author Two", emptyList())
        val recipient = createUserWithRole(admin, "ntfrecip2", "password", "Ntf Recipient Two", emptyList())
        val stranger = createUserWithRole(admin, "ntfstranger2", "password", "Ntf Stranger Two", emptyList())
        val taskId = createTask(
            number = "T-NW2",
            area = "PERSONAL",
            title = "Isolation ws task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = author.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, author.id, "ASSIGNEE")
        addTaskParticipant(taskId, recipient.id, "OBSERVER")
        val authorUser = CurrentUser(author.id, "ntfauthor2", "Ntf Author Two", emptySet())

        val recipientClient = loginClient("ntfrecip2", "password")
        val strangerClient = loginClient("ntfstranger2", "password")
        val authorClient = loginClient("ntfauthor2", "password")

        recipientClient.webSocket("/api/notifications/ws") {
            strangerClient.webSocket("/api/notifications/ws") stranger@{
                authorClient.webSocket("/api/notifications/ws") author@{
                    ctx.sendMessage(taskId, SendChatMessageRequest("Isolated", "ntf-key-2"), authorUser)
                    val envelope = this@webSocket.receiveEnvelope()
                    assertEquals("NOTIFICATION", envelope.getValue("category").jsonPrimitive.content)
                    val strangerFrame = withTimeoutOrNull(2_000) { this@stranger.incoming.receive() }
                    assertNull(strangerFrame, "Stranger must not receive foreign notifications")
                    val authorFrame = withTimeoutOrNull(2_000) { this@author.incoming.receive() }
                    assertNull(authorFrame, "Author must not receive a notification for own message")
                }
            }
        }
    }

    @Test
    fun `reconnect with after receives missed notifications once without duplicates`() = testApp { ctx ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        val author = createUserWithRole(admin, "ntfauthor3", "password", "Ntf Author Three", emptyList())
        val recipient = createUserWithRole(admin, "ntfrecip3", "password", "Ntf Recipient Three", emptyList())
        val taskId = createTask(
            number = "T-NW3",
            area = "PERSONAL",
            title = "Reconnect ws task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = author.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, author.id, "ASSIGNEE")
        addTaskParticipant(taskId, recipient.id, "OBSERVER")
        val authorUser = CurrentUser(author.id, "ntfauthor3", "Ntf Author Three", emptySet())

        val recipientClient = loginClient("ntfrecip3", "password")

        var firstId = 0L
        recipientClient.webSocket("/api/notifications/ws") {
            ctx.sendMessage(taskId, SendChatMessageRequest("First", "ntf-key-r1"), authorUser)
            val envelope = receiveEnvelope()
            firstId = envelope.getValue("id").jsonPrimitive.long
        }

        ctx.sendMessage(taskId, SendChatMessageRequest("Second", "ntf-key-r2"), authorUser)
        ctx.sendMessage(taskId, SendChatMessageRequest("Third", "ntf-key-r3"), authorUser)

        recipientClient.webSocket("/api/notifications/ws?after=$firstId") {
            val second = receiveEnvelope()
            val third = receiveEnvelope()
            val secondId = second.getValue("id").jsonPrimitive.long
            val thirdId = third.getValue("id").jsonPrimitive.long
            assertTrue(secondId > firstId)
            assertTrue(thirdId > secondId)

            ctx.sendMessage(taskId, SendChatMessageRequest("Fourth", "ntf-key-r4"), authorUser)
            val fourth = receiveEnvelope()
            val fourthId = fourth.getValue("id").jsonPrimitive.long
            assertTrue(fourthId > thirdId)

            val receivedIds = listOf(secondId, thirdId, fourthId)
            assertEquals(receivedIds.size, receivedIds.toSet().size)
        }
    }

    @Test
    fun `anonymous and foreign origin handshakes are refused`() = testApp { _ ->
        val admin = newClient()
        ensureAdmin(admin)
        val recipient = createUserWithRole(admin, "ntfrecip4", "password", "Ntf Recipient Four", emptyList())

        val exception = assertFailsWith<IllegalStateException> {
            newClient().webSocket("/api/notifications/ws") { }
        }
        val message = exception.message ?: ""
        assertTrue(
            message.contains("403") || message.contains("WebSocket connection failed"),
            "Handshake must be refused before upgrade, got: $message"
        )

        val recipientClient = loginClient("ntfrecip4", "password")
        val forbiddenUpgrade = recipientClient.get("/api/notifications/ws") {
            header(HttpHeaders.Origin, "http://evil.example")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenUpgrade.status)

        assertFailsWith<IllegalStateException> {
            recipientClient.webSocket(
                "/api/notifications/ws",
                request = { header(HttpHeaders.Origin, "http://evil.example") }
            ) {
            }
        }
    }

    @Test
    fun `logout closes socket on next event`() = testApp { ctx ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        val author = createUserWithRole(admin, "ntfauthor5", "password", "Ntf Author Five", emptyList())
        val recipient = createUserWithRole(admin, "ntfrecip5", "password", "Ntf Recipient Five", emptyList())
        val taskId = createTask(
            number = "T-NW5",
            area = "PERSONAL",
            title = "Logout ws task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = author.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, author.id, "ASSIGNEE")
        addTaskParticipant(taskId, recipient.id, "OBSERVER")
        val authorUser = CurrentUser(author.id, "ntfauthor5", "Ntf Author Five", emptySet())

        val recipientClient = loginClient("ntfrecip5", "password")
        recipientClient.webSocket("/api/notifications/ws") {
            val logoutResponse = recipientClient.post("/api/auth/logout")
            assertEquals(HttpStatusCode.OK, logoutResponse.status)
            ctx.sendMessage(taskId, SendChatMessageRequest("After logout", "ntf-key-l1"), authorUser)
            expectNeutralClose()
        }
    }

    @Test
    fun `repeated message with same idempotency key publishes notification once`() = testApp { ctx ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        val author = createUserWithRole(admin, "ntfauthor6", "password", "Ntf Author Six", emptyList())
        val recipient = createUserWithRole(admin, "ntfrecip6", "password", "Ntf Recipient Six", emptyList())
        val taskId = createTask(
            number = "T-NW6",
            area = "PERSONAL",
            title = "Idempotent ws task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = author.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, author.id, "ASSIGNEE")
        addTaskParticipant(taskId, recipient.id, "OBSERVER")
        val authorUser = CurrentUser(author.id, "ntfauthor6", "Ntf Author Six", emptySet())

        val recipientClient = loginClient("ntfrecip6", "password")
        recipientClient.webSocket("/api/notifications/ws") {
            val first = ctx.sendMessage(taskId, SendChatMessageRequest("Once", "ntf-key-i1"), authorUser)
            val second = ctx.sendMessage(taskId, SendChatMessageRequest("Once", "ntf-key-i1"), authorUser)
            assertEquals(first.id, second.id)

            val envelope = receiveEnvelope()
            assertEquals("NOTIFICATION", envelope.getValue("category").jsonPrimitive.content)
            val duplicate = withTimeoutOrNull(2_000) { incoming.receive() }
            assertNull(duplicate, "Repeated message must not publish the notification again")
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveEnvelope(): JsonObject {
        val frame = withTimeout(10_000) { incoming.receive() }
        assertTrue(frame is Frame.Text, "Expected text frame, got $frame")
        return json.parseToJsonElement((frame as Frame.Text).data.decodeToString()).jsonObject
    }

    private suspend fun DefaultClientWebSocketSession.expectNeutralClose() {
        val reason = withTimeout(10_000) { closeReason.await() }
        assertEquals(
            CloseReason.Codes.VIOLATED_POLICY.code,
            reason?.code,
            "Expected VIOLATED_POLICY close, got $reason"
        )
        assertEquals("", reason?.message, "Close reason must stay neutral")
    }

    private fun testApp(block: suspend ApplicationTestBuilder.(ChatService) -> Unit) {
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
            startApplication()
            block(application.getKoin().get())
        }
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
        val client = newClient()
        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(stringify(LoginRequest(login, password)))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        return client
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

    private fun ApplicationTestBuilder.newClient() = createClient {
        install(HttpCookies)
        install(WebSockets)
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
            TestDatabase.init()
        }

        @JvmStatic
        @AfterClass
        fun tearDown() {
            TestDatabase.destroy()
        }
    }
}
