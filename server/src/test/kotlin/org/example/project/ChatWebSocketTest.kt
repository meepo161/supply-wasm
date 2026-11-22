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
import org.example.project.exception.ForbiddenException
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatWebSocketTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `authorized participant receives new message over websocket`() = testApp { ctx ->
        val admin = newClient()
        val member = createUserWithRole(admin, "wsmember1", "password", "Ws Member One", emptyList())
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-WS1",
            area = "PERSONAL",
            title = "Realtime task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = member.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member.id, "ASSIGNEE")

        val memberClient = loginClient("wsmember1", "password")
        memberClient.webSocket("/api/tasks/$taskId/chat/ws") {
            ctx.sendMessage(
                taskId,
                SendChatMessageRequest("Realtime hello", "ws-key-1"),
                CurrentUser(member.id, "wsmember1", "Ws Member One", emptySet())
            )
            val envelope = receiveEnvelope()
            assertEquals("TASK_CHAT_MESSAGE", envelope.getValue("category").jsonPrimitive.content)
            val message = json.decodeFromJsonElement(ChatMessageDto.serializer(), envelope.getValue("message"))
            assertEquals("Realtime hello", message.content)
            assertEquals(taskId, message.taskId)
            assertEquals(ChatMessageType.USER, message.type)
            assertEquals(member.id, message.author?.userId)
            assertEquals(message.id, envelope.getValue("id").jsonPrimitive.long)
        }
    }

    @Test
    fun `stranger and anonymous handshakes are refused without data`() = testApp { _ ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        createUserWithRole(admin, "wsstranger", "password", "Ws Stranger", emptyList())
        val taskId = createTask(
            number = "T-WS2",
            area = "PERSONAL",
            title = "Private realtime task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )

        assertHandshakeRefused(newClient(), taskId)
        assertHandshakeRefused(loginClient("wsstranger", "password"), taskId)
    }

    @Test
    fun `reconnect with after receives missed messages once without duplicates`() = testApp { ctx ->
        val admin = newClient()
        val member = createUserWithRole(admin, "wsmember2", "password", "Ws Member Two", emptyList())
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-WS3",
            area = "PERSONAL",
            title = "Reconnect task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = member.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member.id, "ASSIGNEE")
        val memberUser = CurrentUser(member.id, "wsmember2", "Ws Member Two", emptySet())

        val memberClient = loginClient("wsmember2", "password")

        var firstId = 0L
        memberClient.webSocket("/api/tasks/$taskId/chat/ws") {
            ctx.sendMessage(taskId, SendChatMessageRequest("First", "ws-key-r1"), memberUser)
            val envelope = receiveEnvelope()
            firstId = json.decodeFromJsonElement(ChatMessageDto.serializer(), envelope.getValue("message")).id
        }

        ctx.sendMessage(taskId, SendChatMessageRequest("Second", "ws-key-r2"), memberUser)
        ctx.sendMessage(taskId, SendChatMessageRequest("Third", "ws-key-r3"), memberUser)

        memberClient.webSocket("/api/tasks/$taskId/chat/ws?after=$firstId") {
            val second = json.decodeFromJsonElement(
                ChatMessageDto.serializer(),
                receiveEnvelope().getValue("message")
            )
            val third = json.decodeFromJsonElement(
                ChatMessageDto.serializer(),
                receiveEnvelope().getValue("message")
            )
            assertEquals("Second", second.content)
            assertEquals("Third", third.content)
            assertTrue(second.id > firstId)
            assertTrue(third.id > second.id)

            ctx.sendMessage(taskId, SendChatMessageRequest("Fourth", "ws-key-r4"), memberUser)
            val fourth = json.decodeFromJsonElement(
                ChatMessageDto.serializer(),
                receiveEnvelope().getValue("message")
            )
            assertEquals("Fourth", fourth.content)
            assertTrue(fourth.id > third.id)

            val receivedIds = listOf(second.id, third.id, fourth.id)
            assertEquals(receivedIds.size, receivedIds.toSet().size)
        }
    }

    @Test
    fun `message of another task is not delivered`() = testApp { ctx ->
        val admin = newClient()
        val member = createUserWithRole(admin, "wsmember3", "password", "Ws Member Three", emptyList())
        val adminUser = ensureAdmin(admin)
        val firstTaskId = createTask(
            number = "T-WS4",
            area = "PERSONAL",
            title = "First ws task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = member.id,
            createdBy = adminUser.id
        )
        val secondTaskId = createTask(
            number = "T-WS5",
            area = "PERSONAL",
            title = "Second ws task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = member.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(firstTaskId, member.id, "ASSIGNEE")
        addTaskParticipant(secondTaskId, member.id, "ASSIGNEE")
        val memberUser = CurrentUser(member.id, "wsmember3", "Ws Member Three", emptySet())

        val memberClient = loginClient("wsmember3", "password")
        memberClient.webSocket("/api/tasks/$firstTaskId/chat/ws") {
            ctx.sendMessage(secondTaskId, SendChatMessageRequest("Other task", "ws-key-o1"), memberUser)
            val frame = withTimeoutOrNull(2_000) { incoming.receive() }
            assertNull(frame, "Socket of task $firstTaskId must not receive messages of task $secondTaskId")
        }
    }

    @Test
    fun `closed task produces no websocket event`() = testApp { ctx ->
        val admin = newClient()
        val member = createUserWithRole(admin, "wsmember4", "password", "Ws Member Four", emptyList())
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-WS6",
            area = "PERSONAL",
            title = "Closed ws task",
            status = "CLOSED",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = member.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member.id, "ASSIGNEE")

        val memberClient = loginClient("wsmember4", "password")
        memberClient.webSocket("/api/tasks/$taskId/chat/ws") {
            assertFailsWith<ForbiddenException> {
                ctx.sendMessage(
                    taskId,
                    SendChatMessageRequest("Too late", "ws-key-c1"),
                    CurrentUser(member.id, "wsmember4", "Ws Member Four", emptySet())
                )
            }
            val frame = withTimeoutOrNull(2_000) { incoming.receive() }
            assertNull(frame, "Closed task must not produce websocket events")
        }
    }

    @Test
    fun `revoked task access closes socket on next event`() = testApp { ctx ->
        val admin = newClient()
        val member = createUserWithRole(admin, "wsmember5", "password", "Ws Member Five", emptyList())
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-WS7",
            area = "PERSONAL",
            title = "Revocation task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = adminUser.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member.id, "OBSERVER")
        val adminAuthor = CurrentUser(adminUser.id, "admin", "Admin User", emptySet())

        val memberClient = loginClient("wsmember5", "password")
        memberClient.webSocket("/api/tasks/$taskId/chat/ws") {
            removeTaskParticipant(taskId, member.id)
            ctx.sendMessage(taskId, SendChatMessageRequest("After revoke", "ws-key-v1"), adminAuthor)
            expectNeutralClose()
        }
    }

    @Test
    fun `logout closes socket on next event`() = testApp { ctx ->
        val admin = newClient()
        val member = createUserWithRole(admin, "wsmember6", "password", "Ws Member Six", emptyList())
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-WS8",
            area = "PERSONAL",
            title = "Logout task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = member.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member.id, "ASSIGNEE")
        val adminAuthor = CurrentUser(adminUser.id, "admin", "Admin User", emptySet())

        val memberClient = loginClient("wsmember6", "password")
        memberClient.webSocket("/api/tasks/$taskId/chat/ws") {
            val logoutResponse = memberClient.post("/api/auth/logout")
            assertEquals(HttpStatusCode.OK, logoutResponse.status)
            ctx.sendMessage(taskId, SendChatMessageRequest("After logout", "ws-key-l1"), adminAuthor)
            expectNeutralClose()
        }
    }

    @Test
    fun `deactivated user closes socket on next event`() = testApp { ctx ->
        val admin = newClient()
        val member = createUserWithRole(admin, "wsmember7", "password", "Ws Member Seven", emptyList())
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-WS9",
            area = "PERSONAL",
            title = "Deactivation task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = member.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member.id, "ASSIGNEE")
        val adminAuthor = CurrentUser(adminUser.id, "admin", "Admin User", emptySet())

        val memberClient = loginClient("wsmember7", "password")
        memberClient.webSocket("/api/tasks/$taskId/chat/ws") {
            val deactivateResponse = admin.put("/api/users/${member.id}/active") {
                contentType(ContentType.Application.Json)
                setBody("""{"active": false, "version": ${member.version}}""")
            }
            assertEquals(HttpStatusCode.OK, deactivateResponse.status, deactivateResponse.bodyAsText())
            ctx.sendMessage(taskId, SendChatMessageRequest("After deactivation", "ws-key-d1"), adminAuthor)
            expectNeutralClose()
        }
    }

    @Test
    fun `expired session closes socket on next event`() = testApp { ctx ->
        val admin = newClient()
        val member = createUserWithRole(admin, "wsmember8", "password", "Ws Member Eight", emptyList())
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-WS10",
            area = "PERSONAL",
            title = "Expiry task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = member.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member.id, "ASSIGNEE")
        val adminAuthor = CurrentUser(adminUser.id, "admin", "Admin User", emptySet())

        val memberClient = loginClient("wsmember8", "password")
        memberClient.webSocket("/api/tasks/$taskId/chat/ws") {
            expireSessionsFor(member.id)
            ctx.sendMessage(taskId, SendChatMessageRequest("After expiry", "ws-key-e1"), adminAuthor)
            expectNeutralClose()
        }
    }

    @Test
    fun `websocket upgrade is allowed for configured origin and refused otherwise`() = testApp { ctx ->
        val admin = newClient()
        val member = createUserWithRole(admin, "wsmember9", "password", "Ws Member Nine", emptyList())
        val adminUser = ensureAdmin(admin)
        val taskId = createTask(
            number = "T-WS11",
            area = "PERSONAL",
            title = "Origin task",
            status = "IN_PROGRESS",
            type = "GENERAL",
            initiatorId = adminUser.id,
            assigneeId = member.id,
            createdBy = adminUser.id
        )
        addTaskParticipant(taskId, member.id, "ASSIGNEE")
        val adminAuthor = CurrentUser(adminUser.id, "admin", "Admin User", emptySet())

        val memberClient = loginClient("wsmember9", "password")
        memberClient.webSocket(
            "/api/tasks/$taskId/chat/ws",
            request = { header(HttpHeaders.Origin, "http://localhost:8081") }
        ) {
            ctx.sendMessage(taskId, SendChatMessageRequest("Allowed origin", "ws-key-g1"), adminAuthor)
            val envelope = receiveEnvelope()
            val message = json.decodeFromJsonElement(ChatMessageDto.serializer(), envelope.getValue("message"))
            assertEquals("Allowed origin", message.content)
        }

        val forbiddenUpgrade = memberClient.get("/api/tasks/$taskId/chat/ws") {
            header(HttpHeaders.Origin, "http://evil.example")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenUpgrade.status)

        assertFailsWith<IllegalStateException> {
            memberClient.webSocket(
                "/api/tasks/$taskId/chat/ws",
                request = { header(HttpHeaders.Origin, "http://evil.example") }
            ) {
            }
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

    private suspend fun assertHandshakeRefused(client: HttpClient, taskId: Long) {
        val exception = assertFailsWith<IllegalStateException> {
            client.webSocket("/api/tasks/$taskId/chat/ws") { }
        }
        val message = exception.message ?: ""
        assertTrue(
            message.contains("403") || message.contains("WebSocket connection failed"),
            "Handshake must be refused before upgrade, got: $message"
        )
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

    private fun ApplicationTestBuilder.removeTaskParticipant(taskId: Long, userId: Long) {
        val dsl = application.getKoin().get<DSLContext>()
        dsl.execute(
            "DELETE FROM task_participants WHERE task_id = ? AND user_id = ?",
            taskId,
            userId
        )
    }

    private fun ApplicationTestBuilder.expireSessionsFor(userId: Long) {
        val dsl = application.getKoin().get<DSLContext>()
        dsl.execute(
            "UPDATE sessions SET expires_at = now() - interval '1 minute' WHERE user_id = ?",
            userId
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
