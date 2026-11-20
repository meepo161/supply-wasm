package org.example.project

import kotlinx.serialization.json.Json
import org.example.project.data.AttachmentFileData
import org.example.project.data.TaskChatRealtimeEvent
import org.example.project.data.chatTypingFrame
import org.example.project.data.mergeTaskChatMessage
import org.example.project.data.parseTaskChatRealtimeEvent
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TaskChatRealtimeEventsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `message envelope parses for the expected task`() {
        val text = """{"category":"TASK_CHAT_MESSAGE","message":{"id":5,"taskId":7,"author":null,"type":"USER","content":"привет","createdAt":"2026-11-17T10:00:00Z"}}"""
        val event = parseTaskChatRealtimeEvent(json, text, expectedTaskId = 7)
        val message = assertIs<TaskChatRealtimeEvent.Message>(event).message
        assertEquals(5L, message.id)
        assertNull(parseTaskChatRealtimeEvent(json, text, expectedTaskId = 8))
    }

    @Test
    fun `reaction envelope carries message id and summaries`() {
        val text = """{"category":"TASK_CHAT_REACTION","messageId":5,"reactions":[{"emoji":"👍","count":2,"reactedByMe":true}]}"""
        val event = assertIs<TaskChatRealtimeEvent.Reaction>(
            parseTaskChatRealtimeEvent(json, text, expectedTaskId = 7)
        )
        assertEquals(5L, event.messageId)
        assertEquals("👍", event.reactions.single().emoji)
        assertTrue(event.reactions.single().reactedByMe)
    }

    @Test
    fun `read pin and typing envelopes parse`() {
        val read = assertIs<TaskChatRealtimeEvent.Read>(
            parseTaskChatRealtimeEvent(
                json,
                """{"category":"TASK_CHAT_READ","userId":3,"lastReadMessageId":42}""",
                expectedTaskId = 7
            )
        )
        assertEquals(42L, read.lastReadMessageId)

        val pin = assertIs<TaskChatRealtimeEvent.Pin>(
            parseTaskChatRealtimeEvent(
                json,
                """{"category":"TASK_CHAT_PIN","isPinned":true}""",
                expectedTaskId = 7
            )
        )
        assertTrue(pin.isPinned)

        val typing = assertIs<TaskChatRealtimeEvent.Typing>(
            parseTaskChatRealtimeEvent(
                json,
                """{"category":"TASK_CHAT_TYPING","userId":3,"fullName":"Иван Петров"}""",
                expectedTaskId = 7
            )
        )
        assertEquals("Иван Петров", typing.fullName)
    }

    @Test
    fun `unknown category and malformed payloads are ignored`() {
        assertNull(parseTaskChatRealtimeEvent(json, """{"category":"OTHER"}""", 7))
        assertNull(parseTaskChatRealtimeEvent(json, "not json", 7))
        assertNull(parseTaskChatRealtimeEvent(json, """{"category":"TASK_CHAT_READ","userId":3}""", 7))
    }

    @Test
    fun `typing command frame matches contract`() {
        assertEquals("""{"action":"TYPING"}""", chatTypingFrame())
    }
}


class TaskChatRealtimeCursorTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `edited and revoked envelopes retain durable event id`() {
        val edited = assertIs<TaskChatRealtimeEvent.Message>(
            parseTaskChatRealtimeEvent(
                json,
                """{"category":"TASK_CHAT_MESSAGE","type":"EDITED","eventId":44,"message":{"id":5,"taskId":7,"author":null,"type":"USER","content":"new","createdAt":"2026-11-19T10:00:00Z","editedAt":"2026-11-19T10:01:00Z"}}""",
                expectedTaskId = 7
            )
        )
        assertEquals(org.example.project.data.TaskChatMessageEventType.EDITED, edited.type)
        assertEquals(44L, edited.eventId)
        assertEquals("new", edited.message.content)
    }

    @Test
    fun `afterEvent reconnect cursor is separate from legacy message after`() {
        assertEquals(
            mapOf("after" to "9", "afterEvent" to "44"),
            org.example.project.data.realtimeCursorParameters(after = 9, afterEvent = 44)
        )
        assertEquals(
            mapOf("afterEvent" to "44"),
            org.example.project.data.realtimeCursorParameters(after = null, afterEvent = 44)
        )
    }
}


class TaskChatLifecycleMergeTest {

    private fun message(id: Long, content: String) = org.example.project.dto.ChatMessageDto(
        id = id,
        taskId = 7,
        author = null,
        type = org.example.project.dto.ChatMessageType.USER,
        content = content,
        createdAt = "2026-11-19T10:00:00Z"
    )

    @Test
    fun `edited and revoked replace existing messages while message dedupes`() {
        val initial = listOf(message(1, "old"))
        val appended = initial.mergeTaskChatMessage(
            TaskChatRealtimeEvent.Message(message(2, "new"), eventId = 1)
        )
        val duplicate = appended.mergeTaskChatMessage(
            TaskChatRealtimeEvent.Message(message(2, "new"), eventId = 2)
        )
        val edited = duplicate.mergeTaskChatMessage(
            TaskChatRealtimeEvent.Message(
                message(1, "edited"),
                org.example.project.data.TaskChatMessageEventType.EDITED,
                3
            )
        )
        val revoked = edited.mergeTaskChatMessage(
            TaskChatRealtimeEvent.Message(
                message(1, "must not survive"),
                org.example.project.data.TaskChatMessageEventType.REVOKED,
                4
            )
        )

        assertEquals(listOf(1L, 2L), revoked.map { it.id })
        assertEquals("", revoked.single { it.id == 1L }.content)
        assertEquals("edited", edited.single { it.id == 1L }.content)
    }

    @Test
    fun `durable event cursor does not jump ahead during reconnect`() {
        val cursor = org.example.project.data.TaskChatEventCursor()
        cursor.record(TaskChatRealtimeEvent.Message(message(8, "new"), eventId = 41))
        assertEquals(8L to 41L, cursor.parameters())
        assertEquals(8L to 41L, cursor.parameters())
        cursor.record(TaskChatRealtimeEvent.Read(2, 8, 42))
        assertEquals(8L to 42L, cursor.parameters())
    }
}


private data class ObservedTaskChatCall(val after: Long?, val afterEvent: Long?)

private class RevokingChatRepository(
    val networkRelease: kotlinx.coroutines.CompletableDeferred<Unit>
) : org.example.project.data.ChatRepository {
    val observed = mutableListOf<ObservedTaskChatCall>()
    var deferHistory = false
    val historyGates = mutableListOf<kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<org.example.project.dto.ChatMessagePageDto>>>()
    private var connections = 0
    var latestMessages: List<org.example.project.dto.ChatMessageDto>? = null
    var onAfter: (suspend (Long, Long?) -> org.example.project.data.ApiResult<org.example.project.dto.ChatMessagePageDto>)? = null
    val realtimeExtra = kotlinx.coroutines.flow.MutableSharedFlow<TaskChatRealtimeEvent>(extraBufferCapacity = 4)
    var emitRealtimeMessage = true
    val sendKeys = mutableListOf<String>()
    val sendGates = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<org.example.project.dto.ChatMessageDto>>>()
    val readCalls = mutableListOf<Pair<Long, Long>>()
    var onSearch: suspend (Long, String) -> org.example.project.data.ApiResult<List<org.example.project.dto.ChatSearchResultDto>> = { _, _ ->
        org.example.project.data.ApiResult.Success(emptyList())
    }
    var onBefore: suspend (Long, Long) -> org.example.project.data.ApiResult<org.example.project.dto.ChatMessagePageDto> = { _, _ ->
        org.example.project.data.ApiResult.Success(org.example.project.dto.ChatMessagePageDto(emptyList()))
    }
    val message = org.example.project.dto.ChatMessageDto(
        1, 1, null, org.example.project.dto.ChatMessageType.USER, "private", "2026-11-19T10:00:00Z"
    )

    override suspend fun listMessages(taskId: Long, after: Long?, before: Long?, limit: Int): org.example.project.data.ApiResult<org.example.project.dto.ChatMessagePageDto> {
        if (before != null) return onBefore(taskId, before)
        onAfter?.let { return it(taskId, after) }
        if (deferHistory) {
            val gate = kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<org.example.project.dto.ChatMessagePageDto>>()
            historyGates += gate
            return gate.await()
        }
        return org.example.project.data.ApiResult.Success(
            org.example.project.dto.ChatMessagePageDto(latestMessages ?: listOf(message.copy(taskId = taskId)))
        )
    }
    override suspend fun sendMessage(taskId: Long, request: org.example.project.dto.SendChatMessageRequest, file: AttachmentFileData?): org.example.project.data.ApiResult<org.example.project.dto.ChatMessageDto> {
        sendKeys += request.idempotencyKey
        val gate = sendGates.getOrPut(request.content) { kotlinx.coroutines.CompletableDeferred() }
        return gate.await()
    }
    override fun observeMessages(taskId: Long, after: Long?) = kotlinx.coroutines.flow.emptyFlow<org.example.project.dto.ChatMessageDto>()
    override fun observeEvents(
        taskId: Long,
        after: Long?,
        outgoing: kotlinx.coroutines.flow.Flow<String>,
        afterEvent: Long?
    ): kotlinx.coroutines.flow.Flow<TaskChatRealtimeEvent> {
        observed += ObservedTaskChatCall(after, afterEvent)
        connections++
        return if (connections == 1) {
            kotlinx.coroutines.flow.flow {
                if (emitRealtimeMessage) emit(TaskChatRealtimeEvent.Message(message, eventId = 41))
                realtimeExtra.collect { emit(it) }
                networkRelease.await()
                throw IllegalStateException("network")
            }
        } else if (connections == 2) {
            kotlinx.coroutines.flow.flow { throw org.example.project.data.ChatAccessRevokedException() }
        } else {
            kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() }
        }
    }
    override suspend fun listChatTasks(query: String?, filter: org.example.project.dto.TaskChatListFilter, limit: Int) =
        org.example.project.data.ApiResult.Success(
            listOf(
                org.example.project.dto.TaskChatListItemDto(
                    1, "T-1", "private preview", "IN_PROGRESS", "NORMAL",
                    lastMessageSnippet = "private"
                )
            )
        )
    override suspend fun markChatRead(taskId: Long, lastReadMessageId: Long): org.example.project.data.ApiResult<Boolean> {
        readCalls += taskId to lastReadMessageId
        return org.example.project.data.ApiResult.Success(true)
    }
    override suspend fun setChatReaction(taskId: Long, messageId: Long, emoji: String, active: Boolean) = error("unused")
    override suspend fun setChatPinned(taskId: Long, pinned: Boolean) = error("unused")
    override suspend fun searchChatMessages(taskId: Long, query: String, limit: Int) = onSearch(taskId, query)
}

private fun revokeTask() = org.example.project.dto.TaskDto(
    id = 1,
    number = "T-1",
    area = org.example.project.dto.TaskArea.PERSONAL,
    status = org.example.project.dto.TaskStatus.IN_PROGRESS,
    type = org.example.project.dto.TaskWorkType.GENERAL,
    title = "Task",
    initiatorId = 1,
    createdBy = 1
)

private fun runTaskViewModelImmediate(block: suspend () -> Unit) {
    var outcome: Result<Unit>? = null
    block.startCoroutine(object : kotlin.coroutines.Continuation<Unit> {
        override val context = kotlin.coroutines.EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) {
            outcome = result
        }
    })
    outcome?.getOrThrow() ?: error("Task ViewModel test did not complete immediately")
}

class TaskViewModelRevocationTest {

    @Test
    fun `revoke clears task chat state after durable reconnect`() = runTaskViewModelImmediate {
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val chat = RevokingChatRepository(release)
        val task = revokeTask()
        val taskRepository = object : org.example.project.data.TaskRepository {
            override suspend fun list() = org.example.project.data.ApiResult.Success(emptyList<org.example.project.dto.TaskDto>())
            override suspend fun get(id: Long) = org.example.project.data.ApiResult.Success(task)
            override suspend fun create(request: org.example.project.dto.CreateTaskRequest) = error("unused")
            override suspend fun assign(id: Long, request: org.example.project.dto.AssignTaskRequest) = error("unused")
            override suspend fun accept(id: Long, request: org.example.project.dto.TaskCommandRequest) = error("unused")
            override suspend fun submitResult(id: Long, request: org.example.project.dto.SubmitResultRequest) = error("unused")
            override suspend fun sendToReview(id: Long, request: org.example.project.dto.TaskCommandRequest) = error("unused")
            override suspend fun review(id: Long, request: org.example.project.dto.ReviewTaskRequest) = error("unused")
            override suspend fun close(id: Long, request: org.example.project.dto.TaskCommandRequest) = error("unused")
            override suspend fun block(id: Long, request: org.example.project.dto.BlockTaskRequest) = error("unused")
            override suspend fun unblock(id: Long, request: org.example.project.dto.TaskCommandRequest) = error("unused")
            override suspend fun reopen(id: Long, request: org.example.project.dto.ReopenTaskRequest) = error("unused")
            override suspend fun cancel(id: Long, request: org.example.project.dto.CancelTaskRequest) = error("unused")
        }
        val users = object : org.example.project.data.UserRepository {
            override suspend fun list() = org.example.project.data.ApiResult.Success(emptyList<org.example.project.dto.UserDto>())
            override suspend fun listOptions() = org.example.project.data.ApiResult.Success(emptyList<org.example.project.dto.UserOptionDto>())
        }
        val projects = object : org.example.project.data.ProjectRepository {
            override suspend fun list() = org.example.project.data.ApiResult.Success(emptyList<org.example.project.dto.ProjectDto>())
            override suspend fun get(id: Long) = error("unused")
            override suspend fun create(request: org.example.project.dto.CreateProjectRequest) = error("unused")
            override suspend fun open(id: Long, request: org.example.project.dto.OpenProjectRequest) = error("unused")
            override suspend fun close(id: Long, request: org.example.project.dto.CloseProjectRequest) = error("unused")
            override suspend fun addMember(id: Long, request: org.example.project.dto.AddProjectMemberRequest) = error("unused")
            override suspend fun removeMember(id: Long, userId: Long) = error("unused")
        }
        val attachment = org.example.project.dto.AttachmentDto(
            1, org.example.project.dto.AttachmentEntityType.TASK, 1, "private.txt", 4,
            null, null, 1, null, "2026-11-19T10:00:00Z"
        )
        val attachments = object : org.example.project.data.AttachmentRepository {
            override suspend fun list(entityType: org.example.project.dto.AttachmentEntityType, entityId: Long) =
                org.example.project.data.ApiResult.Success(listOf(attachment))
            override suspend fun upload(entityType: org.example.project.dto.AttachmentEntityType, entityId: Long, file: AttachmentFileData) = error("unused")
            override suspend fun download(id: Long) = error("unused")
            override suspend fun delete(id: Long) = error("unused")
        }
        val documents = object : org.example.project.data.DocumentRepository {
            override suspend fun list() = org.example.project.data.ApiResult.Success(emptyList<org.example.project.dto.DocumentDto>())
            override suspend fun get(id: Long) = error("unused")
            override suspend fun create(request: org.example.project.dto.CreateDocumentRequest) = error("unused")
            override suspend fun update(id: Long, request: org.example.project.dto.UpdateDocumentRequest) = error("unused")
            override suspend fun uploadVersion(documentId: Long, file: AttachmentFileData, changeReason: String?) = error("unused")
            override suspend fun approveVersion(documentId: Long, versionId: Long, request: org.example.project.dto.VersionTransitionRequest) = error("unused")
            override suspend fun activateVersion(documentId: Long, versionId: Long, request: org.example.project.dto.VersionTransitionRequest) = error("unused")
            override suspend fun cancelVersion(documentId: Long, versionId: Long, request: org.example.project.dto.VersionTransitionRequest) = error("unused")
            override suspend fun addLink(documentId: Long, request: org.example.project.dto.CreateDocumentLinkRequest) = error("unused")
            override suspend fun removeLink(documentId: Long, linkId: Long) = error("unused")
            override suspend fun downloadVersion(versionId: Long) = error("unused")
        }
        val bytes = AttachmentFileData("private.bin", "application/octet-stream", byteArrayOf(1, 2, 3))
        val viewModel = org.example.project.ui.tasks.TaskViewModel(
            taskRepository, chat, users, projects, attachments, documents, 1,
            coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            reconnectDelay = {},
            chatFilePicker = { onPicked, _, _, _ -> onPicked(bytes) }
        )

        viewModel.selectTask(task)
        viewModel.updateChatDraft("private draft")
        viewModel.setReplyTo(chat.message)
        viewModel.pickChatFile()
        viewModel.scrollToSearchResult(1)
        chat.deferHistory = true
        viewModel.refreshChat(manual = true)
        viewModel.loadMoreNewer()
        release.complete(Unit)

        assertEquals(2, chat.observed.size)
        assertEquals(41L, chat.observed[1].afterEvent)
        assertNull(viewModel.uiState.value.selectedTask)
        assertTrue(viewModel.uiState.value.chat.messages.isEmpty())
        assertTrue(viewModel.uiState.value.chatList.isEmpty())
        assertTrue(viewModel.uiState.value.chatDrafts.isEmpty())
        assertTrue(viewModel.uiState.value.chatPendingFiles.isEmpty())
        assertNull(viewModel.uiState.value.chat.pendingFile)
        assertNull(viewModel.uiState.value.chat.replyTo)
        assertNull(viewModel.uiState.value.chat.scrollToMessageId)
        assertTrue(viewModel.uiState.value.taskAttachments.isEmpty())
        assertEquals("Доступ к обсуждению завершён", viewModel.uiState.value.chat.error?.message)

        chat.deferHistory = false
        viewModel.selectTask(task.copy(id = 2, number = "T-2"))
        assertEquals(0L, chat.observed.last().afterEvent)
        assertEquals(2L, viewModel.uiState.value.selectedTask?.id)
        assertEquals(listOf(1L), viewModel.uiState.value.chat.messages.map { it.id })

        chat.historyGates.forEach { gate ->
            gate.complete(
                org.example.project.data.ApiResult.Success(
                    org.example.project.dto.ChatMessagePageDto(listOf(chat.message.copy(id = 99)))
                )
            )
        }
        assertEquals(2L, viewModel.uiState.value.selectedTask?.id)
        assertEquals(listOf(1L), viewModel.uiState.value.chat.messages.map { it.id })
        assertTrue(viewModel.uiState.value.chatList.isEmpty())
        viewModel.backToList()
    }
}


private class DeferredTaskRepository : org.example.project.data.TaskRepository {
    val gates = mutableMapOf<Long, kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<org.example.project.dto.TaskDto>>>()
    val calls = mutableListOf<Long>()
    var refreshError: org.example.project.dto.ApiError? = null

    override suspend fun list() = org.example.project.data.ApiResult.Success(emptyList<org.example.project.dto.TaskDto>())
    override suspend fun get(id: Long): org.example.project.data.ApiResult<org.example.project.dto.TaskDto> {
        calls += id
        gates[id]?.let { return it.await() }
        refreshError?.let { return org.example.project.data.ApiResult.Error(it) }
        return org.example.project.data.ApiResult.Success(revokeTask().copy(id = id, number = "T-$id"))
    }
    override suspend fun create(request: org.example.project.dto.CreateTaskRequest) = error("unused")
    override suspend fun assign(id: Long, request: org.example.project.dto.AssignTaskRequest) = error("unused")
    override suspend fun accept(id: Long, request: org.example.project.dto.TaskCommandRequest) = error("unused")
    override suspend fun submitResult(id: Long, request: org.example.project.dto.SubmitResultRequest) = error("unused")
    override suspend fun sendToReview(id: Long, request: org.example.project.dto.TaskCommandRequest) = error("unused")
    override suspend fun review(id: Long, request: org.example.project.dto.ReviewTaskRequest) = error("unused")
    override suspend fun close(id: Long, request: org.example.project.dto.TaskCommandRequest) = error("unused")
    override suspend fun block(id: Long, request: org.example.project.dto.BlockTaskRequest) = error("unused")
    override suspend fun unblock(id: Long, request: org.example.project.dto.TaskCommandRequest) = error("unused")
    override suspend fun reopen(id: Long, request: org.example.project.dto.ReopenTaskRequest) = error("unused")
    override suspend fun cancel(id: Long, request: org.example.project.dto.CancelTaskRequest) = error("unused")
}

private fun openGenerationTaskViewModel(
    tasks: DeferredTaskRepository,
    chat: RevokingChatRepository
): org.example.project.ui.tasks.TaskViewModel {
    val users = object : org.example.project.data.UserRepository {
        override suspend fun list() = org.example.project.data.ApiResult.Success(emptyList<org.example.project.dto.UserDto>())
        override suspend fun listOptions() = org.example.project.data.ApiResult.Success(emptyList<org.example.project.dto.UserOptionDto>())
    }
    val projects = object : org.example.project.data.ProjectRepository {
        override suspend fun list() = org.example.project.data.ApiResult.Success(emptyList<org.example.project.dto.ProjectDto>())
        override suspend fun get(id: Long) = error("unused")
        override suspend fun create(request: org.example.project.dto.CreateProjectRequest) = error("unused")
        override suspend fun open(id: Long, request: org.example.project.dto.OpenProjectRequest) = error("unused")
        override suspend fun close(id: Long, request: org.example.project.dto.CloseProjectRequest) = error("unused")
        override suspend fun addMember(id: Long, request: org.example.project.dto.AddProjectMemberRequest) = error("unused")
        override suspend fun removeMember(id: Long, userId: Long) = error("unused")
    }
    val attachments = object : org.example.project.data.AttachmentRepository {
        override suspend fun list(entityType: org.example.project.dto.AttachmentEntityType, entityId: Long) =
            org.example.project.data.ApiResult.Success(emptyList<org.example.project.dto.AttachmentDto>())
        override suspend fun upload(entityType: org.example.project.dto.AttachmentEntityType, entityId: Long, file: AttachmentFileData) = error("unused")
        override suspend fun download(id: Long) = error("unused")
        override suspend fun delete(id: Long) = error("unused")
    }
    val documents = object : org.example.project.data.DocumentRepository {
        override suspend fun list() = org.example.project.data.ApiResult.Success(emptyList<org.example.project.dto.DocumentDto>())
        override suspend fun get(id: Long) = error("unused")
        override suspend fun create(request: org.example.project.dto.CreateDocumentRequest) = error("unused")
        override suspend fun update(id: Long, request: org.example.project.dto.UpdateDocumentRequest) = error("unused")
        override suspend fun uploadVersion(documentId: Long, file: AttachmentFileData, changeReason: String?) = error("unused")
        override suspend fun approveVersion(documentId: Long, versionId: Long, request: org.example.project.dto.VersionTransitionRequest) = error("unused")
        override suspend fun activateVersion(documentId: Long, versionId: Long, request: org.example.project.dto.VersionTransitionRequest) = error("unused")
        override suspend fun cancelVersion(documentId: Long, versionId: Long, request: org.example.project.dto.VersionTransitionRequest) = error("unused")
        override suspend fun addLink(documentId: Long, request: org.example.project.dto.CreateDocumentLinkRequest) = error("unused")
        override suspend fun removeLink(documentId: Long, linkId: Long) = error("unused")
        override suspend fun downloadVersion(versionId: Long) = error("unused")
    }
    return org.example.project.ui.tasks.TaskViewModel(
        tasks, chat, users, projects, attachments, documents, 1,
        coroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        reconnectDelay = {}
    )
}

class TaskViewModelOpenGenerationTest {

    @Test
    fun `late open A after back cannot select task or start history`() = runTaskViewModelImmediate {
        val tasks = DeferredTaskRepository()
        val gate = kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<org.example.project.dto.TaskDto>>()
        tasks.gates[1] = gate
        val chat = RevokingChatRepository(kotlinx.coroutines.CompletableDeferred())
        val viewModel = openGenerationTaskViewModel(tasks, chat)

        viewModel.openTaskById(1)
        viewModel.backToList()
        gate.complete(org.example.project.data.ApiResult.Success(revokeTask()))

        assertNull(viewModel.uiState.value.selectedTask)
        assertNull(viewModel.uiState.value.actionError)
        assertTrue(chat.observed.isEmpty())
        assertEquals(listOf(1L), tasks.calls)
    }

    @Test
    fun `late open A cannot replace B or clear B refresh error`() = runTaskViewModelImmediate {
        val tasks = DeferredTaskRepository()
        val gate = kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<org.example.project.dto.TaskDto>>()
        tasks.gates[1] = gate
        val chat = RevokingChatRepository(kotlinx.coroutines.CompletableDeferred())
        val viewModel = openGenerationTaskViewModel(tasks, chat)

        viewModel.openTaskById(1)
        viewModel.openTaskById(2)
        tasks.refreshError = org.example.project.dto.ApiError("B_REFRESH", "B error")
        viewModel.refreshSelectedTask(2)
        gate.complete(org.example.project.data.ApiResult.Success(revokeTask()))

        assertEquals(2L, viewModel.uiState.value.selectedTask?.id)
        assertEquals("B error", viewModel.uiState.value.actionError?.message)
        assertEquals(1, chat.observed.count { true })
        viewModel.backToList()
    }
}

class TaskChatSearchHistoryTest {
    @Test
    fun `older search target is loaded before current page and highlighted`() = runTaskViewModelImmediate {
        val tasks = DeferredTaskRepository()
        val chat = RevokingChatRepository(kotlinx.coroutines.CompletableDeferred())
        chat.emitRealtimeMessage = false
        chat.latestMessages = listOf(chat.message.copy(id = 300))
        val beforeCalls = mutableListOf<Long>()
        chat.onBefore = { _, before ->
            beforeCalls += before
            val messages = if (before == 300L) {
                listOf(chat.message.copy(id = 200), chat.message.copy(id = 199))
            } else {
                listOf(chat.message.copy(id = 100), chat.message.copy(id = 99))
            }
            org.example.project.data.ApiResult.Success(
                org.example.project.dto.ChatMessagePageDto(messages, nextBefore = messages.last().id, hasMore = true)
            )
        }
        val viewModel = openGenerationTaskViewModel(tasks, chat)
        viewModel.selectTask(revokeTask())

        viewModel.scrollToSearchResult(100)

        assertEquals(listOf(300L, 199L), beforeCalls)
        assertEquals(listOf(99L, 100L, 199L, 200L, 300L), viewModel.uiState.value.chat.messages.map { it.id })
        assertEquals(100L, viewModel.uiState.value.chat.scrollToMessageId)
        assertEquals(100L, viewModel.uiState.value.chat.highlightedMessageId)
        assertNull(viewModel.uiState.value.chat.searchError)
        viewModel.backToList()
    }
}

class TaskChatImportantRegressionTest {
    @Test
    fun `success A1 does not remove pending A2 key for same task`() = runTaskViewModelImmediate {
        val chat = RevokingChatRepository(kotlinx.coroutines.CompletableDeferred())
        val viewModel = openGenerationTaskViewModel(DeferredTaskRepository(), chat)
        viewModel.selectTask(revokeTask())
        viewModel.updateChatDraft("A1")
        viewModel.sendMessage()
        viewModel.updateChatDraft("A2")
        viewModel.sendMessage()
        chat.sendGates.getValue("A1").complete(
            org.example.project.data.ApiResult.Success(chat.message.copy(id = 500, content = "A1"))
        )

        viewModel.sendMessage()

        assertEquals(chat.sendKeys[1], chat.sendKeys[2])
        chat.sendGates.getValue("A2").complete(
            org.example.project.data.ApiResult.Success(chat.message.copy(id = 501, content = "A2"))
        )
        viewModel.backToList()
    }

    @Test
    fun `read marks require visible latest and remain monotonic`() = runTaskViewModelImmediate {
        val chat = RevokingChatRepository(kotlinx.coroutines.CompletableDeferred())
        val viewModel = openGenerationTaskViewModel(DeferredTaskRepository(), chat)
        viewModel.selectTask(revokeTask())

        assertTrue(chat.readCalls.isEmpty())
        viewModel.updateChatVisibleRange(lastVisibleMessageId = null, viewingLatest = false)
        assertTrue(chat.readCalls.isEmpty())
        viewModel.updateChatVisibleRange(lastVisibleMessageId = 1, viewingLatest = true)
        viewModel.updateChatVisibleRange(lastVisibleMessageId = 1, viewingLatest = true)
        assertEquals(listOf(1L to 1L), chat.readCalls)
        viewModel.updateChatVisibleRange(lastVisibleMessageId = 1, viewingLatest = false)
        assertEquals(1, chat.readCalls.size)
        viewModel.backToList()
    }

    @Test
    fun `stale search error cannot change loading of newer query`() = runTaskViewModelImmediate {
        val chat = RevokingChatRepository(kotlinx.coroutines.CompletableDeferred())
        val pendingA = kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<List<org.example.project.dto.ChatSearchResultDto>>>()
        val pendingB = kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<List<org.example.project.dto.ChatSearchResultDto>>>()
        chat.onSearch = { _, query -> if (query == "A") pendingA.await() else pendingB.await() }
        val viewModel = openGenerationTaskViewModel(DeferredTaskRepository(), chat)
        viewModel.selectTask(revokeTask())
        viewModel.updateChatSearchQuery("A")
        viewModel.updateChatSearchQuery("B")

        pendingA.complete(org.example.project.data.ApiResult.Error(org.example.project.dto.ApiError("OLD", "stale")))
        assertTrue(viewModel.uiState.value.chat.searchLoading)
        assertNull(viewModel.uiState.value.chat.searchError)
        pendingB.complete(org.example.project.data.ApiResult.Success(emptyList()))
        assertFalse(viewModel.uiState.value.chat.searchLoading)
        viewModel.backToList()
    }

    @Test
    fun `refresh merge keeps realtime arrived during request without duplicate keys`() = runTaskViewModelImmediate {
        val chat = RevokingChatRepository(kotlinx.coroutines.CompletableDeferred())
        val refreshGate = kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<org.example.project.dto.ChatMessagePageDto>>()
        chat.onAfter = { _, after -> if (after == 1L) refreshGate.await() else org.example.project.data.ApiResult.Success(org.example.project.dto.ChatMessagePageDto(listOf(chat.message))) }
        val viewModel = openGenerationTaskViewModel(DeferredTaskRepository(), chat)
        viewModel.selectTask(revokeTask())
        viewModel.refreshChat(manual = true)
        val realtime = chat.message.copy(id = 2, content = "realtime-new", version = 2)
        chat.realtimeExtra.emit(TaskChatRealtimeEvent.Message(realtime, eventId = 42))

        refreshGate.complete(
            org.example.project.data.ApiResult.Success(
                org.example.project.dto.ChatMessagePageDto(listOf(chat.message.copy(id = 2, content = "stale", version = 1)))
            )
        )

        assertEquals(listOf(1L, 2L), viewModel.uiState.value.chat.messages.map { it.id })
        assertEquals("realtime-new", viewModel.uiState.value.chat.messages.single { it.id == 2L }.content)
        viewModel.backToList()
    }
}
