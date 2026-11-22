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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.onSubscription
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
import org.example.project.realtime.ChatEventBus
import org.example.project.service.ChatService
import org.jooq.DSLContext
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.koin.ktor.ext.getKoin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
            ctx.markRead(taskId, MarkChatReadRequest(message.id), adminCtx)
            ctx.markRead(taskId, MarkChatReadRequest(0), adminCtx)

            ctx.setDialogPinned(taskId, true, adminCtx)
            val pin = receiveEnvelope()
            assertEquals("TASK_CHAT_PIN", pin.getValue("category").jsonPrimitive.content)
            assertTrue(pin.getValue("isPinned").jsonPrimitive.boolean)
            ctx.setDialogPinned(taskId, true, adminCtx)
            val eventCounts = application.getKoin().get<DSLContext>().fetch(
                "SELECT category, COUNT(*) AS count FROM task_chat_event_log WHERE task_id = ? GROUP BY category",
                taskId
            ).associate { it.get("category") as String to (it.get("count") as Number).toLong() }
            assertEquals(1L, eventCounts["READ"])
            assertEquals(1L, eventCounts["PIN"])
            assertNull(withTimeoutOrNull(500) { incoming.receive() }, "No-op read/pin must not publish")

            ctx.removeReaction(taskId, message.id, "👍", adminCtx)
            val off = receiveEnvelope()
            assertEquals("REACTION", off.getValue("type").jsonPrimitive.content)
            assertTrue(off.getValue("reactions").jsonArray.isEmpty())
            ctx.addReaction(taskId, message.id, "👍", adminCtx)
            val onAgain = receiveEnvelope()
            assertEquals(1L, onAgain.getValue("reactions").jsonArray.single().jsonObject
                .getValue("count").jsonPrimitive.long)
            assertEquals(3, application.getKoin().get<DSLContext>().fetchCountSql(
                "SELECT * FROM task_chat_event_log WHERE task_id = ? AND category = 'REACTION'", taskId
            ))
            ctx.setDialogPinned(taskId, false, adminCtx)
            val unpin = receiveEnvelope()
            assertFalse(unpin.getValue("isPinned").jsonPrimitive.boolean)
            ctx.setDialogPinned(taskId, false, adminCtx)
            assertEquals(2, application.getKoin().get<DSLContext>().fetchCountSql(
                "SELECT * FROM task_chat_event_log WHERE task_id = ? AND category = 'PIN'", taskId
            ))
            assertNull(withTimeoutOrNull(500) { incoming.receive() }, "No-op unpin must not publish")
        }
    }

    @Test
    fun `concurrent edit and reaction after replay are both delivered with increasing event ids`() = testApp { ctx ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask("T-CW-CONCURRENT", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        val adminCtx = CurrentUser(adminUser.id, "admin", "Admin User", emptySet())
        val message = ctx.sendMessage(taskId, SendChatMessageRequest("Concurrent", "cw-concurrent-1"), adminCtx)
        val adminClient = loginClient("admin", "admin123")
        adminClient.webSocket("/api/tasks/$taskId/chat/ws?afterEvent=0") {
            val replay = receiveEnvelope()
            assertEquals("MESSAGE", replay.getValue("type").jsonPrimitive.content)
            coroutineScope {
                launch {
                    ctx.editMessage(taskId, message.id, EditMessageRequest("Edited concurrently", 0), adminCtx)
                }
                launch {
                    ctx.addReaction(taskId, message.id, "👍", adminCtx)
                }
            }
            val live = List(2) { receiveEnvelope() }
            assertEquals(setOf("EDITED", "REACTION"), live.map { it.getValue("type").jsonPrimitive.content }.toSet())
            val eventIds = live.map { it.getValue("eventId").jsonPrimitive.long }
            assertTrue(eventIds[0] < eventIds[1], "Live event ids must be strictly increasing: $eventIds")
            assertTrue(eventIds.all { it > replay.getValue("eventId").jsonPrimitive.long })
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

    @Test
    fun `afterEvent replays durable lifecycle in order with viewer reactions and tombstones`() = testApp { ctx ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        val member = createUserWithRole(admin, "cwreplay", "password", "Replay Member", emptyList())
        val taskId = createTask("T-CW-REPLAY", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        addTaskParticipant(taskId, member.id, "ASSIGNEE")
        val adminCtx = CurrentUser(adminUser.id, "admin", "Admin User", emptySet())
        val message = ctx.sendMessage(taskId, SendChatMessageRequest("Replay secret", "cw-replay-1"), adminCtx)
        ctx.editMessage(taskId, message.id, EditMessageRequest("Replay edited", 0), adminCtx)
        ctx.addReaction(taskId, message.id, "👍", adminCtx)
        ctx.revokeMessage(taskId, message.id, RevokeMessageRequest(1), adminCtx)
        ctx.markRead(taskId, MarkChatReadRequest(message.id), adminCtx)
        ctx.setDialogPinned(taskId, true, adminCtx)
        ctx.setDialogPinned(taskId, false, adminCtx)

        val adminClient = loginClient("admin", "admin123")
        adminClient.webSocket("/api/tasks/$taskId/chat/ws?afterEvent=0") {
            val envelopes = List(7) { receiveEnvelope() }
            assertEquals(
                listOf("MESSAGE", "EDITED", "REACTION", "REVOKED", "READ", "PIN", "PIN"),
                envelopes.map { it.getValue("type").jsonPrimitive.content }
            )
            val eventIds = envelopes.map { it.getValue("eventId").jsonPrimitive.long }
            assertEquals(eventIds.sorted(), eventIds)
            assertEquals(7, eventIds.distinct().size)
            assertTrue(envelopes[5].getValue("isPinned").jsonPrimitive.boolean)
            assertFalse(envelopes[6].getValue("isPinned").jsonPrimitive.boolean)
            val reaction = envelopes[2].getValue("reactions").jsonArray.single().jsonObject
            assertTrue(reaction.getValue("reactedByMe").jsonPrimitive.boolean)
            val revoked = envelopes[3].getValue("message").jsonObject
            assertEquals("Сообщение отозвано", revoked.getValue("content").jsonPrimitive.content)
            assertEquals(2L, revoked.getValue("version").jsonPrimitive.long)
            assertFalse(envelopes.toString().contains("Replay secret"))
            assertFalse(envelopes.toString().contains("Replay edited"))
        }
    }

    @Test
    fun `closed task rejects typing while read replay remains available`() = testApp { ctx ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask("T-CW-TYPING-RO", "CLOSED", adminUser.id, adminUser.id, adminUser.id)
        addTaskParticipant(taskId, adminUser.id, "INITIATOR")
        val adminCtx = CurrentUser(adminUser.id, "admin", "Admin User", emptySet())
        val messageId = dslSendMessage(taskId, adminUser.id, "Closed history", "cw-typing-ro")
        ctx.markRead(taskId, MarkChatReadRequest(messageId), adminCtx)
        val adminClient = loginClient("admin", "admin123")
        adminClient.webSocket("/api/tasks/$taskId/chat/ws?afterEvent=0") {
            val read = receiveEnvelope()
            assertEquals("READ", read.getValue("type").jsonPrimitive.content)
            send(Frame.Text("""{"action":"TYPING"}"""))
            val reason = withTimeout(10_000) { closeReason.await() }
            assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
        }
    }

    @Test
    fun `legacy after cursor still replays only newer message envelopes`() = testApp { ctx ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask("T-CW-LEGACY-AFTER", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        val adminCtx = CurrentUser(adminUser.id, "admin", "Admin User", emptySet())
        val first = ctx.sendMessage(taskId, SendChatMessageRequest("First", "cw-legacy-1"), adminCtx)
        val second = ctx.sendMessage(taskId, SendChatMessageRequest("Second", "cw-legacy-2"), adminCtx)
        val adminClient = loginClient("admin", "admin123")
        adminClient.webSocket("/api/tasks/$taskId/chat/ws?after=${first.id}") {
            val envelope = receiveEnvelope()
            assertEquals("TASK_CHAT_MESSAGE", envelope.getValue("category").jsonPrimitive.content)
            assertEquals("MESSAGE", envelope.getValue("type").jsonPrimitive.content)
            assertEquals(second.id, envelope.getValue("id").jsonPrimitive.long)
            assertEquals("Second", envelope.getValue("message").jsonObject.getValue("content").jsonPrimitive.content)
            assertTrue(envelope.getValue("eventId") is kotlinx.serialization.json.JsonNull)
            assertNull(withTimeoutOrNull(500) { incoming.receive() })
        }
    }

    @Test
    fun `overflow unblocks ordered command and idempotent retry with explicit signal`() = testApp { ctx ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask("T-CW-OVERFLOW", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        val adminCtx = CurrentUser(adminUser.id, "admin", "Admin User", emptySet())
        val bus = application.getKoin().get<ChatEventBus>()
        coroutineScope {
            val slowSubscribed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val releaseSlowCollector = kotlinx.coroutines.CompletableDeferred<Unit>()
            val slowCollector = launch {
                bus.events
                    .onSubscription { slowSubscribed.complete(Unit) }
                    .collect { releaseSlowCollector.await() }
            }
            slowSubscribed.await()
            val overflowSignals = Channel<Long>(1)
            val overflowSubscribed = kotlinx.coroutines.CompletableDeferred<Unit>()
            val overflowCollector = launch {
                bus.overflow
                    .onSubscription { overflowSubscribed.complete(Unit) }
                    .collect { overflowSignals.send(it.taskId) }
            }
            overflowSubscribed.await()
            repeat(65) { index ->
                bus.publishEvent(
                    ChatEventBus.Event(index + 1L, "TASK_CHAT_TYPING", "TYPING", taskId)
                )
            }

            val message = withTimeout(2_000) {
                ctx.sendMessage(taskId, SendChatMessageRequest("Durable despite overflow", "cw-overflow-1"), adminCtx)
            }
            val retry = withTimeout(1_000) {
                ctx.sendMessage(taskId, SendChatMessageRequest("Durable despite overflow", "cw-overflow-1"), adminCtx)
            }
            assertEquals(message.id, retry.id)
            assertEquals(taskId, withTimeout(1_000) { overflowSignals.receive() })
            assertEquals(1, application.getKoin().get<DSLContext>().fetchCountSql(
                "SELECT * FROM task_chat_event_log WHERE task_id = ?", taskId
            ))
            releaseSlowCollector.complete(Unit)
            slowCollector.cancel()
            overflowCollector.cancel()
        }
    }

    @Test
    fun `event published during replay is delivered once in event id order`() = testApp { ctx ->
        val admin = newClient()
        val adminUser = ensureAdmin(admin)
        val taskId = createTask("T-CW-REPLAY-GAP", "IN_PROGRESS", adminUser.id, adminUser.id, adminUser.id)
        val adminCtx = CurrentUser(adminUser.id, "admin", "Admin User", emptySet())
        repeat(201) { index ->
            ctx.sendMessage(taskId, SendChatMessageRequest("Replay $index", "cw-gap-$index"), adminCtx)
        }
        val adminClient = loginClient("admin", "admin123")
        adminClient.webSocket("/api/tasks/$taskId/chat/ws?afterEvent=0") {
            val first = receiveEnvelope()
            val gap = ctx.sendMessage(taskId, SendChatMessageRequest("During replay", "cw-gap-live"), adminCtx)
            val envelopes = listOf(first) + List(201) { receiveEnvelope() }
            val eventIds = envelopes.map { it.getValue("eventId").jsonPrimitive.long }
            assertEquals(eventIds.sorted(), eventIds)
            assertEquals(eventIds.distinct(), eventIds)
            assertEquals(gap.id, envelopes.last().getValue("message").jsonObject.getValue("id").jsonPrimitive.long)
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

    private fun ApplicationTestBuilder.dslSendMessage(
        taskId: Long,
        authorId: Long,
        content: String,
        key: String
    ): Long = application.getKoin().get<DSLContext>().fetchOne(
        """
        INSERT INTO task_chat_messages (task_id, author_id, type, content, idempotency_key)
        VALUES (?, ?, 'USER', ?, ?) RETURNING id
        """.trimIndent(),
        taskId, authorId, content, key
    )!!.get(0, Long::class.java)

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
