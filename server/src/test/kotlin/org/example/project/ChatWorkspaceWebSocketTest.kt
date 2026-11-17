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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
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
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatWorkspaceWebSocketTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `reaction read and pin events are delivered to connected participant`() = testApp { ctx ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cwmember1", "password", "Cw Member One", emptyList())
        val taskId = createTask("T-CWW1", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        addTaskParticipant(taskId, member.id, "ASSIGNEE")
        addTaskParticipant(taskId, adminUser.id, "INITIATOR")
        val adminCtx = CurrentUser(adminUser.id, "admin", "Admin User", emptySet())

        val message = ctx.sendMessage(taskId, SendChatMessageRequest("События", "cww-key-1"), adminCtx)

        val memberClient = loginClient("cwmember1", "password")
        memberClient.webSocket("/api/tasks/$taskId/chat/ws") {
            val messageEnvelope = receiveEnvelope()
            assertEquals("TASK_CHAT_MESSAGE", messageEnvelope.getValue("category").jsonPrimitive.content)

            ctx.addReaction(taskId, message.id, "👍", adminCtx)
            val reaction = receiveEnvelope()
            assertEquals("TASK_CHAT_REACTION", reaction.getValue("category").jsonPrimitive.content)
            assertEquals(taskId, reaction.getValue("taskId").jsonPrimitive.long)
            assertEquals(message.id, reaction.getValue("messageId").jsonPrimitive.long)
            val reactions = reaction.getValue("reactions").jsonArray
            assertEquals(1, reactions.size)
            assertEquals("👍", reactions.single().jsonObject.getValue("emoji").jsonPrimitive.content)
            assertEquals(1, reactions.single().jsonObject.getValue("count").jsonPrimitive.long)

            ctx.markRead(taskId, MarkChatReadRequest(message.id), adminCtx)
            val read = receiveEnvelope()
            assertEquals("TASK_CHAT_READ", read.getValue("category").jsonPrimitive.content)
            assertEquals(taskId, read.getValue("taskId").jsonPrimitive.long)
            assertEquals(adminUser.id, read.getValue("userId").jsonPrimitive.long)
            assertEquals(message.id, read.getValue("lastReadMessageId").jsonPrimitive.long)

            ctx.setDialogPinned(taskId, true, adminCtx)
            val pin = receiveEnvelope()
            assertEquals("TASK_CHAT_PIN", pin.getValue("category").jsonPrimitive.content)
            assertTrue(pin.getValue("isPinned").jsonPrimitive.boolean)
        }
    }

    @Test
    fun `typing action is broadcast to other sockets with throttle`() = testApp { _ ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cwmember2", "password", "Cw Member Two", emptyList())
        val taskId = createTask("T-CWW2", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        addTaskParticipant(taskId, member.id, "ASSIGNEE")

        val adminClient = loginClient("admin", "admin123")
        val memberClient = loginClient("cwmember2", "password")
        adminClient.webSocket("/api/tasks/$taskId/chat/ws") admin@{
            memberClient.webSocket("/api/tasks/$taskId/chat/ws") member@{
                this@member.send(Frame.Text("""{"action":"TYPING"}"""))
                this@member.send(Frame.Text("""{"action":"TYPING"}"""))

                val typing = this@admin.receiveEnvelope()
                assertEquals("TASK_CHAT_TYPING", typing.getValue("category").jsonPrimitive.content)
                assertEquals(taskId, typing.getValue("taskId").jsonPrimitive.long)
                assertEquals(member.id, typing.getValue("userId").jsonPrimitive.long)
                assertEquals("Cw Member Two", typing.getValue("fullName").jsonPrimitive.content)

                val second = withTimeoutOrNull(2_000) { this@admin.incoming.receive() }
                assertNull(second, "Typing must be throttled to one event per 3 seconds")
            }
        }
    }

    @Test
    fun `revoked access closes socket on workspace event`() = testApp { ctx ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cwmember3", "password", "Cw Member Three", emptyList())
        val taskId = createTask("T-CWW3", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        addTaskParticipant(taskId, member.id, "ASSIGNEE")
        val adminCtx = CurrentUser(adminUser.id, "admin", "Admin User", emptySet())
        val message = ctx.sendMessage(taskId, SendChatMessageRequest("До отзыва", "cww-key-v1"), adminCtx)

        val memberClient = loginClient("cwmember3", "password")
        memberClient.webSocket("/api/tasks/$taskId/chat/ws") {
            val messageEnvelope = receiveEnvelope()
            assertEquals("TASK_CHAT_MESSAGE", messageEnvelope.getValue("category").jsonPrimitive.content)

            removeTaskParticipant(taskId, member.id)
            ctx.addReaction(taskId, message.id, "👍", adminCtx)
            val reason = withTimeout(10_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
            assertEquals("", reason?.message)
        }
    }

    private suspend fun DefaultClientWebSocketSession.receiveEnvelope(): JsonObject {
        val frame = withTimeout(10_000) { incoming.receive() }
        assertTrue(frame is Frame.Text, "Expected text frame, got $frame")
        return json.parseToJsonElement((frame as Frame.Text).data.decodeToString()).jsonObject
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
        status: String,
        initiatorId: Long,
        assigneeId: Long,
        createdBy: Long
    ): Long {
        val dsl = application.getKoin().get<DSLContext>()
        return dsl.connectionResult { conn ->
            conn.prepareStatement(
                """
                INSERT INTO tasks (number, area, status, type, title, initiator_id, assignee_id, created_by)
                VALUES (?, 'PERSONAL', ?, 'GENERAL', ?, ?, ?, ?)
                RETURNING id
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, number)
                stmt.setString(2, status)
                stmt.setString(3, "Workspace ws task $number")
                stmt.setLong(4, initiatorId)
                stmt.setLong(5, assigneeId)
                stmt.setLong(6, createdBy)
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
