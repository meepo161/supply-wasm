package org.example.project

import com.typesafe.config.ConfigFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.config.HoconApplicationConfig
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
import kotlin.test.*

class ConversationExperienceRoutesTest {

    @Before
    fun reset() {
        TestDatabase.reset()
    }

    @Test
    fun `reply mentions and reaction toggle stay inside the conversation`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val author = createUser(admin, "cexreplya", "Reply Author")
        val member = createUser(admin, "cexreplyb", "Reply Member")
        val stranger = createUser(admin, "cexreplys", "Reply Stranger")
        val other = createUser(admin, "cexreplyo", "Reply Other")
        val authorClient = login("cexreplya")
        val memberClient = login("cexreplyb")
        val conversation = createGroup(authorClient, "Replies", listOf(member.id))
        val otherConversation = createGroup(authorClient, "Other", listOf(other.id))
        val original = send(authorClient, conversation.id, "Original", "cex-reply-1")
        val foreign = send(authorClient, otherConversation.id, "Foreign", "cex-reply-2")

        val rejected = authorClient.post("/api/conversations/${conversation.id}/messages") {
            jsonBody(SendChatMessageRequest("Bad reply @cexreplyb @cexreplys", "cex-reply-3", foreign.id))
        }
        assertEquals(HttpStatusCode.BadRequest, rejected.status)

        val response = authorClient.post("/api/conversations/${conversation.id}/messages") {
            jsonBody(SendChatMessageRequest("Hello @cexreplyb @cexreplys", "cex-reply-4", original.id))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val reply = response.bodyAs<ApiResponse<ConversationMessageDto>>().data!!
        assertEquals(original.id, reply.replyTo?.id)
        assertEquals("Original", reply.replyTo?.contentPreview)
        assertEquals(listOf(member.id), reply.mentionedUserIds)
        val notifications = memberClient.get("/api/notifications")
            .bodyAs<ApiResponse<NotificationPageDto>>().data!!.notifications
        assertEquals(2, notifications.size)
        assertEquals("NEW_CONVERSATION_MESSAGE", notifications.last().type)
        assertEquals(conversation.id, notifications.last().entityId)
        assertFalse(notifications.last().title.contains("Hello @"))

        val add = memberClient.post("/api/conversations/${conversation.id}/messages/${reply.id}/reactions") {
            jsonBody(ReactionRequest("👍"))
        }
        assertEquals(HttpStatusCode.OK, add.status, add.bodyAsText())
        val added = add.bodyAs<ApiResponse<List<ReactionSummaryDto>>>().data!!
        assertEquals(1, added.single().count)
        assertTrue(added.single().reactedByMe)
        assertEquals(listOf(member.id), added.single().reactedBy.map { it.userId })
        assertEquals("Reply Member", added.single().reactedBy.single().fullName)

        val remove = memberClient.post("/api/conversations/${conversation.id}/messages/${reply.id}/reactions") {
            jsonBody(ReactionRequest("👍"))
        }
        assertEquals(HttpStatusCode.OK, remove.status)
        assertTrue(remove.bodyAs<ApiResponse<List<ReactionSummaryDto>>>().data!!.isEmpty())
        val invalidEmoji = memberClient.post("/api/conversations/${conversation.id}/messages/${reply.id}/reactions") {
            jsonBody(ReactionRequest("not-an-emoji"))
        }
        assertEquals(HttpStatusCode.BadRequest, invalidEmoji.status)
        val crossConversation = memberClient.post(
            "/api/conversations/${conversation.id}/messages/${foreign.id}/reactions"
        ) { jsonBody(ReactionRequest("👍")) }
        assertEquals(HttpStatusCode.NotFound, crossConversation.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            login("cexreplys").get("/api/conversations/${conversation.id}/messages").status
        )
        assertNotNull(stranger)
    }

    @Test
    fun `read cursor is monotonic and history reports readByCount`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val author = createUser(admin, "cexreada", "Read Author")
        val member = createUser(admin, "cexreadb", "Read Member")
        val authorClient = login("cexreada")
        val memberClient = login("cexreadb")
        val conversation = createGroup(authorClient, "Read", listOf(member.id))
        val first = send(authorClient, conversation.id, "First", "cex-read-1")
        val second = send(authorClient, conversation.id, "Second", "cex-read-2")

        val readSecond = memberClient.post("/api/conversations/${conversation.id}/read") {
            jsonBody(MarkChatReadRequest(second.id))
        }
        assertEquals(HttpStatusCode.OK, readSecond.status, readSecond.bodyAsText())
        assertEquals(second.id, readSecond.bodyAs<ApiResponse<Long>>().data)
        val readFirst = memberClient.post("/api/conversations/${conversation.id}/read") {
            jsonBody(MarkChatReadRequest(first.id))
        }
        assertEquals(second.id, readFirst.bodyAs<ApiResponse<Long>>().data)
        assertEquals(2, dsl().fetchCountSql(
            "SELECT * FROM audit_log WHERE action = 'CONVERSATION_READ' AND entity_id = ?",
            conversation.id
        ))

        val history = memberClient.get("/api/conversations/${conversation.id}/messages")
            .bodyAs<ApiResponse<ConversationMessagePageDto>>().data!!
        assertEquals(listOf(1, 1), history.messages.map { it.readByCount })
        assertTrue(history.messages.all { it.readBy.map { reader -> reader.userId } == listOf(member.id) })
        assertTrue(history.messages.all { it.readBy.single().fullName == "Read Member" })
        val foreign = send(authorClient, createDirect(authorClient, member.id).id, "X", "cex-read-3")
        val invalid = memberClient.post("/api/conversations/${conversation.id}/read") {
            jsonBody(MarkChatReadRequest(foreign.id))
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertNotNull(author)
    }

    @Test
    fun `search escapes ILIKE symbols paginates and hides stranger content`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val member = createUser(admin, "cexsearchb", "Search Member")
        createUser(admin, "cexsearcha", "Search Author")
        createUser(admin, "cexsearchs", "Search Stranger")
        val authorClient = login("cexsearcha")
        val conversation = createGroup(authorClient, "Search", listOf(member.id))
        send(authorClient, conversation.id, "literal 100% match", "cex-search-1")
        val second = send(authorClient, conversation.id, "literal 100 percent match", "cex-search-2")
        send(authorClient, conversation.id, "under_score", "cex-search-3")
        send(authorClient, conversation.id, "underXscore", "cex-search-4")

        val percent = login("cexsearchb").get("/api/conversations/${conversation.id}/messages/search?q=100%25")
        assertEquals(HttpStatusCode.OK, percent.status, percent.bodyAsText())
        val escaped = percent.bodyAs<ApiResponse<ConversationMessagePageDto>>().data!!
        assertEquals(listOf("literal 100% match"), escaped.messages.map { it.content })
        assertFalse(escaped.hasMore)
        assertNull(escaped.nextAfter)

        val firstPage = login("cexsearchb").get(
            "/api/conversations/${conversation.id}/messages/search?q=literal&limit=1"
        ).bodyAs<ApiResponse<ConversationMessagePageDto>>().data!!
        assertEquals(1, firstPage.messages.size)
        assertTrue(firstPage.hasMore)
        assertNotNull(firstPage.nextAfter)
        val next = login("cexsearchb").get(
            "/api/conversations/${conversation.id}/messages/search?q=literal&after=${firstPage.nextAfter}&limit=10"
        ).bodyAs<ApiResponse<ConversationMessagePageDto>>().data!!
        assertEquals(second.id, next.messages.single().id)

        val underscore = login("cexsearchb").get("/api/conversations/${conversation.id}/messages/search?q=under_score")
            .bodyAs<ApiResponse<ConversationMessagePageDto>>().data!!
        assertEquals(listOf("under_score"), underscore.messages.map { it.content })
        val denied = login("cexsearchs").get("/api/conversations/${conversation.id}/messages/search?q=literal")
        assertEquals(HttpStatusCode.Forbidden, denied.status)
        assertFalse(denied.bodyAsText().contains("literal 100"))
    }

    @Test
    fun `edit stores revision detects conflicts and revoke keeps tombstone and audit`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        createUser(admin, "cexedita", "Edit Author")
        val member = createUser(admin, "cexeditb", "Edit Member")
        val authorClient = login("cexedita")
        val memberClient = login("cexeditb")
        val conversation = createGroup(authorClient, "Lifecycle", listOf(member.id))
        val message = send(authorClient, conversation.id, "Secret original", "cex-edit-1")

        val missingVersion = authorClient.post(
            "/api/conversations/${conversation.id}/messages/${message.id}/edit"
        ) { jsonBody(EditMessageRequest("No version")) }
        assertEquals(HttpStatusCode.BadRequest, missingVersion.status)
        assertEquals(HttpStatusCode.Forbidden, memberClient.post(
            "/api/conversations/${conversation.id}/messages/${message.id}/edit"
        ) { jsonBody(EditMessageRequest("Stolen", 0)) }.status)
        val editedResponse = authorClient.post("/api/conversations/${conversation.id}/messages/${message.id}/edit") {
            jsonBody(EditMessageRequest("Edited", 0))
        }
        assertEquals(HttpStatusCode.OK, editedResponse.status, editedResponse.bodyAsText())
        val edited = editedResponse.bodyAs<ApiResponse<ConversationMessageDto>>().data!!
        assertEquals("Edited", edited.content)
        assertNotNull(edited.editedAt)
        assertEquals(1L, edited.version)
        val stale = authorClient.post("/api/conversations/${conversation.id}/messages/${message.id}/edit") {
            jsonBody(EditMessageRequest("Stale", 0))
        }
        assertEquals(HttpStatusCode.Conflict, stale.status)
        val revisions = dsl().fetch("SELECT previous_content FROM conversation_message_revisions WHERE message_id = ?", message.id)
        assertEquals("Secret original", revisions.single().get("previous_content"))

        assertEquals(HttpStatusCode.Forbidden, memberClient.post(
            "/api/conversations/${conversation.id}/messages/${message.id}/revoke"
        ).status)
        val revokedResponse = authorClient.post("/api/conversations/${conversation.id}/messages/${message.id}/revoke")
        assertEquals(HttpStatusCode.OK, revokedResponse.status, revokedResponse.bodyAsText())
        val revoked = revokedResponse.bodyAs<ApiResponse<ConversationMessageDto>>().data!!
        assertEquals("Сообщение отозвано", revoked.content)
        assertNotNull(revoked.revokedAt)
        assertEquals(HttpStatusCode.Conflict, authorClient.post(
            "/api/conversations/${conversation.id}/messages/${message.id}/revoke"
        ).status)
        val historyText = memberClient.get("/api/conversations/${conversation.id}/messages").bodyAsText()
        assertTrue(historyText.contains("Сообщение отозвано"))
        assertFalse(historyText.contains("Secret original"))
        assertFalse(historyText.contains("Edited"))
        val searchText = memberClient.get("/api/conversations/${conversation.id}/messages/search?q=Secret").bodyAsText()
        assertFalse(searchText.contains("Secret original"))
        assertEquals(1, dsl().fetchCountSql("SELECT * FROM conversation_messages WHERE id = ?", message.id))
        assertEquals(2, dsl().fetchCountSql(
            "SELECT * FROM audit_log WHERE entity_id = ? AND action IN ('CONVERSATION_MESSAGE_EDIT', 'CONVERSATION_MESSAGE_REVOKE')",
            message.id
        ))
    }

    @Test
    fun `forward independently checks source and target and rejects TASK`() = testApp {
        val admin = jsonClient()
        ensureAdmin(admin)
        val author = createUser(admin, "cexfwda", "Forward Author")
        val member = createUser(admin, "cexfwdb", "Forward Member")
        val stranger = createUser(admin, "cexfwds", "Forward Stranger")
        val authorClient = login("cexfwda")
        val memberClient = login("cexfwdb")
        val source = createGroup(authorClient, "Source", listOf(member.id))
        val target = createGroup(memberClient, "Target", listOf(stranger.id))
        val privateSource = createDirect(authorClient, stranger.id)
        val message = send(authorClient, source.id, "Forward me", "cex-fwd-1")
        val secret = send(authorClient, privateSource.id, "Do not leak", "cex-fwd-2")

        val deniedSource = memberClient.post("/api/conversations/${privateSource.id}/messages/${secret.id}/forward") {
            jsonBody(ForwardMessageRequest(ConversationKind.GROUP, target.id, "cex-fwd-3"))
        }
        assertEquals(HttpStatusCode.Forbidden, deniedSource.status)
        assertFalse(deniedSource.bodyAsText().contains("Do not leak"))
        val deniedTarget = authorClient.post("/api/conversations/${source.id}/messages/${message.id}/forward") {
            jsonBody(ForwardMessageRequest(ConversationKind.GROUP, target.id, "cex-fwd-4"))
        }
        assertEquals(HttpStatusCode.Forbidden, deniedTarget.status)
        val forwardedResponse = memberClient.post("/api/conversations/${source.id}/messages/${message.id}/forward") {
            jsonBody(ForwardMessageRequest(ConversationKind.GROUP, target.id, "cex-fwd-5"))
        }
        assertEquals(HttpStatusCode.Created, forwardedResponse.status, forwardedResponse.bodyAsText())
        val forwarded = forwardedResponse.bodyAs<ApiResponse<ConversationMessageDto>>().data!!
        assertEquals("Forward me", forwarded.content)
        assertEquals(target.id, forwarded.conversationId)
        assertEquals(ConversationKind.GROUP, forwarded.source?.kind)
        assertEquals(message.id, forwarded.source?.messageId)
        assertEquals(author.id, forwarded.source?.author?.userId)
        assertEquals(message.id, forwardedResponse.bodyAs<ApiResponse<ConversationMessageDto>>().data!!.source?.messageId)
        val repeat = memberClient.post("/api/conversations/${source.id}/messages/${message.id}/forward") {
            jsonBody(ForwardMessageRequest(ConversationKind.GROUP, target.id, "cex-fwd-5"))
        }
        assertEquals(HttpStatusCode.Created, repeat.status)
        assertEquals(forwarded.id, repeat.bodyAs<ApiResponse<ConversationMessageDto>>().data!!.id)
        assertEquals(1, memberClient.get("/api/conversations/${target.id}/messages")
            .bodyAs<ApiResponse<ConversationMessagePageDto>>().data!!.messages.size)
        assertEquals(1, dsl().fetchCountSql(
            "SELECT * FROM audit_log WHERE action = 'CONVERSATION_MESSAGE_FORWARD' AND entity_id = ?",
            message.id
        ))
        val taskTarget = memberClient.post("/api/conversations/${source.id}/messages/${message.id}/forward") {
            jsonBody(ForwardMessageRequest(ConversationKind.TASK, 1, "cex-fwd-6"))
        }
        assertEquals(HttpStatusCode.BadRequest, taskTarget.status)
        assertNotNull(stranger)
    }

    @Test
    fun `project owner and member retain extended message access`() = testApp {
        val admin = jsonClient()
        val owner = ensureAdmin(admin)
        val member = createUser(admin, "cexprojm", "Project Member")
        val projectResponse = admin.post("/api/projects") {
            jsonBody(CreateProjectRequest(
                name = "Experience project",
                projectType = ProjectType.ORDER_DEVELOPMENT,
                ownerId = owner.id
            ))
        }
        val project = projectResponse.bodyAs<ApiResponse<ProjectDto>>().data!!
        assertEquals(HttpStatusCode.OK, admin.post("/api/projects/${project.id}/members") {
            jsonBody(AddProjectMemberRequest(member.id))
        }.status)
        val ownerClient = login("admin", "admin123")
        val chat = ownerClient.post("/api/projects/${project.id}/chat")
            .bodyAs<ApiResponse<ConversationDto>>().data!!
        val memberClient = login("cexprojm")
        val message = send(memberClient, chat.id, "Project hello @admin", "cex-project-1")
        assertTrue(message.mentionedUserIds.contains(owner.id))
        val reaction = ownerClient.post("/api/conversations/${chat.id}/messages/${message.id}/reactions") {
            jsonBody(ReactionRequest("✅"))
        }
        assertEquals(HttpStatusCode.OK, reaction.status, reaction.bodyAsText())

        assertEquals(HttpStatusCode.OK, admin.delete("/api/projects/${project.id}/members/${member.id}").status)
        assertEquals(HttpStatusCode.Forbidden, memberClient.post("/api/conversations/${chat.id}/messages") {
            jsonBody(SendChatMessageRequest("After removal", "cex-project-2"))
        }.status)
        assertEquals(HttpStatusCode.Forbidden, memberClient.post(
            "/api/conversations/${chat.id}/messages/${message.id}/reactions"
        ) { jsonBody(ReactionRequest("👍")) }.status)
        assertEquals(HttpStatusCode.Forbidden, memberClient.post(
            "/api/conversations/${chat.id}/messages/${message.id}/edit"
        ) { jsonBody(EditMessageRequest("After removal", 0)) }.status)
        assertEquals(HttpStatusCode.Forbidden, memberClient.post(
            "/api/conversations/${chat.id}/messages/${message.id}/revoke"
        ).status)
        assertEquals(HttpStatusCode.Forbidden, memberClient.post("/api/conversations/${chat.id}/read") {
            jsonBody(MarkChatReadRequest(message.id))
        }.status)
    }

    private fun testApp(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        environment {
            config = HoconApplicationConfig(ConfigFactory.parseString(
                """
                ktor { deployment { port = 0 } application { modules = [org.example.project.ApplicationKt.module] } }
                database { url = "jdbc:postgresql://localhost:5433/erp_test" user = "erp" password = "erp" }
                session { ttlMinutes = 60 cookieName = "erp_session" }
                security { allowedOrigins = "http://localhost:8080,http://localhost:8081" cookieSecure = false }
                storage { path = "./.local/test-storage" }
                """.trimIndent()
            ))
        }
        block()
    }

    private fun ApplicationTestBuilder.dsl(): DSLContext = application.getKoin().get()
    private fun ApplicationTestBuilder.jsonClient() = createClient { install(HttpCookies) }

    private suspend fun ApplicationTestBuilder.ensureAdmin(client: HttpClient): UserDto {
        client.post("/api/admin/bootstrap") { jsonBody(BootstrapRequest("admin", "admin123", "Admin User")) }
        assertEquals(HttpStatusCode.OK, client.post("/api/auth/login") {
            jsonBody(LoginRequest("admin", "admin123"))
        }.status)
        return client.get("/api/auth/me").bodyAs<ApiResponse<MeResponse>>().data!!.user
    }

    private suspend fun ApplicationTestBuilder.createUser(admin: HttpClient, login: String, fullName: String): UserDto {
        val role = admin.post("/api/roles") {
            jsonBody(CreateRoleRequest(login.uppercase(), fullName, permissions = emptyList()))
        }.bodyAs<ApiResponse<RoleDto>>().data!!
        val response = admin.post("/api/users") {
            jsonBody(CreateUserRequest(login, "password", fullName, roleIds = listOf(role.id)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<UserDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.login(login: String, password: String = "password"): HttpClient {
        val client = jsonClient()
        assertEquals(HttpStatusCode.OK, client.post("/api/auth/login") { jsonBody(LoginRequest(login, password)) }.status)
        return client
    }

    private suspend fun ApplicationTestBuilder.createDirect(client: HttpClient, otherUserId: Long): ConversationDto {
        val response = client.post("/api/conversations") {
            jsonBody(CreateConversationRequest(ConversationKind.DIRECT, memberIds = listOf(otherUserId)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ConversationDto>>().data!!
    }

    private suspend fun ApplicationTestBuilder.createGroup(client: HttpClient, title: String, memberIds: List<Long>): ConversationDto {
        val response = client.post("/api/conversations") {
            jsonBody(CreateConversationRequest(ConversationKind.GROUP, title = title, memberIds = memberIds))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ConversationDto>>().data!!
    }

    private suspend fun send(client: HttpClient, conversationId: Long, content: String, key: String): ConversationMessageDto {
        val response = client.post("/api/conversations/$conversationId/messages") {
            jsonBody(SendChatMessageRequest(content, key))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return response.bodyAs<ApiResponse<ConversationMessageDto>>().data!!
    }

    private suspend inline fun <reified T> HttpRequestBuilder.jsonBody(value: T) {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(serializer<T>(), value))
    }

    private suspend inline fun <reified T> HttpResponse.bodyAs(): T = json.decodeFromString(serializer<T>(), bodyAsText())

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        @JvmStatic @BeforeClass fun setup() = TestDatabase.init()
        @JvmStatic @AfterClass fun tearDown() = TestDatabase.destroy()
    }
}
