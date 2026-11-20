package org.example.project.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.AttachmentFileData
import org.example.project.data.AttachmentRepository
import org.example.project.data.ChatAccessRevokedException
import org.example.project.data.ChatApiClient
import org.example.project.data.ChatRepository
import org.example.project.data.DocumentRepository
import org.example.project.data.TaskChatEventCursor
import org.example.project.data.TaskChatMessageEventType
import org.example.project.data.TaskChatRealtimeEvent
import org.example.project.data.appendDistinct
import org.example.project.data.chatTypingFrame
import org.example.project.ui.conversations.ConversationLocalStateStore
import org.example.project.ui.conversations.conversationCommandSignature
import org.example.project.data.mergeTaskChatMessage
import org.example.project.data.mergeTaskChatHistory
import org.example.project.data.isChatAccessTerminated
import org.example.project.data.ProjectRepository
import org.example.project.data.TaskRepository
import org.example.project.data.UserRepository
import org.example.project.data.pickAttachmentFile
import org.example.project.data.safeAttachmentFileName
import org.example.project.data.saveAttachmentFile
import org.example.project.data.validateAttachmentSelection
import org.example.project.dto.ApiError
import org.example.project.dto.AssignTaskRequest
import org.example.project.dto.AttachmentDto
import org.example.project.dto.AttachmentEntityType
import org.example.project.dto.BlockTaskRequest
import org.example.project.dto.CancelTaskRequest
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ChatSearchResultDto
import org.example.project.dto.CreateTaskRequest
import org.example.project.dto.DocumentDto
import org.example.project.dto.EditMessageRequest
import org.example.project.dto.ForwardMessageRequest
import org.example.project.dto.ConversationKind
import org.example.project.dto.ConversationListItemDto
import org.example.project.dto.ProjectDto
import org.example.project.dto.ReopenTaskRequest
import org.example.project.dto.ReplyPreviewDto
import org.example.project.dto.ReviewDecision
import org.example.project.dto.ReviewTaskRequest
import org.example.project.dto.SendChatMessageRequest
import org.example.project.dto.SubmitResultRequest
import org.example.project.dto.TaskArea
import org.example.project.dto.TaskChatListFilter
import org.example.project.dto.TaskChatListItemDto
import org.example.project.dto.TaskCommandRequest
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskPriority
import org.example.project.dto.TaskStatus
import org.example.project.dto.TaskWorkType
import org.example.project.dto.UserDto

class TaskViewModel(
    private val taskRepository: TaskRepository,
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository,
    private val projectRepository: ProjectRepository,
    private val attachmentRepository: AttachmentRepository,
    private val documentRepository: DocumentRepository,
    private val currentUserId: Long,
    coroutineScope: CoroutineScope? = null,
    private val reconnectDelay: suspend (Long) -> Unit = { delay(it) },
    private val chatFilePicker: ((AttachmentFileData) -> Unit, (String) -> Unit, Int, Long) -> Unit = ::pickAttachmentFile,
    private val conversationRepository: org.example.project.data.ConversationRepository? = null
) : ViewModel() {

    private val coroutineScope = coroutineScope ?: viewModelScope
    private val _uiState = MutableStateFlow(TasksUiState())
    val uiState: StateFlow<TasksUiState> = _uiState.asStateFlow()

    private var chatRealtimeJob: Job? = null
    private var chatListJob: Job? = null
    private var chatSearchJob: Job? = null
    private var chatSearchGeneration = 0L
    private var taskOpenJob: Job? = null
    private var taskOpenGeneration = 0L
    private var chatGeneration = 0L
    private var chatListGeneration = 0L
    private var shareGeneration = 0L
    private var shareIdempotencyKey: String? = null
    private val chatHistoryJobs = mutableSetOf<Job>()
    private val chatEventCursors = mutableMapOf<Long, TaskChatEventCursor>()
    private val pendingCommands = ConversationLocalStateStore()
    private val typingOut = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private var lastTypingSent: TimeMark? = null
    private var lastMarkedReadId: Long = 0L
    private var lastUnreadNotificationIds: Set<Long> = emptySet()

    init {
        loadTasks()
        loadUsers()
        loadShareUsers()
        loadProjects()
        loadChatList()
    }

    fun loadTasks() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        coroutineScope.launch {
            when (val result = taskRepository.list()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    tasks = result.data,
                    isLoading = false,
                    error = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.error
                )
            }
        }
    }

    private fun loadUsers() {
        coroutineScope.launch {
            when (val result = userRepository.list()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(users = result.data)
                is ApiResult.Error -> { /* без права чтения справочника выбор участников скрывается */
                }
            }
        }
    }

    private fun loadProjects() {
        coroutineScope.launch {
            when (val result = projectRepository.list()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(projects = result.data)
                is ApiResult.Error -> { /* список проектов для выбора недоступен — форма покажет подсказку */
                }
            }
        }
    }

    private fun advanceChatGeneration(): Long {
        chatGeneration++
        chatHistoryJobs.forEach { it.cancel() }
        chatHistoryJobs.clear()
        return chatGeneration
    }

    private fun isChatGenerationCurrent(taskId: Long, generation: Long): Boolean =
        generation == chatGeneration && _uiState.value.selectedTask?.id == taskId

    private fun launchChatHistory(generation: Long, block: suspend () -> Unit) {
        val job = coroutineScope.launch { block() }
        chatHistoryJobs += job
        job.invokeOnCompletion { chatHistoryJobs -= job }
    }

    private fun invalidateTaskOpen(): Long {
        taskOpenGeneration++
        taskOpenJob?.cancel()
        taskOpenJob = null
        return taskOpenGeneration
    }

    fun selectTask(task: TaskDto) {
        invalidateTaskOpen()
        chatSearchGeneration++
        chatSearchJob?.cancel()
        val generation = advanceChatGeneration()
        chatRealtimeJob?.cancel()
        chatRealtimeJob = null
        val previousId = _uiState.value.selectedTask?.id
        var drafts = _uiState.value.chatDrafts
        var files = _uiState.value.chatPendingFiles
        var replies = _uiState.value.chatReplies
        if (previousId != null && previousId != task.id) {
            drafts = drafts + (previousId to _uiState.value.chat.draft)
            files = if (_uiState.value.chat.pendingFiles.isNotEmpty()) {
                files + (previousId to _uiState.value.chat.pendingFiles)
            } else {
                files - previousId
            }
            replies = _uiState.value.chat.replyTo?.let { replies + (previousId to it) } ?: (replies - previousId)
        }
        lastMarkedReadId = 0L
        _uiState.value = _uiState.value.copy(
            selectedTask = task,
            detailLoading = true,
            actionError = null,
            assigneePickerId = null,
            resultDraft = task.result ?: "",
            returnReason = "",
            blockReason = "",
            unblockResponsibleId = null,
            reopenReason = "",
            cancelReason = "",
            workspaceFullCard = false,
            chat = ChatUiState(
                isLoading = true,
                draft = drafts[task.id] ?: "",
                pendingFiles = files[task.id].orEmpty(),
                replyTo = replies[task.id]
            ),
            chatDrafts = drafts,
            chatPendingFiles = files,
            chatReplies = replies
        )
        launchChatHistory(generation) {
            refreshSelectedTask(task.id)
            loadChatInitial(task.id, generation)
            loadTaskContext(task.id)
            if (isChatGenerationCurrent(task.id, generation)) {
                startChatRealtime(task.id, generation)
            }
        }
    }

    fun backToList() {
        invalidateTaskOpen()
        chatSearchGeneration++
        chatSearchJob?.cancel()
        advanceChatGeneration()
        chatRealtimeJob?.cancel()
        chatRealtimeJob = null
        _uiState.value = _uiState.value.copy(
            selectedTask = null,
            actionError = null,
            createError = null,
            chat = ChatUiState()
        )
    }

    fun refreshSelectedTask(taskId: Long) {
        coroutineScope.launch {
            when (val result = taskRepository.get(taskId)) {
                is ApiResult.Success -> if (_uiState.value.selectedTask?.id == taskId) {
                    _uiState.value = _uiState.value.copy(
                        selectedTask = result.data,
                        detailLoading = false
                    )
                }
                is ApiResult.Error -> if (_uiState.value.selectedTask?.id == taskId) {
                    _uiState.value = _uiState.value.copy(
                        detailLoading = false,
                        actionError = result.error
                    )
                }
            }
        }
    }

    fun openTaskById(taskId: Long) {
        val generation = invalidateTaskOpen()
        _uiState.value = _uiState.value.copy(actionError = null)
        taskOpenJob = coroutineScope.launch {
            when (val result = taskRepository.get(taskId)) {
                is ApiResult.Success -> if (generation == taskOpenGeneration) {
                    taskOpenJob = null
                    selectTask(result.data)
                }
                is ApiResult.Error -> if (generation == taskOpenGeneration) {
                    taskOpenJob = null
                    _uiState.value = _uiState.value.copy(actionError = result.error)
                }
            }
        }
    }

    fun createTask(currentUserId: Long) {
        val form = _uiState.value.createForm
        val validationError = validateCreateForm(form, currentUserId)
        if (validationError != null) {
            _uiState.value = _uiState.value.copy(createError = validationError)
            return
        }
        _uiState.value = _uiState.value.copy(createError = null)
        coroutineScope.launch {
            val request = CreateTaskRequest(
                title = form.title.trim(),
                description = form.description.takeIf { it.isNotBlank() }?.trim(),
                area = form.area,
                type = form.type,
                projectId = if (form.area == TaskArea.PROJECT) form.projectId else null,
                assigneeId = if (form.area == TaskArea.PERSONAL) null else form.assigneeId,
                reviewerId = if (form.type == TaskWorkType.REVIEWED) form.reviewerId else null,
                dueDate = form.dueDate.trim().takeIf { it.isNotEmpty() }?.let { "${it}T00:00:00Z" },
                priority = form.priority,
                acceptanceCriteria = if (form.type == TaskWorkType.REVIEWED) {
                    form.acceptanceCriteria.takeIf { it.isNotBlank() }?.trim()
                } else {
                    null
                }
            )
            when (val result = taskRepository.create(request)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        createForm = TaskCreateForm(),
                        createError = null
                    )
                    loadTasks()
                    selectTask(result.data)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(createError = result.error)
            }
        }
    }

    private fun validateCreateForm(form: TaskCreateForm, currentUserId: Long): ApiError? {
        if (form.title.isBlank()) {
            return ApiError("VALIDATION_ERROR", "Заполните обязательное поле: название")
        }
        if (form.area == TaskArea.PROJECT) {
            if (form.projectId == null) {
                return ApiError("VALIDATION_ERROR", "Выберите проект для проектной задачи")
            }
            if (form.assigneeId == null) {
                return ApiError("VALIDATION_ERROR", "Выберите исполнителя")
            }
        }
        val assigneeId = if (form.area == TaskArea.PERSONAL) currentUserId else form.assigneeId
        if (assigneeId != null && assigneeId != currentUserId && form.description.isBlank()) {
            return ApiError("VALIDATION_ERROR", "Описание обязательно при назначении задачи другому сотруднику")
        }
        if (form.type == TaskWorkType.REVIEWED) {
            if (form.reviewerId == null) {
                return ApiError("VALIDATION_ERROR", "Выберите проверяющего для задачи с приёмкой")
            }
            if (form.reviewerId == assigneeId) {
                return ApiError("VALIDATION_ERROR", "Проверяющий должен отличаться от исполнителя")
            }
            if (form.acceptanceCriteria.isBlank()) {
                return ApiError("VALIDATION_ERROR", "Заполните критерии приёмки")
            }
        }
        val dueDate = form.dueDate.trim()
        if (dueDate.isNotEmpty() && !dueDate.matches(DATE_PATTERN)) {
            return ApiError("VALIDATION_ERROR", "Срок выполнения укажите в формате ГГГГ-ММ-ДД")
        }
        return null
    }

    fun updateCreateForm(form: TaskCreateForm) {
        _uiState.value = _uiState.value.copy(createForm = form)
    }

    fun loadShareUsers() {
        _uiState.value = _uiState.value.copy(shareUsersLoading = true, shareUsersError = null)
        coroutineScope.launch {
            when (val result = userRepository.listOptions()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    shareUsers = result.data.filter { it.id != currentUserId },
                    shareUsersLoading = false,
                    shareUsersError = null
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    shareUsersLoading = false,
                    shareUsersError = result.error
                )
            }
        }
    }

    fun beginTaskShare() {
        shareGeneration++
        shareIdempotencyKey = newIdempotencyKey()
        _uiState.value = _uiState.value.copy(
            sharingTaskId = null,
            sharedTaskId = null,
            isSharingTask = false,
            shareError = null
        )
        if (_uiState.value.shareUsers.isEmpty() && !_uiState.value.shareUsersLoading) loadShareUsers()
    }

    fun finishTaskShare() {
        shareGeneration++
        shareIdempotencyKey = null
        _uiState.value = _uiState.value.copy(
            sharingTaskId = null,
            sharedTaskId = null,
            isSharingTask = false,
            shareError = null
        )
    }

    fun shareTask(task: TaskDto, recipientId: Long) {
        val repository = conversationRepository
        if (repository == null || recipientId == currentUserId || _uiState.value.isSharingTask) return
        val generation = shareGeneration
        val idempotencyKey = shareIdempotencyKey ?: newIdempotencyKey().also { shareIdempotencyKey = it }
        _uiState.value = _uiState.value.copy(
            sharingTaskId = task.id,
            sharedTaskId = null,
            isSharingTask = true,
            shareError = null
        )
        coroutineScope.launch {
            val conversationResult = repository.create(
                org.example.project.dto.CreateConversationRequest(
                    kind = ConversationKind.DIRECT,
                    memberIds = listOf(recipientId)
                )
            )
            when (conversationResult) {
                is ApiResult.Error -> if (generation == shareGeneration) {
                    _uiState.value = _uiState.value.copy(isSharingTask = false, shareError = conversationResult.error)
                }
                is ApiResult.Success -> {
                    if (generation != shareGeneration) return@launch
                    val result = repository.sendMessage(
                        conversationResult.data.id,
                        SendChatMessageRequest(taskShareContent(task), idempotencyKey),
                        file = null
                    )
                    if (generation != shareGeneration) return@launch
                    _uiState.value = when (result) {
                        is ApiResult.Success -> {
                            shareIdempotencyKey = null
                            _uiState.value.copy(
                                sharingTaskId = task.id,
                                sharedTaskId = task.id,
                                isSharingTask = false,
                                shareError = null
                            )
                        }
                        is ApiResult.Error -> _uiState.value.copy(
                            isSharingTask = false,
                            shareError = result.error
                        )
                    }
                }
            }
        }
    }

    fun updateAssigneePicker(userId: Long?) {
        _uiState.value = _uiState.value.copy(assigneePickerId = userId)
    }

    fun updateResultDraft(value: String) {
        _uiState.value = _uiState.value.copy(resultDraft = value)
    }

    fun updateReturnReason(value: String) {
        _uiState.value = _uiState.value.copy(returnReason = value)
    }

    fun updateBlockReason(value: String) {
        _uiState.value = _uiState.value.copy(blockReason = value)
    }

    fun updateUnblockResponsible(userId: Long?) {
        _uiState.value = _uiState.value.copy(unblockResponsibleId = userId)
    }

    fun updateReopenReason(value: String) {
        _uiState.value = _uiState.value.copy(reopenReason = value)
    }

    fun updateCancelReason(value: String) {
        _uiState.value = _uiState.value.copy(cancelReason = value)
    }

    fun assign(task: TaskDto) {
        val assigneeId = _uiState.value.assigneePickerId
        if (assigneeId == null) {
            _uiState.value = _uiState.value.copy(
                actionError = ApiError("VALIDATION_ERROR", "Выберите исполнителя")
            )
            return
        }
        runCommand(task) { taskRepository.assign(task.id, AssignTaskRequest(assigneeId, task.version)) }
    }

    fun accept(task: TaskDto) =
        runCommand(task) { taskRepository.accept(task.id, TaskCommandRequest(task.version)) }

    fun submitResult(task: TaskDto) {
        val result = _uiState.value.resultDraft
        if (result.isBlank()) {
            _uiState.value = _uiState.value.copy(
                actionError = ApiError("VALIDATION_ERROR", "Заполните результат выполнения")
            )
            return
        }
        runCommand(task) { taskRepository.submitResult(task.id, SubmitResultRequest(result, task.version)) }
    }

    fun sendToReview(task: TaskDto) =
        runCommand(task) { taskRepository.sendToReview(task.id, TaskCommandRequest(task.version)) }

    fun reviewAccept(task: TaskDto) =
        runCommand(task) {
            taskRepository.review(task.id, ReviewTaskRequest(ReviewDecision.ACCEPT, null, task.version))
        }

    fun reviewReturn(task: TaskDto) {
        val reason = _uiState.value.returnReason
        if (reason.isBlank()) {
            _uiState.value = _uiState.value.copy(
                actionError = ApiError("VALIDATION_ERROR", "Укажите причину возврата")
            )
            return
        }
        runCommand(task) {
            taskRepository.review(task.id, ReviewTaskRequest(ReviewDecision.RETURN, reason, task.version))
        }
    }

    fun close(task: TaskDto) =
        runCommand(task) { taskRepository.close(task.id, TaskCommandRequest(task.version)) }

    fun block(task: TaskDto) {
        val reason = _uiState.value.blockReason
        if (reason.isBlank()) {
            _uiState.value = _uiState.value.copy(
                actionError = ApiError("VALIDATION_ERROR", "Укажите причину блокировки")
            )
            return
        }
        runCommand(task) {
            taskRepository.block(
                task.id,
                BlockTaskRequest(reason, _uiState.value.unblockResponsibleId, task.version)
            )
        }
    }

    fun unblock(task: TaskDto) =
        runCommand(task) { taskRepository.unblock(task.id, TaskCommandRequest(task.version)) }

    fun reopen(task: TaskDto) {
        val reason = _uiState.value.reopenReason
        if (reason.isBlank()) {
            _uiState.value = _uiState.value.copy(
                actionError = ApiError("VALIDATION_ERROR", "Укажите причину повторного открытия")
            )
            return
        }
        runCommand(task) { taskRepository.reopen(task.id, ReopenTaskRequest(reason, task.version)) }
    }

    fun cancel(task: TaskDto) {
        val reason = _uiState.value.cancelReason
        if (reason.isBlank()) {
            _uiState.value = _uiState.value.copy(
                actionError = ApiError("VALIDATION_ERROR", "Укажите причину отмены")
            )
            return
        }
        runCommand(task) { taskRepository.cancel(task.id, CancelTaskRequest(reason, task.version)) }
    }

    private fun runCommand(task: TaskDto, command: suspend () -> ApiResult<TaskDto>) {
        _uiState.value = _uiState.value.copy(actionError = null)
        coroutineScope.launch {
            when (val result = command()) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        selectedTask = result.data,
                        actionError = null,
                        assigneePickerId = null,
                        resultDraft = result.data.result ?: "",
                        returnReason = "",
                        blockReason = "",
                        unblockResponsibleId = null,
                        reopenReason = "",
                        cancelReason = ""
                    )
                    loadTasks()
                    refreshChat(manual = false)
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(actionError = result.error)
                    if (result.statusCode == 409) {
                        refreshSelectedTask(task.id)
                    }
                }
            }
        }
    }

    private suspend fun loadChatInitial(taskId: Long, generation: Long) {
        var collected = emptyList<ChatMessageDto>()
        var after: Long? = null
        var hasMore = false
        var iterations = 0
        while (iterations < MAX_CHAT_PAGES && isChatGenerationCurrent(taskId, generation)) {
            when (val result = chatRepository.listMessages(taskId, after = after)) {
                is ApiResult.Success -> {
                    if (!isChatGenerationCurrent(taskId, generation)) return
                    collected = collected.mergeTaskChatHistory(result.data.messages)
                    after = result.data.messages.lastOrNull()?.id
                    hasMore = result.data.hasMore
                    if (!result.data.hasMore || result.data.messages.isEmpty()) break
                }
                is ApiResult.Error -> {
                    if (isChatGenerationCurrent(taskId, generation)) {
                        _uiState.value = _uiState.value.copy(
                            chat = _uiState.value.chat.copy(
                                messages = collected,
                                isLoading = false,
                                error = result.error
                            )
                        )
                    }
                    return
                }
            }
            iterations++
        }
        if (!isChatGenerationCurrent(taskId, generation)) return
        val hasUnread = _uiState.value.chatList.any { it.taskId == taskId && it.unreadCount > 0 }
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(
                messages = _uiState.value.chat.messages.mergeTaskChatHistory(collected),
                isLoading = false,
                hasMoreNewer = hasMore,
                firstUnreadMessageId = firstUnreadMessageId(
                    collected.map { it.id to it.author?.userId },
                    currentUserId,
                    hasUnread
                ),
                error = null
            )
        )
    }

    fun loadMoreNewer() {
        val taskId = _uiState.value.selectedTask?.id ?: return
        val generation = chatGeneration
        val after = _uiState.value.chat.messages.lastOrNull()?.id ?: return
        _uiState.value = _uiState.value.copy(chat = _uiState.value.chat.copy(isLoadingMore = true))
        launchChatHistory(generation) {
            when (val result = chatRepository.listMessages(taskId, after = after)) {
                is ApiResult.Success -> if (isChatGenerationCurrent(taskId, generation)) {
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat.copy(
                            messages = _uiState.value.chat.messages.mergeTaskChatHistory(result.data.messages),
                            isLoadingMore = false,
                            hasMoreNewer = result.data.hasMore
                        )
                    )
                }
                is ApiResult.Error -> if (isChatGenerationCurrent(taskId, generation)) {
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat.copy(isLoadingMore = false, error = result.error)
                    )
                }
            }
        }
    }

    fun refreshChat(manual: Boolean) {
        val taskId = _uiState.value.selectedTask?.id ?: return
        val generation = chatGeneration
        val after = _uiState.value.chat.messages.lastOrNull()?.id
        if (after == null) {
            launchChatHistory(generation) { loadChatInitial(taskId, generation) }
            return
        }
        launchChatHistory(generation) {
            when (val result = chatRepository.listMessages(taskId, after = after)) {
                is ApiResult.Success -> {
                    if (isChatGenerationCurrent(taskId, generation) &&
                        (result.data.messages.isNotEmpty() || manual)
                    ) {
                        _uiState.value = _uiState.value.copy(
                            chat = _uiState.value.chat.copy(
                                messages = _uiState.value.chat.messages.mergeTaskChatHistory(result.data.messages),
                                hasMoreNewer = result.data.hasMore,
                                error = null
                            )
                        )
                    }
                }
                is ApiResult.Error -> if (manual && isChatGenerationCurrent(taskId, generation)) {
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat.copy(error = result.error)
                    )
                }
            }
        }
    }

    fun updateChatDraft(value: String) {
        val taskId = _uiState.value.selectedTask?.id
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(draft = value),
            chatDrafts = if (taskId != null) {
                _uiState.value.chatDrafts + (taskId to value)
            } else {
                _uiState.value.chatDrafts
            }
        )
        if (value.isNotBlank()) notifyTyping()
    }

    fun setChatFilter(filter: ChatMessageFilter) {
        _uiState.value = _uiState.value.copy(chat = _uiState.value.chat.copy(filter = filter))
    }

    fun setReplyTo(message: ChatMessageDto) {
        val task = _uiState.value.selectedTask ?: return
        if (task.isClosedLike()) return
        val reply = ReplyPreviewDto(
            id = message.id,
            author = message.author,
            contentPreview = chatSnippet(message.content)
        )
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(replyTo = reply),
            chatReplies = _uiState.value.chatReplies + (task.id to reply)
        )
    }

    fun clearReply() {
        val taskId = _uiState.value.selectedTask?.id
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(replyTo = null),
            chatReplies = if (taskId == null) _uiState.value.chatReplies else _uiState.value.chatReplies - taskId
        )
    }

    fun sendMessage() {
        val task = _uiState.value.selectedTask ?: return
        if (task.isClosedLike()) return
        val taskId = task.id
        val content = _uiState.value.chat.draft
        val files = _uiState.value.chat.pendingFiles
        if (content.isBlank() && files.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                chat = _uiState.value.chat.copy(
                    sendError = ApiError("VALIDATION_ERROR", "Введите текст сообщения или выберите файл")
                )
            )
            return
        }
        val replyToMessageId = _uiState.value.chat.replyTo?.id
        val signature = conversationCommandSignature(content, replyToMessageId, files)
        val key = pendingCommands.commandKey(taskId.toString(), signature, ::newIdempotencyKey)
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(isSending = true, sendError = null)
        )
        coroutineScope.launch {
            val request = SendChatMessageRequest(content.trim(), key, replyToMessageId)
            val result = if (files.size <= 1) {
                chatRepository.sendMessage(taskId, request, files.singleOrNull())
            } else {
                (chatRepository as? ChatApiClient)?.sendMessage(taskId, request, files)
                    ?: ApiResult.Error(ApiError("UNSUPPORTED", "Отправка нескольких файлов не поддерживается"))
            }
            when (result) {
                is ApiResult.Success -> {
                    pendingCommands.clearComposerAfterSuccess(taskId.toString(), signature)
                    if (_uiState.value.selectedTask?.id == taskId) {
                        val messages = _uiState.value.chat.messages.mergeTaskChatHistory(listOf(result.data))
                        val unchanged = _uiState.value.chat.draft == content &&
                            _uiState.value.chat.pendingFiles == files &&
                            _uiState.value.chat.replyTo?.id == replyToMessageId
                        _uiState.value = _uiState.value.copy(
                            chat = _uiState.value.chat.copy(
                                messages = messages,
                                draft = if (unchanged) "" else _uiState.value.chat.draft,
                                pendingFiles = if (unchanged) emptyList() else _uiState.value.chat.pendingFiles,
                                replyTo = if (unchanged) null else _uiState.value.chat.replyTo,
                                isSending = false,
                                sendError = null
                            ),
                            chatDrafts = if (unchanged) _uiState.value.chatDrafts - taskId else _uiState.value.chatDrafts,
                            chatPendingFiles = if (unchanged) {
                                _uiState.value.chatPendingFiles - taskId
                            } else {
                                _uiState.value.chatPendingFiles
                            },
                            chatReplies = if (unchanged) _uiState.value.chatReplies - taskId else _uiState.value.chatReplies
                        )
                        refreshChat(manual = false)
                    }
                }
                is ApiResult.Error -> {
                    if (_uiState.value.selectedTask?.id == taskId) {
                        _uiState.value = _uiState.value.copy(
                            chat = _uiState.value.chat.copy(isSending = false, sendError = result.error)
                        )
                    }
                }
            }
        }
    }

    fun pickChatFile() {
        val task = _uiState.value.selectedTask ?: return
        if (task.isClosedLike()) return
        chatFilePicker(
            { file ->
                if (_uiState.value.selectedTask?.id == task.id) {
                    val validationError = validateAttachmentSelection(
                        _uiState.value.chat.pendingFiles.map { it.bytes.size.toLong() } + file.bytes.size.toLong()
                    )
                    val files = if (validationError == null) _uiState.value.chat.pendingFiles + file
                    else _uiState.value.chat.pendingFiles
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat.copy(pendingFiles = files, attachmentPickerError = validationError),
                        chatPendingFiles = _uiState.value.chatPendingFiles + (task.id to files)
                    )
                }
            },
            { error ->
                if (_uiState.value.selectedTask?.id == task.id) {
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat.copy(attachmentPickerError = error)
                    )
                }
            },
            _uiState.value.chat.pendingFiles.size,
            _uiState.value.chat.pendingFiles.sumOf { it.bytes.size.toLong() }
        )
    }

    fun removeChatPendingFile(file: AttachmentFileData) {
        val taskId = _uiState.value.selectedTask?.id ?: return
        val files = _uiState.value.chat.pendingFiles.filterNot { it === file }
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(pendingFiles = files),
            chatPendingFiles = if (files.isEmpty()) {
                _uiState.value.chatPendingFiles - taskId
            } else {
                _uiState.value.chatPendingFiles + (taskId to files)
            }
        )
    }

    fun clearChatPendingFile() {
        val taskId = _uiState.value.selectedTask?.id
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(pendingFiles = emptyList()),
            chatPendingFiles = if (taskId != null) {
                _uiState.value.chatPendingFiles - taskId
            } else {
                _uiState.value.chatPendingFiles
            }
        )
    }

    fun downloadChatAttachment(attachment: AttachmentDto) {
        if (_uiState.value.chat.downloadingAttachmentId != null) return
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(
                downloadingAttachmentId = attachment.id,
                downloadError = null
            )
        )
        coroutineScope.launch {
            when (val result = attachmentRepository.download(attachment.id)) {
                is ApiResult.Success -> saveAttachmentFile(
                    safeAttachmentFileName(attachment.fileName, attachment.id),
                    result.data.mimeType ?: attachment.mimeType,
                    result.data.bytes
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    chat = _uiState.value.chat.copy(downloadError = result.error)
                )
            }
            _uiState.value = _uiState.value.copy(
                chat = _uiState.value.chat.copy(downloadingAttachmentId = null)
            )
        }
    }

    private fun startChatRealtime(taskId: Long, generation: Long) {
        chatRealtimeJob?.cancel()
        chatRealtimeJob = coroutineScope.launch {
            var attempt = 0
            val cursor = chatEventCursors.getOrPut(taskId) { TaskChatEventCursor() }
            while (isActive && isChatGenerationCurrent(taskId, generation)) {
                try {
                    catchUpChat(taskId, generation)
                    val parameters = cursor.parameters()
                    chatRepository.observeEvents(
                        taskId,
                        _uiState.value.chat.messages.lastOrNull()?.id ?: parameters.first,
                        typingOut,
                        afterEvent = parameters.second
                    ).collect { event ->
                        if (!isChatGenerationCurrent(taskId, generation)) return@collect
                        if (!cursor.accept(event)) return@collect
                        when (event) {
                            is TaskChatRealtimeEvent.Message -> {
                                _uiState.value = _uiState.value.copy(
                                    chat = _uiState.value.chat.copy(
                                        messages = _uiState.value.chat.messages.mergeTaskChatMessage(event)
                                    )
                                )
                                if (event.type == TaskChatMessageEventType.MESSAGE) {
                                    refreshTaskQuiet(taskId)
                                }
                            }
                            is TaskChatRealtimeEvent.Reaction -> applyReaction(event)
                            is TaskChatRealtimeEvent.Read -> applyRead(event)
                            is TaskChatRealtimeEvent.Pin -> applyChatListPin(taskId, event.isPinned)
                            is TaskChatRealtimeEvent.Typing -> showTyping(event)
                        }
                    }
                } catch (e: ChatAccessRevokedException) {
                    if (isChatGenerationCurrent(taskId, generation)) revokeTaskChatAccess(taskId)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // сетевой разрыв: переподключение с backoff, догон истории по курсору
                }
                attempt++
                reconnectDelay(reconnectDelayMs(attempt))
            }
        }
    }

    private fun revokeTaskChatAccess(taskId: Long) {
        advanceChatGeneration()
        chatListGeneration++
        chatRealtimeJob?.cancel()
        chatRealtimeJob = null
        chatListJob?.cancel()
        chatListJob = null
        chatEventCursors -= taskId
        pendingCommands.clearSensitive(taskId.toString())
        lastMarkedReadId = 0
        lastTypingSent = null
        _uiState.value = _uiState.value.copy(
            selectedTask = null,
            actionError = ApiError("CHAT_ACCESS_ENDED", "Доступ к обсуждению завершён"),
            chat = ChatUiState(error = ApiError("CHAT_ACCESS_ENDED", "Доступ к обсуждению завершён")),
            chatList = _uiState.value.chatList.filterNot { it.taskId == taskId },
            chatDrafts = _uiState.value.chatDrafts - taskId,
            chatPendingFiles = _uiState.value.chatPendingFiles - taskId,
            taskAttachments = emptyList(),
            linkedDocuments = emptyList()
        )
    }

    private fun applyReaction(event: TaskChatRealtimeEvent.Reaction) {
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(
                messages = _uiState.value.chat.messages.map { message ->
                    if (message.id == event.messageId) message.copy(reactions = event.reactions) else message
                }
            )
        )
    }

    private fun applyRead(event: TaskChatRealtimeEvent.Read) {
        val previous = _uiState.value.chat.readCursors[event.userId] ?: 0
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(
                readCursors = _uiState.value.chat.readCursors +
                    (event.userId to maxOf(previous, event.lastReadMessageId))
            )
        )
    }

    private fun applyChatListPin(taskId: Long, isPinned: Boolean) {
        _uiState.value = _uiState.value.copy(
            chatList = _uiState.value.chatList.map {
                if (it.taskId == taskId) it.copy(isPinned = isPinned) else it
            }
        )
    }

    private fun showTyping(event: TaskChatRealtimeEvent.Typing) {
        if (event.userId == currentUserId) return
        val current = _uiState.value.chat.typingUsers
        if (event.fullName !in current) {
            _uiState.value = _uiState.value.copy(
                chat = _uiState.value.chat.copy(typingUsers = current + event.fullName)
            )
        }
        coroutineScope.launch {
            delay(TYPING_VISIBLE_MS)
            _uiState.value = _uiState.value.copy(
                chat = _uiState.value.chat.copy(
                    typingUsers = _uiState.value.chat.typingUsers - event.fullName
                )
            )
        }
    }

    fun notifyTyping() {
        val now = TimeSource.Monotonic.markNow()
        val last = lastTypingSent
        if (last != null && last.elapsedNow() < TYPING_THROTTLE) return
        lastTypingSent = now
        typingOut.tryEmit(chatTypingFrame())
    }

    /* ---------------------------- D-51: список чатов ---------------------------- */

    fun loadChatList() {
        val query = _uiState.value.chatListQuery
        val filter = _uiState.value.chatListFilter
        val generation = ++chatListGeneration
        _uiState.value = _uiState.value.copy(chatListLoading = true, chatListError = null)
        chatListJob?.cancel()
        chatListJob = coroutineScope.launch {
            when (val result = chatRepository.listChatTasks(query, filter)) {
                is ApiResult.Success -> if (generation == chatListGeneration) {
                    _uiState.value = _uiState.value.copy(
                        chatList = result.data,
                        chatListLoading = false,
                        chatListError = null
                    )
                }
                is ApiResult.Error -> if (generation == chatListGeneration) {
                    _uiState.value = _uiState.value.copy(
                        chatListLoading = false,
                        chatListError = result.error
                    )
                }
            }
        }
    }

    fun updateChatListQuery(query: String) {
        _uiState.value = _uiState.value.copy(chatListQuery = query)
        loadChatList()
    }

    fun setChatListFilter(filter: TaskChatListFilter) {
        if (_uiState.value.chatListFilter == filter) return
        _uiState.value = _uiState.value.copy(chatListFilter = filter)
        loadChatList()
    }

    /** Новое уведомление обновляет левый список чатов (unread/mention счётчики). */
    fun onNotificationsUpdated(notifications: List<org.example.project.dto.NotificationDto>) {
        val unreadIds = notifications.filter { !it.isRead }.map { it.id }.toSet()
        if (unreadIds != lastUnreadNotificationIds) {
            lastUnreadNotificationIds = unreadIds
            loadChatList()
        }
    }

    fun toggleChatPin(taskId: Long) {
        val item = _uiState.value.chatList.find { it.taskId == taskId } ?: return
        if (runCatching { TaskStatus.valueOf(item.status) }.getOrNull()?.let { !canMutateTaskChat(it) } == true) return
        coroutineScope.launch {
            when (val result = chatRepository.setChatPinned(taskId, !item.isPinned)) {
                is ApiResult.Success -> loadChatList()
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(chatListError = result.error)
            }
        }
    }

    /* ---------------------------- D-51: реакции ---------------------------- */

    fun toggleReaction(messageId: Long, emoji: String) {
        val task = _uiState.value.selectedTask ?: return
        if (task.isClosedLike()) return
        val taskId = task.id
        val message = _uiState.value.chat.messages.find { it.id == messageId } ?: return
        val active = message.reactions.find { it.emoji == emoji }?.reactedByMe != true
        coroutineScope.launch {
            when (val result = chatRepository.setChatReaction(taskId, messageId, emoji, active)) {
                is ApiResult.Success -> refreshChat(manual = false)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    chat = _uiState.value.chat.copy(error = result.error)
                )
            }
        }
    }

    fun editChatMessage(message: ChatMessageDto, content: String) {
        val task = _uiState.value.selectedTask ?: return
        if (task.isClosedLike() || message.author?.userId != currentUserId || message.revokedAt != null || content.isBlank()) return
        val client = chatRepository as? ChatApiClient ?: return
        coroutineScope.launch {
            when (val result = client.editMessage(task.id, message.id, EditMessageRequest(content.trim(), message.version))) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    chat = _uiState.value.chat.copy(
                        messages = _uiState.value.chat.messages.map { if (it.id == message.id) result.data else it }
                    )
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    chat = _uiState.value.chat.copy(error = result.error)
                )
            }
        }
    }

    fun revokeChatMessage(message: ChatMessageDto) {
        val task = _uiState.value.selectedTask ?: return
        if (task.isClosedLike() || message.author?.userId != currentUserId || message.revokedAt != null) return
        val client = chatRepository as? ChatApiClient ?: return
        coroutineScope.launch {
            when (val result = client.revokeMessage(task.id, message.id, message.version)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    chat = _uiState.value.chat.copy(
                        messages = _uiState.value.chat.messages.map { if (it.id == message.id) result.data else it }
                    )
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    chat = _uiState.value.chat.copy(error = result.error)
                )
            }
        }
    }

    fun forwardChatMessage(message: ChatMessageDto, target: ConversationListItemDto) {
        val task = _uiState.value.selectedTask ?: return
        val targetTask = _uiState.value.chatList.find { it.taskId == target.id && target.kind == ConversationKind.TASK }
        val targetClosed = targetTask?.let {
            runCatching { TaskStatus.valueOf(it.status) }.getOrNull()?.let { status -> !canMutateTaskChat(status) }
        } == true
        if (task.isClosedLike() || targetClosed || !target.canWrite || message.revokedAt != null) return
        val client = chatRepository as? ChatApiClient ?: return
        coroutineScope.launch {
            when (
                val result = client.forwardMessage(
                    task.id,
                    message.id,
                    ForwardMessageRequest(target.kind, target.id, newIdempotencyKey())
                )
            ) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    chat = _uiState.value.chat.copy(actionError = null, forwardedMessageId = message.id)
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    chat = _uiState.value.chat.copy(actionError = result.error)
                )
            }
        }
    }

    fun loadChatImagePreview(attachment: AttachmentDto) {
        val current = _uiState.value.chat
        if (attachment.id in current.imagePreviews || attachment.id in current.loadingImagePreviews) return
        _uiState.value = _uiState.value.copy(
            chat = current.copy(loadingImagePreviews = current.loadingImagePreviews + attachment.id)
        )
        coroutineScope.launch {
            when (val result = attachmentRepository.download(attachment.id)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    chat = _uiState.value.chat.copy(
                        imagePreviews = _uiState.value.chat.imagePreviews + (attachment.id to result.data.bytes),
                        loadingImagePreviews = _uiState.value.chat.loadingImagePreviews - attachment.id
                    )
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    chat = _uiState.value.chat.copy(
                        loadingImagePreviews = _uiState.value.chat.loadingImagePreviews - attachment.id
                    )
                )
            }
        }
    }

    /* ----------------------------- D-51: поиск ------------------------------ */

    fun updateChatSearchQuery(query: String) {
        val taskId = _uiState.value.selectedTask?.id
        val searchGeneration = ++chatSearchGeneration
        chatSearchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(searchQuery = query, searchError = null)
        )
        if (query.isBlank() || taskId == null) {
            _uiState.value = _uiState.value.copy(
                chat = _uiState.value.chat.copy(searchResults = emptyList(), searchLoading = false)
            )
            return
        }
        val generation = chatGeneration
        _uiState.value = _uiState.value.copy(chat = _uiState.value.chat.copy(searchLoading = true))
        chatSearchJob = coroutineScope.launch {
            when (val result = chatRepository.searchChatMessages(taskId, query)) {
                is ApiResult.Success -> {
                    if (searchGeneration == chatSearchGeneration &&
                        isChatGenerationCurrent(taskId, generation) &&
                        _uiState.value.chat.searchQuery == query
                    ) {
                        _uiState.value = _uiState.value.copy(
                            chat = _uiState.value.chat.copy(
                                searchResults = result.data,
                                searchLoading = false
                            )
                        )
                    }
                }
                is ApiResult.Error -> {
                    if (searchGeneration == chatSearchGeneration &&
                        isChatGenerationCurrent(taskId, generation) &&
                        _uiState.value.chat.searchQuery == query
                    ) {
                        _uiState.value = _uiState.value.copy(
                            chat = _uiState.value.chat.copy(
                                searchLoading = false,
                                searchError = result.error
                            )
                        )
                    }
                }
            }
        }
    }

    fun scrollToSearchResult(messageId: Long) {
        val taskId = _uiState.value.selectedTask?.id ?: return
        if (_uiState.value.chat.messages.any { it.id == messageId }) {
            showChatSearchTarget(messageId)
            return
        }
        val generation = chatGeneration
        _uiState.value = _uiState.value.copy(chat = _uiState.value.chat.copy(searchError = null))
        launchChatHistory(generation) { loadChatSearchTarget(taskId, generation, messageId) }
    }

    private fun showChatSearchTarget(messageId: Long) {
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(
                scrollToMessageId = messageId,
                highlightedMessageId = messageId,
                searchError = null
            )
        )
    }

    private suspend fun loadChatSearchTarget(taskId: Long, generation: Long, messageId: Long) {
        val loaded = _uiState.value.chat.messages
        var before = when {
            loaded.isEmpty() -> messageId + 1
            messageId < loaded.first().id -> loaded.first().id
            messageId > loaded.last().id -> null
            else -> messageId + 1
        }
        var after = if (before == null && loaded.isNotEmpty()) loaded.last().id else null
        repeat(MAX_SEARCH_TARGET_PAGES) {
            if (!isChatGenerationCurrent(taskId, generation)) return
            when (
                val result = chatRepository.listMessages(
                    taskId,
                    after = after,
                    before = before,
                    limit = SEARCH_HISTORY_PAGE_SIZE
                )
            ) {
                is ApiResult.Error -> {
                    if (isChatGenerationCurrent(taskId, generation)) {
                        _uiState.value = _uiState.value.copy(
                            chat = _uiState.value.chat.copy(searchError = result.error)
                        )
                    }
                    return
                }
                is ApiResult.Success -> {
                    if (!isChatGenerationCurrent(taskId, generation)) return
                    val merged = (_uiState.value.chat.messages + result.data.messages)
                        .associateBy { it.id }.values.sortedBy { it.id }
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat.copy(messages = merged)
                    )
                    if (merged.any { it.id == messageId }) {
                        showChatSearchTarget(messageId)
                        return
                    }
                    if (result.data.messages.isEmpty()) return@repeat
                    if (before != null) {
                        if (!result.data.hasMore) return@repeat
                        before = result.data.nextBefore ?: result.data.messages.last().id
                    } else {
                        if (!result.data.hasMore) return@repeat
                        after = result.data.nextAfter ?: result.data.messages.last().id
                    }
                }
            }
        }
        if (isChatGenerationCurrent(taskId, generation)) {
            _uiState.value = _uiState.value.copy(
                chat = _uiState.value.chat.copy(
                    searchError = ApiError(
                        "MESSAGE_TARGET_UNAVAILABLE",
                        "Сообщение недоступно или было удалено"
                    )
                )
            )
        }
    }

    fun clearScrollToMessage() {
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(scrollToMessageId = null)
        )
    }

    /* --------------------- D-51: контекст задачи справа --------------------- */

    private fun loadTaskContext(taskId: Long) {
        coroutineScope.launch {
            when (val result = attachmentRepository.list(AttachmentEntityType.TASK, taskId)) {
                is ApiResult.Success -> {
                    if (_uiState.value.selectedTask?.id == taskId) {
                        _uiState.value = _uiState.value.copy(taskAttachments = result.data)
                    }
                }
                is ApiResult.Error -> { /* контекст без вложений — секция покажет пустое состояние */
                }
            }
        }
        coroutineScope.launch {
            when (val result = documentRepository.list()) {
                is ApiResult.Success -> {
                    if (_uiState.value.selectedTask?.id == taskId) {
                        _uiState.value = _uiState.value.copy(
                            linkedDocuments = linkedTaskDocuments(result.data, taskId)
                        )
                    }
                }
                is ApiResult.Error -> { /* документы недоступны — секция покажет пустое состояние */
                }
            }
        }
    }

    fun updateChatVisibleRange(lastVisibleMessageId: Long?, viewingLatest: Boolean) {
        val taskId = _uiState.value.selectedTask?.id ?: return
        val latestLoadedMessageId = _uiState.value.chat.messages.lastOrNull()?.id
        if (shouldMarkTaskChatRead(lastVisibleMessageId, latestLoadedMessageId, viewingLatest)) {
            markChatReadUpTo(taskId, requireNotNull(lastVisibleMessageId))
        }
    }

    private fun markChatReadUpTo(taskId: Long, messageId: Long) {
        if (messageId <= lastMarkedReadId) return
        lastMarkedReadId = messageId
        coroutineScope.launch {
            when (val result = chatRepository.markChatRead(taskId, messageId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    chatList = _uiState.value.chatList.map {
                        if (it.taskId == taskId) it.copy(unreadCount = 0, mentionCount = 0) else it
                    }
                )
                is ApiResult.Error -> {
                    if (lastMarkedReadId == messageId) lastMarkedReadId = 0
                    if (_uiState.value.selectedTask?.id == taskId) {
                        _uiState.value = _uiState.value.copy(
                            chat = _uiState.value.chat.copy(error = result.error)
                        )
                    }
                }
            }
        }
    }

    fun openFullCard() {
        _uiState.value = _uiState.value.copy(workspaceFullCard = true)
    }

    fun closeFullCard() {
        _uiState.value = _uiState.value.copy(workspaceFullCard = false)
    }

    private suspend fun catchUpChat(taskId: Long, generation: Long) {
        var after = _uiState.value.chat.messages.lastOrNull()?.id
        var iterations = 0
        while (iterations < MAX_CHAT_PAGES && isChatGenerationCurrent(taskId, generation)) {
            when (val result = chatRepository.listMessages(taskId, after = after)) {
                is ApiResult.Success -> {
                    if (!isChatGenerationCurrent(taskId, generation)) return
                    appendChatMessages(result.data.messages)
                    if (!result.data.hasMore || result.data.messages.isEmpty()) break
                    after = result.data.messages.last().id
                }
                is ApiResult.Error -> {
                    if (isChatAccessTerminated(result.statusCode) && isChatGenerationCurrent(taskId, generation)) {
                        throw ChatAccessRevokedException()
                    }
                    break
                }
            }
            iterations++
        }
    }

    private fun appendChatMessages(incoming: List<ChatMessageDto>) {
        if (incoming.isEmpty()) return
        if (_uiState.value.selectedTask == null) return
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(
                messages = _uiState.value.chat.messages.appendDistinct(incoming)
            )
        )
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceAtMost(5)
        return (CHAT_RECONNECT_BASE_MS shl shift).coerceAtMost(CHAT_RECONNECT_MAX_MS)
    }

    private suspend fun refreshTaskQuiet(taskId: Long) {
        when (val result = taskRepository.get(taskId)) {
            is ApiResult.Success -> {
                if (_uiState.value.selectedTask?.id == taskId) {
                    _uiState.value = _uiState.value.copy(selectedTask = result.data)
                }
            }
            is ApiResult.Error -> { /* фоновое обновление не показывает ошибку */
            }
        }
    }

    override fun onCleared() {
        advanceChatGeneration()
        chatListGeneration++
        chatSearchGeneration++
        chatSearchJob?.cancel()
        chatRealtimeJob?.cancel()
        chatListJob?.cancel()
        super.onCleared()
    }

    private fun newIdempotencyKey(): String =
        "ui-${Random.nextLong().toString(16)}-${Random.nextLong().toString(16)}"

    private companion object {
        const val CHAT_RECONNECT_BASE_MS = 1_000L
        const val CHAT_RECONNECT_MAX_MS = 30_000L
        const val MAX_CHAT_PAGES = 10
        const val MAX_SEARCH_TARGET_PAGES = 10
        const val SEARCH_HISTORY_PAGE_SIZE = 200
        const val TYPING_VISIBLE_MS = 4_000L
        val TYPING_THROTTLE = 3.seconds
        val DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
    }
}

data class TasksUiState(
    val tasks: List<TaskDto> = emptyList(),
    val users: List<UserDto> = emptyList(),
    val projects: List<ProjectDto> = emptyList(),
    val selectedTask: TaskDto? = null,
    val isLoading: Boolean = false,
    val detailLoading: Boolean = false,
    val error: ApiError? = null,
    val actionError: ApiError? = null,
    val createError: ApiError? = null,
    val shareUsers: List<org.example.project.dto.UserOptionDto> = emptyList(),
    val shareUsersLoading: Boolean = false,
    val shareUsersError: ApiError? = null,
    val sharingTaskId: Long? = null,
    val sharedTaskId: Long? = null,
    val isSharingTask: Boolean = false,
    val shareError: ApiError? = null,
    val createForm: TaskCreateForm = TaskCreateForm(),
    val assigneePickerId: Long? = null,
    val resultDraft: String = "",
    val returnReason: String = "",
    val blockReason: String = "",
    val unblockResponsibleId: Long? = null,
    val reopenReason: String = "",
    val cancelReason: String = "",
    val chat: ChatUiState = ChatUiState(),
    val chatList: List<TaskChatListItemDto> = emptyList(),
    val chatListLoading: Boolean = false,
    val chatListError: ApiError? = null,
    val chatListQuery: String = "",
    val chatListFilter: TaskChatListFilter = TaskChatListFilter.ALL,
    val taskAttachments: List<AttachmentDto> = emptyList(),
    val linkedDocuments: List<DocumentDto> = emptyList(),
    val chatDrafts: Map<Long, String> = emptyMap(),
    val chatPendingFiles: Map<Long, List<AttachmentFileData>> = emptyMap(),
    val chatReplies: Map<Long, ReplyPreviewDto> = emptyMap(),
    val workspaceFullCard: Boolean = false
)

data class TaskCreateForm(
    val area: TaskArea = TaskArea.PERSONAL,
    val title: String = "",
    val description: String = "",
    val type: TaskWorkType = TaskWorkType.GENERAL,
    val projectId: Long? = null,
    val assigneeId: Long? = null,
    val reviewerId: Long? = null,
    val dueDate: String = "",
    val priority: TaskPriority = TaskPriority.NORMAL,
    val acceptanceCriteria: String = ""
)

data class ChatUiState(
    val messages: List<ChatMessageDto> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMoreNewer: Boolean = false,
    val isSending: Boolean = false,
    val error: ApiError? = null,
    val sendError: ApiError? = null,
    val draft: String = "",
    val filter: ChatMessageFilter = ChatMessageFilter.ALL,
    val pendingFiles: List<AttachmentFileData> = emptyList(),
    val downloadingAttachmentId: Long? = null,
    val downloadError: ApiError? = null,
    val actionError: ApiError? = null,
    val attachmentPickerError: String? = null,
    val forwardedMessageId: Long? = null,
    val replyTo: ReplyPreviewDto? = null,
    val readCursors: Map<Long, Long> = emptyMap(),
    val typingUsers: List<String> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<ChatSearchResultDto> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: ApiError? = null,
    val scrollToMessageId: Long? = null,
    val highlightedMessageId: Long? = null,
    val firstUnreadMessageId: Long? = null,
    val imagePreviews: Map<Long, ByteArray> = emptyMap(),
    val loadingImagePreviews: Set<Long> = emptySet()
) {
    val pendingFile: AttachmentFileData? get() = pendingFiles.firstOrNull()
}

enum class ChatMessageFilter(val label: String) {
    ALL("Все"),
    USER("Сообщения"),
    SYSTEM("События")
}

fun TaskDto.isClosedLike(): Boolean =
    status == TaskStatus.CLOSED || status == TaskStatus.CANCELLED
