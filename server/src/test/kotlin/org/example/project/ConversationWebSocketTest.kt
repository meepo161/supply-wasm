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
import org.example.project.service.ConversationService
import org.jooq.DSLContext
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.koin.ktor.ext.getKoin
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationWebSocketTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `participant receives new conversation message over websocket`() = testApp { ctx ->
        val admin = newClient()
        ensureAdmin(admin)
        val author = createUserWithRole(admin, "cwsauthor1", "password", "Cws Author One", emptyList())
        val recipient = createUserWithRole(admin, "cwsrecip1", "password", "Cws Recipient One", emptyList())
        val conversationId = createDirectConversation(author.id, recipient.id, "cwsauthor1")
        val authorUser = CurrentUser(author.id, "cwsauthor1", "Cws Author One", emptySet())

        val recipientClient = loginClient("cwsrecip1", "password")
        recipientClient.webSocket("/api/conversations/$conversationId/ws") {
            ctx.sendMessage(
                conversationId,
                SendChatMessageRequest("Realtime conv hello", "cws-key-1"),
                authorUser
            )
            val envelope = receiveEnvelope()
            assertEquals("CONVERSATION_MESSAGE", envelope.getValue("category").jsonPrimitive.content)
            val message = json.decodeFromJsonElement(
                ConversationMessageDto.serializer(),
                envelope.getValue("message")
            )
            assertEquals("Realtime conv hello", message.content)
            assertEquals(conversationId, message.conversationId)
            assertEquals(author.id, message.author?.userId)
            assertEquals(message.id, envelope.getValue("id").jsonPrimitive.long)
        }
    }

    @Test
    fun `stranger and anonymous handshakes are refused without data`() = testApp { _ ->
        val admin = newClient()
        ensureAdmin(admin)
        val author = createUserWithRole(admin, "cwsauthor2", "password", "Cws Author Two", emptyList())
        val recipient = createUserWithRole(admin, "cwsrecip2", "password", "Cws Recipient Two", emptyList())
        createUserWithRole(admin, "cwsstranger2", "password", "Cws Stranger Two", emptyList())
        val conversationId = createDirectConversation(author.id, recipient.id, "cwsauthor2")

        assertHandshakeRefused(newClient(), conversationId)
        assertHandshakeRefused(loginClient("cwsstranger2", "password"), conversationId)

        val recipientClient = loginClient("cwsrecip2", "password")
        val forbiddenUpgrade = recipientClient.get("/api/conversations/$conversationId/ws") {
            header(HttpHeaders.Origin, "http://evil.example")
        }
        assertEquals(HttpStatusCode.Forbidden, forbiddenUpgrade.status)
    }

    @Test
    fun `reconnect with after receives missed messages once without duplicates`() = testApp { ctx ->
        val admin = newClient()
        ensureAdmin(admin)
        val author = createUserWithRole(admin, "cwsauthor3", "password", "Cws Author Three", emptyList())
        val recipient = createUserWithRole(admin, "cwsrecip3", "password", "Cws Recipient Three", emptyList())
        val conversationId = createDirectConversation(author.id, recipient.id, "cwsauthor3")
        val authorUser = CurrentUser(author.id, "cwsauthor3", "Cws Author Three", emptySet())

        val recipientClient = loginClient("cwsrecip3", "password")

        var firstId = 0L
        recipientClient.webSocket("/api/conversations/$conversationId/ws") {
            ctx.sendMessage(conversationId, SendChatMessageRequest("First", "cws-key-r1"), authorUser)
            val envelope = receiveEnvelope()
            firstId = json.decodeFromJsonElement(
                ConversationMessageDto.serializer(),
                envelope.getValue("message")
            ).id
        }

        ctx.sendMessage(conversationId, SendChatMessageRequest("Second", "cws-key-r2"), authorUser)
        ctx.sendMessage(conversationId, SendChatMessageRequest("Third", "cws-key-r3"), authorUser)

        recipientClient.webSocket("/api/conversations/$conversationId/ws?after=$firstId") {
            val second = json.decodeFromJsonElement(
                ConversationMessageDto.serializer(),
                receiveEnvelope().getValue("message")
            )
            val third = json.decodeFromJsonElement(
                ConversationMessageDto.serializer(),
                receiveEnvelope().getValue("message")
            )
            assertEquals("Second", second.content)
            assertEquals("Third", third.content)
            assertTrue(second.id > firstId)
            assertTrue(third.id > second.id)

            ctx.sendMessage(conversationId, SendChatMessageRequest("Fourth", "cws-key-r4"), authorUser)
            val fourth = json.decodeFromJsonElement(
                ConversationMessageDto.serializer(),
                receiveEnvelope().getValue("message")
            )
            assertEquals("Fourth", fourth.content)
            assertTrue(fourth.id > third.id)

            val receivedIds = listOf(second.id, third.id, fourth.id)
            assertEquals(receivedIds.size, receivedIds.toSet().size)
        }
    }

    @Test
    fun `message of another conversation is not delivered`() = testApp { ctx ->
        val admin = newClient()
        ensureAdmin(admin)
        val author = createUserWithRole(admin, "cwsauthor4", "password", "Cws Author Four", emptyList())
        val recipient = createUserWithRole(admin, "cwsrecip4", "password", "Cws Recipient Four", emptyList())
        val other = createUserWithRole(admin, "cwsother4", "password", "Cws Other Four", emptyList())
        val firstId = createDirectConversation(author.id, recipient.id, "cwsauthor4")
        val secondId = createDirectConversation(author.id, other.id, "cwsauthor4")
        val authorUser = CurrentUser(author.id, "cwsauthor4", "Cws Author Four", emptySet())

        val recipientClient = loginClient("cwsrecip4", "password")
        recipientClient.webSocket("/api/conversations/$firstId/ws") {
            ctx.sendMessage(secondId, SendChatMessageRequest("Other conv", "cws-key-o1"), authorUser)
            val frame = withTimeoutOrNull(2_000) { incoming.receive() }
            assertNull(frame, "Socket of conversation $firstId must not receive messages of conversation $secondId")
        }
    }

    @Test
    fun `removed project member closes project chat socket on next event`() = testApp { ctx ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cwsmember5", "password", "Cws Member Five", emptyList())
        val project = createProject(admin, adminUser.id, listOf(member.id))
        val adminUserCtx = CurrentUser(adminUser.id, "admin", "Admin User", emptySet())

        val memberClient = loginClient("cwsmember5", "password")
        val chat = memberClient.post("/api/projects/${project.id}/chat")
            .bodyAs<ApiResponse<ConversationDto>>().data!!

        memberClient.webSocket("/api/conversations/${chat.id}/ws") {
            removeProjectMember(project.id, member.id)
            ctx.sendMessage(chat.id, SendChatMessageRequest("After removal", "cws-key-v1"), adminUserCtx)
            expectNeutralClose()
        }
    }

    @Test
    fun `repeated message with same idempotency key publishes once`() = testApp { ctx ->
        val admin = newClient()
        ensureAdmin(admin)
        val author = createUserWithRole(admin, "cwsauthor6", "password", "Cws Author Six", emptyList())
        val recipient = createUserWithRole(admin, "cwsrecip6", "password", "Cws Recipient Six", emptyList())
        val conversationId = createDirectConversation(author.id, recipient.id, "cwsauthor6")
        val authorUser = CurrentUser(author.id, "cwsauthor6", "Cws Author Six", emptySet())

        val recipientClient = loginClient("cwsrecip6", "password")
        recipientClient.webSocket("/api/conversations/$conversationId/ws") {
            val first = ctx.sendMessage(conversationId, SendChatMessageRequest("Once", "cws-key-i1"), authorUser)
            val second = ctx.sendMessage(conversationId, SendChatMessageRequest("Once", "cws-key-i1"), authorUser)
            assertEquals(first.id, second.id)

            val envelope = receiveEnvelope()
            assertEquals("CONVERSATION_MESSAGE", envelope.getValue("category").jsonPrimitive.content)
            val duplicate = withTimeoutOrNull(2_000) { incoming.receive() }
            assertNull(duplicate, "Repeated message must not be published again")
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

    private suspend fun assertHandshakeRefused(client: HttpClient, conversationId: Long) {
        val exception = assertFailsWith<IllegalStateException> {
            client.webSocket("/api/conversations/$conversationId/ws") { }
        }
        val message = exception.message ?: ""
        assertTrue(
            message.contains("403") || message.contains("WebSocket connection failed"),
            "Handshake must be refused before upgrade, got: $message"
        )
    }

    private fun testApp(block: suspend ApplicationTestBuilder.(ConversationService) -> Unit) {
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

    private suspend fun ApplicationTestBuilder.createDirectConversation(
        userAId: Long,
        userBId: Long,
        creatorLogin: String
    ): Long {
        val client = loginClient(creatorLogin, "password")
        val response = client.post("/api/conversations") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateConversationRequest(ConversationKind.DIRECT, memberIds = listOf(userBId))))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ConversationDto>>().data!!.id
    }

    private suspend fun ApplicationTestBuilder.createProject(
        client: HttpClient,
        ownerId: Long,
        memberIds: List<Long> = emptyList()
    ): ProjectDto {
        val response = client.post("/api/projects") {
            contentType(ContentType.Application.Json)
            setBody(stringify(CreateProjectRequest(
                name = "Ws Chat Project",
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

    private fun ApplicationTestBuilder.removeProjectMember(projectId: Long, userId: Long) {
        val dsl = application.getKoin().get<DSLContext>()
        dsl.execute(
            "DELETE FROM project_members WHERE project_id = ? AND user_id = ?",
            projectId,
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
