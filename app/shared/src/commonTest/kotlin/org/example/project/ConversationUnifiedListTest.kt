package org.example.project

import org.example.project.data.conversationKey
import org.example.project.data.conversationMessagePath
import org.example.project.data.dispositionSafe
import org.example.project.data.isSameConversation
import org.example.project.data.isVisibleTo
import org.example.project.data.toConversationMessage
import org.example.project.data.toVirtualConversation
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ChatMessageType
import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind
import org.example.project.dto.ProjectDto
import org.example.project.dto.ProjectMemberDto
import org.example.project.dto.TaskArea
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus
import org.example.project.dto.TaskWorkType
import org.example.project.dto.UserDto
import org.example.project.dto.UserOptionDto
import org.example.project.ui.conversations.formatAttachmentSize
import org.example.project.ui.conversations.toConversationDto
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationUnifiedListTest {

    private fun user(id: Long, vararg permissions: String) = UserDto(
        id = id,
        login = "user$id",
        fullName = "User $id",
        permissions = permissions.toList()
    )

    private fun task(
        id: Long,
        status: TaskStatus = TaskStatus.IN_PROGRESS,
        initiatorId: Long = 1,
        assigneeId: Long? = null,
        observerIds: List<Long> = emptyList()
    ) = TaskDto(
        id = id,
        number = "T-2026-$id",
        area = TaskArea.PERSONAL,
        status = status,
        type = TaskWorkType.GENERAL,
        title = "Задача $id",
        initiatorId = initiatorId,
        assigneeId = assigneeId,
        observerIds = observerIds,
        createdBy = initiatorId
    )

    private val options = listOf(
        UserOptionDto(1, "Иван Петров"),
        UserOptionDto(2, "Анна Смирнова")
    )

    @Test
    fun `virtual task conversation uses negative id and task chat coordinates`() {
        val conversation = task(id = 5, assigneeId = 2).toVirtualConversation(user(2), options)
        assertEquals(-5L, conversation.id)
        assertEquals(ConversationKind.TASK, conversation.kind)
        assertEquals(5L, conversation.taskId)
        assertEquals("T-2026-5 · Задача 5", conversation.title)
        assertEquals(listOf(1L, 2L), conversation.members.map { it.userId })
        assertEquals("Анна Смирнова", conversation.members[1].fullName)
        assertTrue(conversation.canWrite)
    }

    @Test
    fun `closed task chat is read only even for participant`() {
        val closed = task(id = 6, status = TaskStatus.CLOSED, assigneeId = 2)
        assertFalse(closed.toVirtualConversation(user(2), options).canWrite)
        assertFalse(closed.toVirtualConversation(user(9, "tasks:write_any"), options).canWrite)
    }

    @Test
    fun `observer cannot write but write_any can`() {
        val observed = task(id = 6, observerIds = listOf(3))
        assertFalse(observed.toVirtualConversation(user(3), options).canWrite)
        assertTrue(observed.toVirtualConversation(user(4, "tasks:write_any"), options).canWrite)
    }

    @Test
    fun `virtual project conversation carries project id and members`() {
        val project = ProjectDto(
            id = 7,
            number = "PRJ-2026-7",
            name = "Проект 7",
            ownerId = 1,
            createdBy = 1,
            members = listOf(ProjectMemberDto(1, "Иван Петров"), ProjectMemberDto(2, "Анна Смирнова"))
        )
        val conversation = project.toVirtualConversation()
        assertEquals(-7L, conversation.id)
        assertEquals(ConversationKind.PROJECT, conversation.kind)
        assertEquals(7L, conversation.projectId)
        assertEquals("PRJ-2026-7 · Проект 7", conversation.title)
        assertEquals(2, conversation.members.size)
        assertTrue(conversation.canWrite)
        assertTrue(project.isVisibleTo(user(1)))
        assertTrue(project.isVisibleTo(user(2)))
        assertFalse(project.isVisibleTo(user(9)))
    }

    @Test
    fun `task chat message maps to conversation message with negative conversation id`() {
        val chatMessage = ChatMessageDto(
            id = 10,
            taskId = 5,
            author = null,
            type = ChatMessageType.USER,
            content = "привет",
            createdAt = "2026-11-17T10:00:00Z"
        )
        val mapped = chatMessage.toConversationMessage()
        assertEquals(10L, mapped.id)
        assertEquals(-5L, mapped.conversationId)
        assertEquals("привет", mapped.content)
        assertTrue(mapped.attachments.isEmpty())
    }

    @Test
    fun `conversation keys do not collide across kinds`() {
        assertEquals("TASK:-5", conversationKey(ConversationKind.TASK, -5))
        assertEquals("PROJECT:-5", conversationKey(ConversationKind.PROJECT, -5))
        assertFalse(conversationKey(ConversationKind.TASK, -5) == conversationKey(ConversationKind.PROJECT, -5))
    }

    @Test
    fun `late response guard checks id and kind`() {
        val selected = ConversationDto(id = 3, kind = ConversationKind.GROUP)
        assertTrue(isSameConversation(selected, 3, ConversationKind.GROUP))
        assertFalse(isSameConversation(selected, 3, ConversationKind.DIRECT))
        assertFalse(isSameConversation(selected, 4, ConversationKind.GROUP))
        assertFalse(isSameConversation(null, 3, ConversationKind.GROUP))
    }

    @Test
    fun `disposition safe file name strips quotes slashes and newlines`() {
        assertEquals("repo_rt.pdf", "repo\"rt.pdf".dispositionSafe())
        assertEquals("folder_file.txt", "folder\\file.txt".dispositionSafe())
        assertEquals("a_b.txt", "a\nb.txt".dispositionSafe())
    }

    @Test
    fun `message path keeps virtual task id sign handling in one place`() {
        assertEquals("/api/tasks/3/chat/messages", conversationMessagePath(-3))
        assertEquals("/api/conversations/3/messages", conversationMessagePath(3))
    }

    @Test
    fun `attachment size formatting`() {
        assertEquals("512 Б", formatAttachmentSize(512))
        assertEquals("2 КБ", formatAttachmentSize(2048))
        assertEquals("5 МБ", formatAttachmentSize(5L * 1024 * 1024))
    }
}


class ConversationWorkspaceListTest {

    private fun item(kind: ConversationKind, id: Long, preview: String? = null) =
        org.example.project.dto.ConversationListItemDto(
            kind = kind,
            id = id,
            title = "${kind.name}-$id",
            lastMessagePreview = preview
        )

    @Test
    fun `workspace item maps task to virtual id without losing server task id`() {
        val mapped = item(ConversationKind.TASK, 9).toConversationDto()
        assertEquals(-9L, mapped.id)
        assertEquals(9L, mapped.taskId)
        assertEquals(ConversationKind.TASK, mapped.kind)
    }

    @Test
    fun `workspace pages append by stable kind and id without duplicates`() {
        val first = listOf(item(ConversationKind.DIRECT, 4), item(ConversationKind.TASK, 4))
        val next = listOf(item(ConversationKind.TASK, 4, "updated"), item(ConversationKind.PROJECT, 2))

        val merged = org.example.project.ui.conversations.mergeWorkspaceItems(first, next)

        assertEquals(3, merged.size)
        assertEquals("updated", merged.single { it.kind == ConversationKind.TASK }.lastMessagePreview)
    }

    @Test
    fun `revoked preview never falls back to old message text`() {
        val updated = org.example.project.ui.conversations.updateWorkspacePreview(
            listOf(item(ConversationKind.GROUP, 3, "secret")),
            ConversationKind.GROUP,
            3,
            org.example.project.ui.conversations.WorkspacePreview(
                text = null,
                revoked = true,
                author = null,
                createdAt = "2026-11-19T12:00:00Z"
            )
        )

        assertEquals("Сообщение удалено", updated.single().lastMessagePreview)
        assertFalse(updated.single().lastMessagePreview?.contains("secret") == true)
    }
}


private fun runImmediate(block: suspend () -> Unit) {
    var outcome: Result<Unit>? = null
    block.startCoroutine(object : kotlin.coroutines.Continuation<Unit> {
        override val context = kotlin.coroutines.EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) {
            outcome = result
        }
    })
    outcome?.getOrThrow() ?: error("Suspending test did not complete immediately")
}

@OptIn(io.ktor.utils.io.InternalAPI::class)
private class RecordingEngine(
    private val response: (io.ktor.client.request.HttpRequestData) -> Pair<io.ktor.http.HttpStatusCode, String>
) : io.ktor.client.engine.HttpClientEngine {
    var responseBody: (String) -> Any = { io.ktor.utils.io.ByteReadChannel(it) }
    val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
    override val dispatcher = kotlinx.coroutines.Dispatchers.Unconfined
    override val config = io.ktor.client.engine.HttpClientEngineConfig()
    override val coroutineContext = kotlinx.coroutines.SupervisorJob() + dispatcher

    override suspend fun execute(data: io.ktor.client.request.HttpRequestData): io.ktor.client.request.HttpResponseData {
        requests += data
        val (status, body) = response(data)
        return io.ktor.client.request.HttpResponseData(
            statusCode = status,
            requestTime = io.ktor.util.date.GMTDate(),
            headers = io.ktor.http.headersOf(io.ktor.http.HttpHeaders.ContentType, "application/json"),
            version = io.ktor.http.HttpProtocolVersion.HTTP_1_1,
            body = responseBody(body),
            callContext = kotlinx.coroutines.currentCoroutineContext()
        )
    }

    override fun close() {
        coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}

private fun apiClient(engine: RecordingEngine): org.example.project.data.ConversationApiClient {
    val client = io.ktor.client.HttpClient(engine) {
        install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
            json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
        }
    }
    return org.example.project.data.ConversationApiClient(client)
}

@OptIn(io.ktor.utils.io.InternalAPI::class)
class ConversationApiContractTest {

    @Test
    fun `workspace query cursor and state body use server contract`() = runImmediate {
        val engine = RecordingEngine { request ->
            if (request.method == io.ktor.http.HttpMethod.Get) {
                io.ktor.http.HttpStatusCode.OK to """{"success":true,"data":{"items":[],"nextCursor":null,"hasMore":false}}"""
            } else {
                io.ktor.http.HttpStatusCode.OK to """{"success":true,"data":{"kind":"GROUP","id":3,"isPinned":true,"isMuted":false}}"""
            }
        }
        val api = apiClient(engine)

        api.listWorkspace("supply", ConversationKind.GROUP, org.example.project.dto.ConversationListFilter.UNREAD, "cursor-1", 25)
        api.changeDialogState(
            ConversationKind.GROUP,
            3,
            org.example.project.dto.ConversationDialogStateRequest(pinned = true, muted = false)
        )

        val get = engine.requests[0]
        assertEquals("supply", get.url.parameters["query"])
        assertEquals("GROUP", get.url.parameters["kind"])
        assertEquals("UNREAD", get.url.parameters["filter"])
        assertEquals("cursor-1", get.url.parameters["cursor"])
        assertEquals("25", get.url.parameters["limit"])
        assertEquals("/api/chat/workspace", get.url.encodedPath)
        val post = engine.requests[1]
        assertEquals("/api/chat/workspace/GROUP/3/state", post.url.encodedPath)
        val body = (post.body as io.ktor.http.content.TextContent).text
        assertTrue(body.contains("\"pinned\":true"))
        assertTrue(body.contains("\"muted\":false"))
    }

    @Test
    fun `virtual task rich operations use task routes and preserve returned fields`() = runImmediate {
        val engine = RecordingEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/edit") -> io.ktor.http.HttpStatusCode.OK to """{"success":true,"data":{"id":5,"taskId":7,"author":null,"type":"USER","content":"edited","createdAt":"2026-11-19T10:00:00Z","editedAt":"2026-11-19T10:01:00Z","version":2}}"""
                path.endsWith("/revoke") -> io.ktor.http.HttpStatusCode.OK to """{"success":true,"data":{"id":5,"taskId":7,"author":null,"type":"USER","content":"","createdAt":"2026-11-19T10:00:00Z","revokedAt":"2026-11-19T10:02:00Z","version":3}}"""
                path.contains("/reactions/") -> io.ktor.http.HttpStatusCode.OK to """{"success":true,"data":[{"emoji":"+1","count":2,"reactedByMe":true}]}"""
                else -> io.ktor.http.HttpStatusCode.OK to """{"success":true,"data":[{"messageId":5,"taskId":7,"content":"needle","createdAt":"2026-11-19T10:00:00Z","author":{"userId":3,"fullName":"Author"}}]}"""
            }
        }
        val api = apiClient(engine)

        val edited = api.editMessage(-7, 5, org.example.project.dto.EditMessageRequest("edited", 1))
        val revoked = api.revokeMessage(-7, 5, 2)
        val reactions = api.toggleReaction(-7, 5, "+1")
        val search = api.searchMessages(-7, "needle")

        assertEquals("/api/tasks/7/chat/messages/5/edit", engine.requests[0].url.encodedPath)
        assertEquals("/api/tasks/7/chat/messages/5/revoke", engine.requests[1].url.encodedPath)
        assertEquals("/api/tasks/7/chat/messages/5/reactions/+1", engine.requests[2].url.encodedPath)
        assertEquals("/api/tasks/7/chat/search", engine.requests[3].url.encodedPath)
        assertEquals("needle", engine.requests[3].url.parameters["query"])
        val editedMessage = (edited as org.example.project.data.ApiResult.Success).data
        assertEquals("2026-11-19T10:01:00Z", editedMessage.editedAt)
        assertEquals(2L, editedMessage.version)
        assertEquals(-7L, editedMessage.conversationId)
        val revokedMessage = (revoked as org.example.project.data.ApiResult.Success).data
        assertEquals("2026-11-19T10:02:00Z", revokedMessage.revokedAt)
        assertEquals(3L, revokedMessage.version)
        assertEquals(2, (reactions as org.example.project.data.ApiResult.Success).data.single().count)
        val result = (search as org.example.project.data.ApiResult.Success).data.messages.single()
        assertEquals("needle", result.content)
        assertEquals("Author", result.author?.fullName)
        assertEquals(-7L, result.conversationId)
    }

    @Test
    fun `multipart sends every pending file under files part`() = runImmediate {
        val engine = RecordingEngine {
            io.ktor.http.HttpStatusCode.Created to """{"success":true,"data":{"id":9,"taskId":7,"author":null,"type":"USER","content":"docs","createdAt":"2026-11-19T10:00:00Z"}}"""
        }
        val api = apiClient(engine)
        val files = listOf(
            org.example.project.data.AttachmentFileData("a.txt", "text/plain", byteArrayOf(1)),
            org.example.project.data.AttachmentFileData("b.txt", "text/plain", byteArrayOf(2))
        )

        api.sendMessage(-7, org.example.project.dto.SendChatMessageRequest("docs", "key-1"), files)

        val content = engine.requests.single().body as io.ktor.client.request.forms.MultiPartFormDataContent
        assertEquals(listOf("content", "idempotencyKey", "files", "files"), content.parts.map { it.name })
        assertEquals("/api/tasks/7/chat/messages", engine.requests.single().url.encodedPath)
    }
}


class ConversationExplicitTaskKindTest {

    @Test
    fun `positive task id with explicit kind never uses conversations routes`() = runImmediate {
        val engine = RecordingEngine { request ->
            if (request.url.encodedPath.endsWith("/search")) {
                io.ktor.http.HttpStatusCode.OK to """{"success":true,"data":[]}"""
            } else {
                io.ktor.http.HttpStatusCode.OK to """{"success":true,"data":[]}"""
            }
        }
        val api = apiClient(engine)

        api.toggleReaction(7, 5, "+1", kind = ConversationKind.TASK)
        api.searchMessages(-7, "needle")

        assertEquals("/api/tasks/7/chat/messages/5/reactions/+1", engine.requests[0].url.encodedPath)
        assertEquals("/api/tasks/7/chat/search", engine.requests[1].url.encodedPath)
    }
}


class ConversationForwardReadContractTest {

    @Test
    fun `task and conversation forward read use their own endpoints`() = runImmediate {
        val engine = RecordingEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/forward") && path.startsWith("/api/tasks") ->
                    io.ktor.http.HttpStatusCode.Created to """{"success":true,"data":{"id":10,"taskId":7,"author":null,"type":"USER","content":"forwarded","createdAt":"2026-11-19T10:00:00Z","source":{"kind":"TASK","messageId":5,"createdAt":"2026-11-18T10:00:00Z"}}}"""
                path.endsWith("/forward") ->
                    io.ktor.http.HttpStatusCode.Created to """{"success":true,"data":{"id":11,"conversationId":3,"content":"forwarded","createdAt":"2026-11-19T10:00:00Z"}}"""
                path.startsWith("/api/tasks") ->
                    io.ktor.http.HttpStatusCode.OK to """{"success":true,"data":{"lastReadMessageId":9}}"""
                else ->
                    io.ktor.http.HttpStatusCode.OK to """{"success":true,"data":9}"""
            }
        }
        val api = apiClient(engine)
        val request = org.example.project.dto.ForwardMessageRequest(ConversationKind.TASK, 7, "forward-key")

        val taskForward = api.forwardMessage(-7, 5, request)
        val taskRead = api.markRead(-7, 9)
        val conversationForward = api.forwardMessage(3, 5, request)
        val conversationRead = api.markRead(3, 9)

        assertEquals("/api/tasks/7/chat/messages/5/forward", engine.requests[0].url.encodedPath)
        assertEquals("/api/tasks/7/chat/read", engine.requests[1].url.encodedPath)
        assertEquals("/api/conversations/3/messages/5/forward", engine.requests[2].url.encodedPath)
        assertEquals("/api/conversations/3/read", engine.requests[3].url.encodedPath)
        val forwarded = (taskForward as org.example.project.data.ApiResult.Success).data
        assertEquals(5L, forwarded.source?.messageId)
        assertEquals(ConversationKind.TASK, forwarded.source?.kind)
        assertEquals(9L, (taskRead as org.example.project.data.ApiResult.Success).data)
        assertEquals(11L, (conversationForward as org.example.project.data.ApiResult.Success).data.id)
        assertEquals(9L, (conversationRead as org.example.project.data.ApiResult.Success).data)
    }
}


class ApiCancellationContractTest {

    private fun cancelledClient(): io.ktor.client.HttpClient {
        val engine = RecordingEngine { throw kotlinx.coroutines.CancellationException("cancelled") }
        return io.ktor.client.HttpClient(engine) {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
            }
        }
    }

    @Test
    fun `conversation execute propagates cancellation`() = runImmediate {
        val api = org.example.project.data.ConversationApiClient(cancelledClient())
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            api.listWorkspace()
        }
    }

    @Test
    fun `chat execute and executeEmpty propagate cancellation`() = runImmediate {
        val api = org.example.project.data.ChatApiClient(cancelledClient())
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            api.listMessages(1)
        }
        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            api.markChatRead(1, 1)
        }
    }
}


class ApiErrorBodyCancellationTest {

    @Test
    fun `executeEmpty propagates cancellation thrown while reading error body`() = runImmediate {
        val engine = RecordingEngine { io.ktor.http.HttpStatusCode.InternalServerError to "error" }
        engine.responseBody = {
            io.ktor.utils.io.ByteChannel().also {
                it.cancel(kotlinx.coroutines.CancellationException("body cancelled"))
            }
        }
        val client = io.ktor.client.HttpClient(engine) {
            install(io.ktor.client.plugins.contentnegotiation.ContentNegotiation) {
                json(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
            }
        }
        val api = org.example.project.data.ChatApiClient(client)

        kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
            api.markChatRead(1, 1)
        }
    }
}
