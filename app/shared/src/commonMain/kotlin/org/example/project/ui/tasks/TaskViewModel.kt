package org.example.project.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random
import org.example.project.data.ApiResult
import org.example.project.data.ChatAccessRevokedException
import org.example.project.data.ChatRepository
import org.example.project.data.appendDistinct
import org.example.project.data.isChatAccessTerminated
import org.example.project.data.ProjectRepository
import org.example.project.data.TaskRepository
import org.example.project.data.UserRepository
import org.example.project.dto.ApiError
import org.example.project.dto.AssignTaskRequest
import org.example.project.dto.BlockTaskRequest
import org.example.project.dto.CancelTaskRequest
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.CreateTaskRequest
import org.example.project.dto.ProjectDto
import org.example.project.dto.ReopenTaskRequest
import org.example.project.dto.ReviewDecision
import org.example.project.dto.ReviewTaskRequest
import org.example.project.dto.SendChatMessageRequest
import org.example.project.dto.SubmitResultRequest
import org.example.project.dto.TaskArea
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
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TasksUiState())
    val uiState: StateFlow<TasksUiState> = _uiState.asStateFlow()

    private var chatRealtimeJob: Job? = null
    private var pendingMessageKey: String? = null

    init {
        loadTasks()
        loadUsers()
        loadProjects()
    }

    fun loadTasks() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
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
        viewModelScope.launch {
            when (val result = userRepository.list()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(users = result.data)
                is ApiResult.Error -> { /* без права чтения справочника выбор участников скрывается */
                }
            }
        }
    }

    private fun loadProjects() {
        viewModelScope.launch {
            when (val result = projectRepository.list()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(projects = result.data)
                is ApiResult.Error -> { /* список проектов для выбора недоступен — форма покажет подсказку */
                }
            }
        }
    }

    fun selectTask(task: TaskDto) {
        chatRealtimeJob?.cancel()
        chatRealtimeJob = null
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
            chat = ChatUiState(isLoading = true)
        )
        pendingMessageKey = null
        viewModelScope.launch {
            refreshSelectedTask(task.id)
            loadChatInitial(task.id)
            if (_uiState.value.selectedTask?.id == task.id) {
                startChatRealtime(task.id)
            }
        }
    }

    fun backToList() {
        chatRealtimeJob?.cancel()
        chatRealtimeJob = null
        pendingMessageKey = null
        _uiState.value = _uiState.value.copy(
            selectedTask = null,
            actionError = null,
            createError = null,
            chat = ChatUiState()
        )
    }

    fun refreshSelectedTask(taskId: Long) {
        viewModelScope.launch {
            when (val result = taskRepository.get(taskId)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    selectedTask = result.data,
                    detailLoading = false
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    detailLoading = false,
                    actionError = result.error
                )
            }
        }
    }

    fun openTaskById(taskId: Long) {
        _uiState.value = _uiState.value.copy(actionError = null)
        viewModelScope.launch {
            when (val result = taskRepository.get(taskId)) {
                is ApiResult.Success -> selectTask(result.data)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(actionError = result.error)
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
        viewModelScope.launch {
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
        viewModelScope.launch {
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

    private suspend fun loadChatInitial(taskId: Long) {
        var collected = emptyList<ChatMessageDto>()
        var after: Long? = null
        var hasMore = false
        var iterations = 0
        while (iterations < MAX_CHAT_PAGES) {
            when (val result = chatRepository.listMessages(taskId, after = after)) {
                is ApiResult.Success -> {
                    collected = collected + result.data.messages
                    after = result.data.messages.lastOrNull()?.id
                    hasMore = result.data.hasMore
                    if (!result.data.hasMore || result.data.messages.isEmpty()) break
                }
                is ApiResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat.copy(
                            messages = collected,
                            isLoading = false,
                            error = result.error
                        )
                    )
                    return
                }
            }
            iterations++
        }
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(
                messages = collected,
                isLoading = false,
                hasMoreNewer = hasMore,
                error = null
            )
        )
    }

    fun loadMoreNewer() {
        val taskId = _uiState.value.selectedTask?.id ?: return
        val after = _uiState.value.chat.messages.lastOrNull()?.id ?: return
        _uiState.value = _uiState.value.copy(chat = _uiState.value.chat.copy(isLoadingMore = true))
        viewModelScope.launch {
            when (val result = chatRepository.listMessages(taskId, after = after)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    chat = _uiState.value.chat.copy(
                        messages = _uiState.value.chat.messages + result.data.messages,
                        isLoadingMore = false,
                        hasMoreNewer = result.data.hasMore
                    )
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    chat = _uiState.value.chat.copy(isLoadingMore = false, error = result.error)
                )
            }
        }
    }

    fun refreshChat(manual: Boolean) {
        val taskId = _uiState.value.selectedTask?.id ?: return
        val known = _uiState.value.chat.messages
        val after = known.lastOrNull()?.id
        if (after == null) {
            viewModelScope.launch { loadChatInitial(taskId) }
            return
        }
        viewModelScope.launch {
            when (val result = chatRepository.listMessages(taskId, after = after)) {
                is ApiResult.Success -> {
                    if (result.data.messages.isNotEmpty() || manual) {
                        _uiState.value = _uiState.value.copy(
                            chat = _uiState.value.chat.copy(
                                messages = known + result.data.messages,
                                hasMoreNewer = result.data.hasMore,
                                error = null
                            )
                        )
                    }
                }
                is ApiResult.Error -> if (manual) {
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat.copy(error = result.error)
                    )
                }
            }
        }
    }

    fun updateChatDraft(value: String) {
        _uiState.value = _uiState.value.copy(chat = _uiState.value.chat.copy(draft = value))
    }

    fun setChatFilter(filter: ChatMessageFilter) {
        _uiState.value = _uiState.value.copy(chat = _uiState.value.chat.copy(filter = filter))
    }

    fun sendMessage() {
        val taskId = _uiState.value.selectedTask?.id ?: return
        val content = _uiState.value.chat.draft
        if (content.isBlank()) {
            _uiState.value = _uiState.value.copy(
                chat = _uiState.value.chat.copy(
                    sendError = ApiError("VALIDATION_ERROR", "Введите текст сообщения")
                )
            )
            return
        }
        val key = pendingMessageKey ?: newIdempotencyKey().also { pendingMessageKey = it }
        _uiState.value = _uiState.value.copy(
            chat = _uiState.value.chat.copy(isSending = true, sendError = null)
        )
        viewModelScope.launch {
            when (val result = chatRepository.sendMessage(taskId, SendChatMessageRequest(content.trim(), key))) {
                is ApiResult.Success -> {
                    pendingMessageKey = null
                    val known = _uiState.value.chat.messages
                    val messages = if (known.any { it.id == result.data.id }) known else known + result.data
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat.copy(
                            messages = messages,
                            draft = "",
                            isSending = false,
                            sendError = null
                        )
                    )
                    refreshChat(manual = false)
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    chat = _uiState.value.chat.copy(isSending = false, sendError = result.error)
                )
            }
        }
    }

    private fun startChatRealtime(taskId: Long) {
        chatRealtimeJob?.cancel()
        chatRealtimeJob = viewModelScope.launch {
            var attempt = 0
            while (isActive && _uiState.value.selectedTask?.id == taskId) {
                try {
                    catchUpChat(taskId)
                    val lastId = _uiState.value.chat.messages.lastOrNull()?.id
                    chatRepository.observeMessages(taskId, lastId).collect { message ->
                        appendChatMessages(listOf(message))
                        refreshTaskQuiet(taskId)
                    }
                } catch (e: ChatAccessRevokedException) {
                    _uiState.value = _uiState.value.copy(
                        chat = _uiState.value.chat.copy(
                            error = ApiError("CHAT_ACCESS_ENDED", "Доступ к обсуждению завершён")
                        )
                    )
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // сетевой разрыв: переподключение с backoff, догон истории по курсору
                }
                attempt++
                delay(reconnectDelayMs(attempt))
            }
        }
    }

    private suspend fun catchUpChat(taskId: Long) {
        var after = _uiState.value.chat.messages.lastOrNull()?.id
        var iterations = 0
        while (iterations < MAX_CHAT_PAGES) {
            when (val result = chatRepository.listMessages(taskId, after = after)) {
                is ApiResult.Success -> {
                    appendChatMessages(result.data.messages)
                    if (!result.data.hasMore || result.data.messages.isEmpty()) break
                    after = result.data.messages.last().id
                }
                is ApiResult.Error -> {
                    if (isChatAccessTerminated(result.statusCode)) throw ChatAccessRevokedException()
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
        chatRealtimeJob?.cancel()
        super.onCleared()
    }

    private fun newIdempotencyKey(): String =
        "ui-${Random.nextLong().toString(16)}-${Random.nextLong().toString(16)}"

    private companion object {
        const val CHAT_RECONNECT_BASE_MS = 1_000L
        const val CHAT_RECONNECT_MAX_MS = 30_000L
        const val MAX_CHAT_PAGES = 10
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
    val createForm: TaskCreateForm = TaskCreateForm(),
    val assigneePickerId: Long? = null,
    val resultDraft: String = "",
    val returnReason: String = "",
    val blockReason: String = "",
    val unblockResponsibleId: Long? = null,
    val reopenReason: String = "",
    val cancelReason: String = "",
    val chat: ChatUiState = ChatUiState()
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
    val filter: ChatMessageFilter = ChatMessageFilter.ALL
)

enum class ChatMessageFilter(val label: String) {
    ALL("Все"),
    USER("Сообщения"),
    SYSTEM("События")
}

fun TaskDto.isClosedLike(): Boolean =
    status == TaskStatus.CLOSED || status == TaskStatus.CANCELLED
