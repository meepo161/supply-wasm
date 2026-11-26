package org.example.project

import org.example.project.data.AttachmentFileData
import org.example.project.data.conversationKey
import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind
import org.example.project.dto.UserDto
import org.example.project.dto.UserOptionDto
import org.example.project.ui.conversations.ConversationLocalStateStore
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationDraftsTest {

    private val taskKey = conversationKey(ConversationKind.TASK, -7)
    private val directKey = conversationKey(ConversationKind.DIRECT, 7)
    private val file = AttachmentFileData("spec.txt", "text/plain", byteArrayOf(1, 2))
    private val otherFile = AttachmentFileData("photo.png", "image/png", byteArrayOf(3))

    @Test
    fun `draft files and scroll are restored independently for kind and id`() {
        val store = ConversationLocalStateStore()
        store.setDraft(taskKey, "task draft")
        store.setFiles(taskKey, listOf(file, otherFile))
        store.setScrollPosition(taskKey, 420)
        store.setDraft(directKey, "direct draft")
        store.setFiles(directKey, listOf(otherFile))
        store.setScrollPosition(directKey, 80)

        assertEquals("task draft", store.draft(taskKey))
        assertEquals(listOf(file, otherFile), store.files(taskKey))
        assertEquals(420, store.scrollPosition(taskKey))
        assertEquals("direct draft", store.draft(directKey))
        assertEquals(listOf(otherFile), store.files(directKey))
        assertEquals(80, store.scrollPosition(directKey))
        assertEquals("", store.draft(conversationKey(ConversationKind.PROJECT, 7)))
        assertTrue(store.files(conversationKey(ConversationKind.PROJECT, 7)).isEmpty())
    }

    @Test
    fun `retry keeps key only for the same failed conversation command`() {
        val store = ConversationLocalStateStore()
        val first = store.commandKey(taskKey) { "first-key" }

        assertEquals(first, store.commandKey(taskKey) { "replacement-key" })
        assertNotEquals(first, store.commandKey(directKey) { "direct-key" })

        store.completeCommand(taskKey)
        assertEquals("next-key", store.commandKey(taskKey) { "next-key" })
        assertEquals("direct-key", store.commandKey(directKey) { "unused" })
    }

    @Test
    fun `successful send clears composer only for its conversation`() {
        val store = ConversationLocalStateStore()
        store.setDraft(taskKey, "sent")
        store.setFiles(taskKey, listOf(file))
        store.setDraft(directKey, "keep me")
        store.setFiles(directKey, listOf(otherFile))
        store.setScrollPosition(taskKey, 100)

        store.clearComposerAfterSuccess(taskKey)

        assertEquals("", store.draft(taskKey))
        assertTrue(store.files(taskKey).isEmpty())
        assertEquals(100, store.scrollPosition(taskKey))
        assertEquals("keep me", store.draft(directKey))
        assertEquals(listOf(otherFile), store.files(directKey))
        assertNull(store.commandKeyOrNull(taskKey))
    }
}


private open class FakeConversationRepository : org.example.project.data.ConversationRepository {
    var onGet: suspend (Long) -> org.example.project.data.ApiResult<ConversationDto> = {
        org.example.project.data.ApiResult.Success(ConversationDto(it, ConversationKind.DIRECT))
    }
    var onProject: suspend (Long) -> org.example.project.data.ApiResult<ConversationDto> = {
        org.example.project.data.ApiResult.Success(ConversationDto(it, ConversationKind.PROJECT, projectId = it))
    }
    val sendKeys = mutableListOf<String>()
    val sendResults = mutableListOf<org.example.project.data.ApiResult<org.example.project.dto.ConversationMessageDto>>()
    var sendGate: kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<org.example.project.dto.ConversationMessageDto>>? = null
    var onListMessages: suspend (Long, Long?) -> org.example.project.data.ApiResult<org.example.project.dto.ConversationMessagePageDto> = { _, _ ->
        org.example.project.data.ApiResult.Success(org.example.project.dto.ConversationMessagePageDto(emptyList()))
    }
    var onListMessagesBefore: suspend (Long, Long) -> org.example.project.data.ApiResult<org.example.project.dto.ConversationMessagePageDto> = { _, _ ->
        org.example.project.data.ApiResult.Success(org.example.project.dto.ConversationMessagePageDto(emptyList()))
    }
    var listMessagesException: Throwable? = null
    var onSearch: suspend (Long, String) -> org.example.project.data.ApiResult<org.example.project.dto.ConversationMessagePageDto> = { _, _ ->
        org.example.project.data.ApiResult.Success(org.example.project.dto.ConversationMessagePageDto(emptyList()))
    }
    val readCalls = mutableListOf<Pair<Long, Long>>()
    var readResult: org.example.project.data.ApiResult<Long> = org.example.project.data.ApiResult.Success(0)
    val observeArguments = mutableListOf<Triple<Long, Long?, Long?>>()
    var observeFlows = mutableListOf<kotlinx.coroutines.flow.Flow<org.example.project.data.ConversationRealtimeEvent>>()
    var onWorkspace: suspend (String?, ConversationKind?, org.example.project.dto.ConversationListFilter, String?) ->
    org.example.project.data.ApiResult<org.example.project.dto.ConversationListPageDto> = { _, _, _, _ ->
        org.example.project.data.ApiResult.Success(org.example.project.dto.ConversationListPageDto(emptyList()))
    }

    override suspend fun list() = org.example.project.data.ApiResult.Success(emptyList<ConversationDto>())
    override suspend fun listWorkspace(
        query: String?,
        kind: ConversationKind?,
        filter: org.example.project.dto.ConversationListFilter,
        cursor: String?,
        limit: Int
    ) = onWorkspace(query, kind, filter, cursor)
    override suspend fun get(id: Long) = onGet(id)
    override suspend fun create(request: org.example.project.dto.CreateConversationRequest) =
        org.example.project.data.ApiResult.Success(ConversationDto(100, request.kind))
    override suspend fun getOrCreateProjectChat(projectId: Long) = onProject(projectId)
    override suspend fun listMessages(conversationId: Long, after: Long?, before: Long?, limit: Int): org.example.project.data.ApiResult<org.example.project.dto.ConversationMessagePageDto> {
        listMessagesException?.let { throw it }
        return if (before == null) onListMessages(conversationId, after) else onListMessagesBefore(conversationId, before)
    }
    override suspend fun searchMessages(conversationId: Long, query: String, after: Long?, limit: Int) =
        onSearch(conversationId, query)
    override suspend fun markRead(conversationId: Long, lastReadMessageId: Long): org.example.project.data.ApiResult<Long> {
        readCalls += conversationId to lastReadMessageId
        return readResult
    }
    override suspend fun sendMessage(
        conversationId: Long,
        request: org.example.project.dto.SendChatMessageRequest,
        file: AttachmentFileData?
    ) = org.example.project.data.ApiResult.Error(org.example.project.dto.ApiError("UNUSED", "unused"))
    override suspend fun sendMessage(
        conversationId: Long,
        request: org.example.project.dto.SendChatMessageRequest,
        files: List<AttachmentFileData>
    ): org.example.project.data.ApiResult<org.example.project.dto.ConversationMessageDto> {
        sendKeys += request.idempotencyKey
        return sendGate?.await() ?: sendResults.removeAt(0)
    }
    override fun observeMessages(conversationId: Long, after: Long?) = kotlinx.coroutines.flow.emptyFlow<org.example.project.dto.ConversationMessageDto>()
    override fun observeEvents(
        conversationId: Long,
        after: Long?,
        afterEvent: Long?
    ): kotlinx.coroutines.flow.Flow<org.example.project.data.ConversationRealtimeEvent> {
        observeArguments += Triple(conversationId, after, afterEvent)
        return if (observeFlows.isEmpty()) {
            kotlinx.coroutines.flow.flow { kotlinx.coroutines.awaitCancellation() }
        } else {
            observeFlows.removeAt(0)
        }
    }
}

private fun testViewModel(
    repository: FakeConversationRepository,
    coroutineScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
): org.example.project.ui.conversations.ConversationViewModel {
    val tasks = object : org.example.project.data.TaskRepository {
        override suspend fun list() = org.example.project.data.ApiResult.Success(emptyList<org.example.project.dto.TaskDto>())
        override suspend fun get(id: Long) = error("unused")
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
    val projects = object : org.example.project.data.ProjectRepository {
        override suspend fun getSupplyOverview(projectId: Long) = error("unused")
        override suspend fun handoffSpecification(projectId: Long, request: org.example.project.dto.HandoffProjectSpecRequest) = error("unused")
        override suspend fun list() = org.example.project.data.ApiResult.Success(emptyList<org.example.project.dto.ProjectDto>())
        override suspend fun get(id: Long) = error("unused")
        override suspend fun create(request: org.example.project.dto.CreateProjectRequest) = error("unused")
        override suspend fun open(id: Long, request: org.example.project.dto.OpenProjectRequest) = error("unused")
        override suspend fun close(id: Long, request: org.example.project.dto.CloseProjectRequest) = error("unused")
        override suspend fun addMember(id: Long, request: org.example.project.dto.AddProjectMemberRequest) = error("unused")
        override suspend fun removeMember(id: Long, userId: Long) = error("unused")
        override suspend fun summary(id: Long) = error("unused")
        override suspend fun history(id: Long, limit: Int?) = error("unused")
        override suspend fun tasks(id: Long, status: org.example.project.dto.TaskStatus?) = error("unused")
        override suspend fun demands(id: Long) = error("unused")
        override suspend fun documents(id: Long) = error("unused")
        override suspend fun stages(projectId: Long, status: org.example.project.dto.ProjectStageStatus?) = error("unused")
        override suspend fun createStage(projectId: Long, request: org.example.project.dto.CreateProjectStageRequest) = error("unused")
        override suspend fun updateStage(stageId: Long, request: org.example.project.dto.UpdateProjectStageRequest) = error("unused")
        override suspend fun stageCommand(stageId: Long, command: org.example.project.data.ProjectStageCommand, request: org.example.project.dto.ProjectStageCommandRequest) = error("unused")
        override suspend fun deleteStage(stageId: Long, version: Long) = error("unused")
        override suspend fun milestones(projectId: Long, stageId: Long?, status: org.example.project.dto.ProjectMilestoneStatus?) = error("unused")
        override suspend fun createMilestone(projectId: Long, request: org.example.project.dto.CreateProjectMilestoneRequest) = error("unused")
        override suspend fun updateMilestone(milestoneId: Long, request: org.example.project.dto.UpdateProjectMilestoneRequest) = error("unused")
        override suspend fun milestoneCommand(milestoneId: Long, command: org.example.project.data.ProjectMilestoneCommand, request: org.example.project.dto.ProjectMilestoneCommandRequest) = error("unused")
        override suspend fun deleteMilestone(milestoneId: Long, version: Long) = error("unused")
        override suspend fun risks(projectId: Long, status: org.example.project.dto.ProjectRiskStatus?) = error("unused")
        override suspend fun createRisk(projectId: Long, request: org.example.project.dto.CreateProjectRiskRequest) = error("unused")
        override suspend fun updateRisk(riskId: Long, request: org.example.project.dto.UpdateProjectRiskRequest) = error("unused")
        override suspend fun mitigateRisk(riskId: Long, version: Long) = error("unused")
        override suspend fun reopenRisk(riskId: Long, version: Long) = error("unused")
        override suspend fun closeRisk(riskId: Long, request: org.example.project.dto.CloseProjectRiskRequest) = error("unused")
        override suspend fun deleteRisk(riskId: Long, version: Long) = error("unused")
        override suspend fun planSummary(projectId: Long) = error("unused")
    }
    val attachments = object : org.example.project.data.AttachmentRepository {
        override suspend fun list(entityType: org.example.project.dto.AttachmentEntityType, entityId: Long) = error("unused")
        override suspend fun upload(entityType: org.example.project.dto.AttachmentEntityType, entityId: Long, file: AttachmentFileData) = error("unused")
        override suspend fun download(id: Long) = error("unused")
        override suspend fun delete(id: Long) = error("unused")
    }
    val users = object : org.example.project.data.UserRepository {
        override suspend fun list() = org.example.project.data.ApiResult.Success(emptyList<UserDto>())
        override suspend fun listOptions() = org.example.project.data.ApiResult.Success(emptyList<UserOptionDto>())
    }
    return org.example.project.ui.conversations.ConversationViewModel(
        repository,
        tasks,
        projects,
        attachments,
        users,
        UserDto(1, "user", "User"),
        coroutineScope = coroutineScope,
        reconnectDelay = {}
    )
}

private fun sentMessage(conversationId: Long) = org.example.project.dto.ConversationMessageDto(
    id = 99,
    conversationId = conversationId,
    content = "sent",
    createdAt = "2026-11-19T10:00:00Z"
)

private fun runViewModelImmediate(block: suspend () -> Unit) {
    var outcome: Result<Unit>? = null
    block.startCoroutine(object : kotlin.coroutines.Continuation<Unit> {
        override val context = kotlin.coroutines.EmptyCoroutineContext
        override fun resumeWith(result: Result<Unit>) {
            outcome = result
        }
    })
    outcome?.getOrThrow() ?: error("ViewModel test did not complete immediately")
}

class ConversationViewModelNavigationTest {

    @Test
    fun `late conversation repository result cannot replace newer selection`() = runViewModelImmediate {
        val pending = kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<ConversationDto>>()
        val repository = FakeConversationRepository()
        repository.onGet = { pending.await() }
        val viewModel = testViewModel(repository)

        viewModel.openConversationById(1)
        viewModel.selectConversation(ConversationDto(2, ConversationKind.GROUP))
        pending.complete(org.example.project.data.ApiResult.Success(ConversationDto(1, ConversationKind.DIRECT)))

        assertEquals(2L, viewModel.uiState.value.selectedConversation?.id)
        assertEquals(ConversationKind.GROUP, viewModel.uiState.value.selectedConversation?.kind)
        viewModel.closeConversation()
    }

    @Test
    fun `late project repository result cannot reopen conversation after back`() = runViewModelImmediate {
        val pending = kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<ConversationDto>>()
        val repository = FakeConversationRepository()
        repository.onProject = { pending.await() }
        val viewModel = testViewModel(repository)

        viewModel.openProjectChat(9)
        viewModel.closeConversation()
        pending.complete(org.example.project.data.ApiResult.Success(ConversationDto(9, ConversationKind.PROJECT, projectId = 9)))

        assertNull(viewModel.uiState.value.selectedConversation)
    }
}

class ConversationViewModelContractTest {

    @Test
    fun `failed send retries same key and success clears only its draft`() = runViewModelImmediate {
        val repository = FakeConversationRepository()
        repository.sendResults += org.example.project.data.ApiResult.Error(org.example.project.dto.ApiError("NETWORK", "offline"))
        repository.sendResults += org.example.project.data.ApiResult.Success(sentMessage(1))
        val viewModel = testViewModel(repository)
        val first = ConversationDto(1, ConversationKind.DIRECT)
        val second = ConversationDto(2, ConversationKind.DIRECT)

        viewModel.selectConversation(second)
        viewModel.updateDraft("second draft")
        viewModel.selectConversation(first)
        viewModel.updateDraft("first draft")
        viewModel.sendMessage()
        assertEquals("first draft", viewModel.uiState.value.messages.draft)
        assertTrue(viewModel.uiState.value.messages.sendError != null)

        viewModel.sendMessage()
        assertEquals(2, repository.sendKeys.size)
        assertEquals(repository.sendKeys[0], repository.sendKeys[1])
        assertEquals("", viewModel.uiState.value.messages.draft)
        assertNull(viewModel.uiState.value.messages.sendError)

        viewModel.selectConversation(second)
        assertEquals("second draft", viewModel.uiState.value.messages.draft)
        viewModel.closeConversation()
    }
}


class ConversationViewModelReconnectTest {

    @Test
    fun `overflow reconnect calls repository with latest message and event cursors`() = runViewModelImmediate {
        val repository = FakeConversationRepository()
        repository.observeFlows += kotlinx.coroutines.flow.flow {
            emit(
                org.example.project.data.ConversationRealtimeEvent.Message(
                    sentMessage(1).copy(id = 8),
                    eventId = 41
                )
            )
            throw org.example.project.data.ConversationOverflowException()
        }
        repository.observeFlows += kotlinx.coroutines.flow.flow {
            throw org.example.project.data.ConversationAccessRevokedException()
        }
        val viewModel = testViewModel(repository)

        viewModel.selectConversation(ConversationDto(1, ConversationKind.DIRECT))

        assertEquals(2, repository.observeArguments.size)
        assertEquals(Triple(1L, null, null), repository.observeArguments[0])
        assertEquals(Triple(1L, 8L, 41L), repository.observeArguments[1])
        viewModel.closeConversation()
    }
}


class ConversationCommandSignatureTest {

    private val key = conversationKey(ConversationKind.DIRECT, 1)
    private val file = AttachmentFileData("a.txt", "text/plain", byteArrayOf(1, 2))

    @Test
    fun `key changes for content reply and ordered file content changes`() {
        val store = ConversationLocalStateStore()
        val original = org.example.project.ui.conversations.conversationCommandSignature("same", null, listOf(file))
        val first = store.commandKey(key, original) { "first" }

        assertEquals(first, store.commandKey(key, original) { "replacement" })
        assertNotEquals(
            first,
            store.commandKey(
                key,
                org.example.project.ui.conversations.conversationCommandSignature("changed", null, listOf(file))
            ) { "changed-content" }
        )
        assertNotEquals(
            first,
            store.commandKey(
                key,
                org.example.project.ui.conversations.conversationCommandSignature("same", 9, listOf(file))
            ) { "changed-reply" }
        )
        val changedFile = AttachmentFileData("a.txt", "text/plain", byteArrayOf(1, 3))
        assertNotEquals(
            first,
            store.commandKey(
                key,
                org.example.project.ui.conversations.conversationCommandSignature("same", null, listOf(changedFile))
            ) { "changed-file" }
        )
    }

    @Test
    fun `success does not clear composer changed after command started`() {
        val store = ConversationLocalStateStore()
        val original = org.example.project.ui.conversations.conversationCommandSignature("before", null, listOf(file))
        store.setDraft(key, "before")
        store.setFiles(key, listOf(file))
        store.commandKey(key, original) { "first" }
        store.setDraft(key, "after")
        store.setFiles(key, emptyList())

        store.clearComposerAfterSuccess(key, original)

        assertEquals("after", store.draft(key))
        assertTrue(store.files(key).isEmpty())
    }
}

class ConversationAccessRevocationTest {

    @Test
    fun `revoke clears selection messages and sensitive local state`() = runViewModelImmediate {
        val repository = FakeConversationRepository()
        repository.observeFlows += kotlinx.coroutines.flow.flow<org.example.project.data.ConversationRealtimeEvent> {
            kotlinx.coroutines.awaitCancellation()
        }
        val viewModel = testViewModel(repository)
        val conversation = ConversationDto(1, ConversationKind.DIRECT)
        repository.sendResults += org.example.project.data.ApiResult.Error(org.example.project.dto.ApiError("NETWORK", "offline"))

        viewModel.selectConversation(conversation)
        viewModel.updateDraft("private draft")
        viewModel.updateScrollPosition(300)
        viewModel.sendMessage()
        viewModel.closeConversation()
        repository.observeFlows += kotlinx.coroutines.flow.flow {
            emit(org.example.project.data.ConversationRealtimeEvent.Message(sentMessage(1), eventId = 1))
            throw org.example.project.data.ConversationAccessRevokedException()
        }
        viewModel.selectConversation(conversation)

        assertNull(viewModel.uiState.value.selectedConversation)
        assertTrue(viewModel.uiState.value.messages.messages.isEmpty())
        assertEquals("", viewModel.uiState.value.messages.draft)
        assertEquals(0, viewModel.uiState.value.messages.scrollPosition)
        assertEquals("Доступ к обсуждению завершён", viewModel.uiState.value.messages.error?.message)
    }
}

class ConversationHistoryGenerationTest {

    @Test
    fun `old A history cannot overwrite reselected A or start second realtime`() = runViewModelImmediate {
        val oldA = kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<org.example.project.dto.ConversationMessagePageDto>>()
        val repository = FakeConversationRepository()
        var firstA = true
        repository.onListMessages = { id, _ ->
            if (id == 1L && firstA) {
                firstA = false
                oldA.await()
            } else if (id == 1L) {
                org.example.project.data.ApiResult.Success(
                    org.example.project.dto.ConversationMessagePageDto(listOf(sentMessage(1).copy(id = 2)))
                )
            } else {
                org.example.project.data.ApiResult.Success(org.example.project.dto.ConversationMessagePageDto(emptyList()))
            }
        }
        val viewModel = testViewModel(repository)
        val a = ConversationDto(1, ConversationKind.DIRECT)

        viewModel.selectConversation(a)
        viewModel.closeConversation()
        viewModel.selectConversation(ConversationDto(2, ConversationKind.GROUP))
        viewModel.selectConversation(a)
        oldA.complete(
            org.example.project.data.ApiResult.Success(
                org.example.project.dto.ConversationMessagePageDto(listOf(sentMessage(1).copy(id = 1)))
            )
        )

        assertEquals(listOf(2L), viewModel.uiState.value.messages.messages.map { it.id })
        assertEquals(1, repository.observeArguments.count { it.first == 1L })
        viewModel.closeConversation()
    }
}

class ConversationSendMutationTest {

    @Test
    fun `success preserves draft edited while request is pending`() = runViewModelImmediate {
        val repository = FakeConversationRepository()
        repository.sendGate = kotlinx.coroutines.CompletableDeferred()
        val viewModel = testViewModel(repository)
        val conversation = ConversationDto(1, ConversationKind.DIRECT)
        viewModel.selectConversation(conversation)
        viewModel.updateDraft("before")
        viewModel.sendMessage()
        viewModel.updateDraft("after")

        repository.sendGate?.complete(org.example.project.data.ApiResult.Success(sentMessage(1)))

        assertEquals("after", viewModel.uiState.value.messages.draft)
        viewModel.closeConversation()
    }
}


class ConversationRepeatedSelectionTest {

    @Test
    fun `selecting active conversation does not restart realtime`() = runViewModelImmediate {
        val repository = FakeConversationRepository()
        repository.observeFlows += kotlinx.coroutines.flow.flow<org.example.project.data.ConversationRealtimeEvent> {
            kotlinx.coroutines.awaitCancellation()
        }
        val viewModel = testViewModel(repository)
        val conversation = ConversationDto(1, ConversationKind.DIRECT)

        viewModel.selectConversation(conversation)
        viewModel.selectConversation(conversation)

        assertEquals(1, repository.observeArguments.size)
        assertEquals(conversation, viewModel.uiState.value.selectedConversation)
        viewModel.closeConversation()
    }
}

class ConversationNormalizedSignatureTest {

    @Test
    fun `whitespace only draft change keeps the same command key`() {
        val store = ConversationLocalStateStore()
        val key = conversationKey(ConversationKind.DIRECT, 1)
        store.setDraft(key, "same")
        val original = store.signature(key)
        val first = store.commandKey(key, original) { "first" }
        store.setDraft(key, "  same  ")

        assertEquals(first, store.commandKey(key, store.signature(key)) { "replacement" })
    }
}


private fun workspacePage(
    id: Long,
    cursor: String? = null,
    more: Boolean = false
) = org.example.project.dto.ConversationListPageDto(
    items = listOf(
        org.example.project.dto.ConversationListItemDto(
            kind = ConversationKind.DIRECT,
            id = id,
            title = "chat-$id"
        )
    ),
    nextCursor = cursor,
    hasMore = more
)

class ConversationWorkspaceRequestGenerationTest {

    @Test
    fun `query change invalidates cursor and blocks load more during debounce`() = runViewModelImmediate {
        val repository = FakeConversationRepository()
        var calls = 0
        repository.onWorkspace = { _, _, _, _ ->
            calls++
            org.example.project.data.ApiResult.Success(workspacePage(1, cursor = "old", more = true))
        }
        val viewModel = testViewModel(repository)

        viewModel.updateQuery("new parameters")
        viewModel.loadMore()

        assertNull(viewModel.uiState.value.nextCursor)
        assertTrue(!viewModel.uiState.value.hasMore)
        assertTrue(viewModel.uiState.value.isLoading)
        assertEquals(1, calls)
        viewModel.close()
    }

    @Test
    fun `response for old query is ignored while new query waits for debounce`() = runViewModelImmediate {
        val initial = kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<org.example.project.dto.ConversationListPageDto>>()
        val repository = FakeConversationRepository()
        repository.onWorkspace = { _, _, _, _ -> initial.await() }
        val viewModel = testViewModel(repository)

        viewModel.updateQuery("replacement")
        initial.complete(org.example.project.data.ApiResult.Success(workspacePage(1, cursor = "stale", more = true)))

        assertTrue(viewModel.uiState.value.items.isEmpty())
        assertNull(viewModel.uiState.value.nextCursor)
        assertTrue(!viewModel.uiState.value.hasMore)
        viewModel.close()
    }

    @Test
    fun `refresh wins over in flight append and late append cannot replace fresh page`() = runViewModelImmediate {
        val append = kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<org.example.project.dto.ConversationListPageDto>>()
        val refresh = kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<org.example.project.dto.ConversationListPageDto>>()
        val repository = FakeConversationRepository()
        var viewModelMarkerRefreshStarted = false
        repository.onWorkspace = { _, _, _, cursor ->
            when (cursor) {
                "page-1" -> append.await()
                null -> if (viewModelMarkerRefreshStarted) refresh.await() else org.example.project.data.ApiResult.Success(
                    workspacePage(1, cursor = "page-1", more = true)
                )
                else -> error("unexpected cursor")
            }
        }
        val viewModel = testViewModel(repository)
        viewModel.loadMore()
        viewModelMarkerRefreshStarted = true
        viewModel.loadAll()

        append.complete(org.example.project.data.ApiResult.Success(workspacePage(2, cursor = "stale", more = true)))
        assertTrue(viewModel.uiState.value.items.none { it.id == 2L })
        assertTrue(!viewModel.uiState.value.isLoadingMore)
        refresh.complete(org.example.project.data.ApiResult.Success(workspacePage(3)))

        assertEquals(listOf(3L), viewModel.uiState.value.items.map { it.id })
        assertNull(viewModel.uiState.value.nextCursor)
        viewModel.close()
    }
}

class ConversationViewModelCloseTest {

    @Test
    fun `close cancels scope clears sensitive state and ignores late response`() = runViewModelImmediate {
        val pending = kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<org.example.project.dto.ConversationListPageDto>>()
        val repository = FakeConversationRepository()
        repository.onWorkspace = { _, _, _, _ -> pending.await() }
        val job = kotlinx.coroutines.Job()
        val scope = kotlinx.coroutines.CoroutineScope(job + kotlinx.coroutines.Dispatchers.Unconfined)
        val viewModel = testViewModel(repository, scope)
        repository.observeFlows += kotlinx.coroutines.flow.flow<org.example.project.data.ConversationRealtimeEvent> {
            kotlinx.coroutines.awaitCancellation()
        }
        viewModel.selectConversation(ConversationDto(1, ConversationKind.DIRECT))
        viewModel.updateDraft("sensitive draft")

        viewModel.close()
        viewModel.close()
        pending.complete(org.example.project.data.ApiResult.Success(workspacePage(8, cursor = "late", more = true)))

        assertTrue(!job.isActive)
        assertTrue(viewModel.uiState.value.items.isEmpty())
        assertTrue(viewModel.uiState.value.conversations.isEmpty())
        assertTrue(viewModel.uiState.value.messages.messages.isEmpty())
        assertEquals("", viewModel.uiState.value.messages.draft)
        assertNull(viewModel.uiState.value.nextCursor)
    }
}

class ConversationSearchHistoryTest {
    @Test
    fun `older search target is loaded across bounded before pages without duplicates`() = runViewModelImmediate {
        val repository = FakeConversationRepository()
        val beforeCalls = mutableListOf<Long>()
        repository.onListMessages = { _, _ ->
            org.example.project.data.ApiResult.Success(
                org.example.project.dto.ConversationMessagePageDto(
                    listOf(sentMessage(1).copy(id = 300), sentMessage(1).copy(id = 301))
                )
            )
        }
        repository.onListMessagesBefore = { _, before ->
            beforeCalls += before
            val messages = if (before == 300L) {
                listOf(sentMessage(1).copy(id = 200), sentMessage(1).copy(id = 199))
            } else {
                listOf(sentMessage(1).copy(id = 100), sentMessage(1).copy(id = 99))
            }
            org.example.project.data.ApiResult.Success(
                org.example.project.dto.ConversationMessagePageDto(messages, nextBefore = messages.last().id, hasMore = true)
            )
        }
        repository.observeFlows += kotlinx.coroutines.flow.flow<org.example.project.data.ConversationRealtimeEvent> {
            kotlinx.coroutines.awaitCancellation()
        }
        val viewModel = testViewModel(repository)
        viewModel.selectConversation(ConversationDto(1, ConversationKind.DIRECT))

        viewModel.scrollToSearchResult(100)

        assertEquals(listOf(300L, 199L), beforeCalls)
        assertEquals(listOf(99L, 100L, 199L, 200L, 300L, 301L), viewModel.uiState.value.messages.messages.map { it.id })
        assertEquals(100L, viewModel.uiState.value.messages.scrollToMessageId)
        assertEquals(100L, viewModel.uiState.value.messages.highlightedMessageId)
        assertNull(viewModel.uiState.value.messages.searchError)
        viewModel.closeConversation()
    }
}

class ConversationMarkReadTest {
    @Test
    fun `initial latest and visible incoming mark read monotonically and clear list badges`() = runViewModelImmediate {
        val repository = FakeConversationRepository()
        repository.onWorkspace = { _, _, _, _ ->
            org.example.project.data.ApiResult.Success(
                org.example.project.dto.ConversationListPageDto(
                    listOf(
                        org.example.project.dto.ConversationListItemDto(
                            ConversationKind.DIRECT, 1, unreadCount = 3, mentionCount = 1
                        )
                    )
                )
            )
        }
        repository.onListMessages = { _, _ ->
            org.example.project.data.ApiResult.Success(
                org.example.project.dto.ConversationMessagePageDto(listOf(sentMessage(1).copy(id = 5)))
            )
        }
        repository.observeFlows += kotlinx.coroutines.flow.flow {
            emit(org.example.project.data.ConversationRealtimeEvent.Message(sentMessage(1).copy(id = 6)))
            kotlinx.coroutines.awaitCancellation()
        }
        val viewModel = testViewModel(repository)
        viewModel.selectConversation(ConversationDto(1, ConversationKind.DIRECT))

        assertEquals(listOf(1L to 5L, 1L to 6L), repository.readCalls)
        assertEquals(0, viewModel.uiState.value.items.single().unreadCount)
        assertEquals(0, viewModel.uiState.value.items.single().mentionCount)
        viewModel.updateVisibleRange(4, viewingLatest = false)
        assertEquals(2, repository.readCalls.size)
        viewModel.closeConversation()
    }

    @Test
    fun `mark read error preserves loaded messages`() = runViewModelImmediate {
        val repository = FakeConversationRepository()
        repository.readResult = org.example.project.data.ApiResult.Error(org.example.project.dto.ApiError("NETWORK", "offline"))
        repository.onListMessages = { _, _ ->
            org.example.project.data.ApiResult.Success(
                org.example.project.dto.ConversationMessagePageDto(listOf(sentMessage(1).copy(id = 5)))
            )
        }
        repository.observeFlows += kotlinx.coroutines.flow.flow<org.example.project.data.ConversationRealtimeEvent> {
            kotlinx.coroutines.awaitCancellation()
        }
        val viewModel = testViewModel(repository)
        viewModel.selectConversation(ConversationDto(1, ConversationKind.DIRECT))

        assertEquals(listOf(5L), viewModel.uiState.value.messages.messages.map { it.id })
        assertEquals("offline", viewModel.uiState.value.messages.error?.message)
        viewModel.closeConversation()
    }
}

class ConversationRefreshFailureTest {
    @Test
    fun `manual refresh access revoke performs cleanup without crash`() = runViewModelImmediate {
        val repository = FakeConversationRepository()
        repository.onListMessages = { _, _ ->
            org.example.project.data.ApiResult.Success(
                org.example.project.dto.ConversationMessagePageDto(listOf(sentMessage(1)))
            )
        }
        repository.observeFlows += kotlinx.coroutines.flow.flow<org.example.project.data.ConversationRealtimeEvent> {
            kotlinx.coroutines.awaitCancellation()
        }
        val viewModel = testViewModel(repository)
        viewModel.selectConversation(ConversationDto(1, ConversationKind.DIRECT))
        repository.listMessagesException = org.example.project.data.ConversationAccessRevokedException()

        viewModel.refreshMessages(manual = true)

        assertNull(viewModel.uiState.value.selectedConversation)
        assertTrue(viewModel.uiState.value.messages.messages.isEmpty())
        assertEquals("Доступ к обсуждению завершён", viewModel.uiState.value.messages.error?.message)
    }
}

class ConversationSearchGenerationTest {
    @Test
    fun `stale query error and other chat success cannot replace current search`() = runViewModelImmediate {
        val repository = FakeConversationRepository()
        val pendingA = kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<org.example.project.dto.ConversationMessagePageDto>>()
        val pendingB = kotlinx.coroutines.CompletableDeferred<org.example.project.data.ApiResult<org.example.project.dto.ConversationMessagePageDto>>()
        repository.onSearch = { _, query -> if (query == "A") pendingA.await() else pendingB.await() }
        repository.observeFlows += kotlinx.coroutines.flow.flow<org.example.project.data.ConversationRealtimeEvent> {
            kotlinx.coroutines.awaitCancellation()
        }
        repository.observeFlows += kotlinx.coroutines.flow.flow<org.example.project.data.ConversationRealtimeEvent> {
            kotlinx.coroutines.awaitCancellation()
        }
        val viewModel = testViewModel(repository)
        viewModel.selectConversation(ConversationDto(1, ConversationKind.DIRECT))
        viewModel.updateMessageSearchQuery("A")
        viewModel.updateMessageSearchQuery("B")

        pendingA.complete(org.example.project.data.ApiResult.Error(org.example.project.dto.ApiError("OLD", "stale")))
        assertTrue(viewModel.uiState.value.messages.searchLoading)
        assertNull(viewModel.uiState.value.messages.searchError)
        viewModel.selectConversation(ConversationDto(2, ConversationKind.GROUP))
        pendingB.complete(
            org.example.project.data.ApiResult.Success(
                org.example.project.dto.ConversationMessagePageDto(listOf(sentMessage(1)))
            )
        )
        assertTrue(viewModel.uiState.value.messages.searchResults.isEmpty())
        viewModel.closeConversation()
    }
}
