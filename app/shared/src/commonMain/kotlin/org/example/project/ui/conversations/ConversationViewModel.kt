package org.example.project.ui.conversations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.AttachmentFileData
import org.example.project.data.AttachmentRepository
import org.example.project.data.ConversationAccessRevokedException
import org.example.project.data.ConversationRepository
import org.example.project.data.ProjectRepository
import org.example.project.data.TaskRepository
import org.example.project.data.UserRepository
import org.example.project.data.appendDistinctMessages
import org.example.project.data.isConversationAccessTerminated
import org.example.project.data.isSameConversation
import org.example.project.data.isVisibleTo
import org.example.project.data.pickAttachmentFile
import org.example.project.data.safeAttachmentFileName
import org.example.project.data.saveAttachmentFile
import org.example.project.data.toVirtualConversation
import org.example.project.dto.ApiError
import org.example.project.dto.AttachmentDto
import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind
import org.example.project.dto.ConversationMessageDto
import org.example.project.dto.CreateConversationRequest
import org.example.project.dto.ProjectDto
import org.example.project.dto.SendChatMessageRequest
import org.example.project.dto.TaskDto
import org.example.project.dto.UserDto
import org.example.project.dto.UserOptionDto

/**
 * Единый Chat: DIRECT/GROUP из Conversations API, PROJECT — get-or-create
 * через POST /api/projects/{id}/chat, TASK — виртуальные записи поверх
 * существующего Task Chat API (отрицательный id = -taskId).
 * Раздел «Чат» и проектная вкладка «Обсуждение» используют отдельные
 * экземпляры, чтобы не смешивать состояния.
 */
class ConversationViewModel(
    private val conversationRepository: ConversationRepository,
    private val taskRepository: TaskRepository,
    private val projectRepository: ProjectRepository,
    private val attachmentRepository: AttachmentRepository,
    private val userRepository: UserRepository,
    val currentUser: UserDto
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    private var realtimeJob: Job? = null
    private var pendingMessageKey: String? = null

    init {
        loadAll()
        loadUserOptions()
    }

    fun loadAll() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            when (val result = conversationRepository.list()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    conversations = result.data,
                    isLoading = false,
                    error = null
                ).withMergedEntries(currentUser)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = result.error
                )
            }
        }
        viewModelScope.launch {
            when (val result = taskRepository.list()) {
                is ApiResult.Success ->
                    _uiState.value = _uiState.value.copy(tasks = result.data).withMergedEntries(currentUser)
                is ApiResult.Error -> { /* задачи недоступны — список покажет остальные виды чатов */
                }
            }
        }
        viewModelScope.launch {
            when (val result = projectRepository.list()) {
                is ApiResult.Success ->
                    _uiState.value = _uiState.value.copy(projects = result.data).withMergedEntries(currentUser)
                is ApiResult.Error -> { /* проекты недоступны — список покажет остальные виды чатов */
                }
            }
        }
    }

    fun loadConversations() = loadAll()

    private fun loadUserOptions() {
        viewModelScope.launch {
            when (val result = userRepository.listOptions()) {
                is ApiResult.Success ->
                    _uiState.value = _uiState.value.copy(userOptions = result.data).withMergedEntries(currentUser)
                is ApiResult.Error -> { /* без справочника создание чата покажет ошибку */
                }
            }
        }
    }

    /** Выбор записи единого списка; виртуальный PROJECT сначала материализуется на сервере. */
    fun openEntry(conversation: ConversationDto) {
        if (conversation.kind == ConversationKind.PROJECT && conversation.id < 0) {
            conversation.projectId?.let { openProjectChat(it) }
            return
        }
        selectConversation(conversation)
    }

    /** Task Chat обслуживается отдельным API; не запускаем его через Conversations API. */
    fun openTaskChat(conversation: ConversationDto) {
        require(conversation.kind == ConversationKind.TASK)
        realtimeJob?.cancel()
        realtimeJob = null
        pendingMessageKey = null
        _uiState.value = _uiState.value.copy(
            selectedConversation = conversation,
            messages = ConversationMessagesUiState()
        )
    }

    fun selectConversation(conversation: ConversationDto) {
        if (isSameConversation(_uiState.value.selectedConversation, conversation.id, conversation.kind)) return
        realtimeJob?.cancel()
        realtimeJob = null
        pendingMessageKey = null
        _uiState.value = _uiState.value.copy(
            selectedConversation = conversation,
            messages = ConversationMessagesUiState(isLoading = true)
        )
        viewModelScope.launch {
            loadMessagesInitial(conversation.id, conversation.kind)
            if (isSameConversation(_uiState.value.selectedConversation, conversation.id, conversation.kind)) {
                startRealtime(conversation.id, conversation.kind)
            }
        }
    }

    /** Прямой переход (например, из уведомления): сервер повторно проверяет права. */
    fun openConversationById(conversationId: Long) {
        _uiState.value = _uiState.value.copy(error = null)
        viewModelScope.launch {
            when (val result = conversationRepository.get(conversationId)) {
                is ApiResult.Success -> selectConversation(result.data)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(error = result.error)
            }
        }
    }

    /** Проектное обсуждение: get-or-create на сервере, права проверяются там же. */
    fun openProjectChat(projectId: Long) {
        val current = _uiState.value.selectedConversation
        if (current?.kind == ConversationKind.PROJECT && current.projectId == projectId && current.id > 0) return
        realtimeJob?.cancel()
        realtimeJob = null
        pendingMessageKey = null
        _uiState.value = _uiState.value.copy(
            selectedConversation = null,
            messages = ConversationMessagesUiState(isLoading = true)
        )
        viewModelScope.launch {
            when (val result = conversationRepository.getOrCreateProjectChat(projectId)) {
                is ApiResult.Success -> selectConversation(result.data)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    messages = ConversationMessagesUiState(isLoading = false, error = result.error)
                )
            }
        }
    }

    fun closeConversation() {
        realtimeJob?.cancel()
        realtimeJob = null
        pendingMessageKey = null
        _uiState.value = _uiState.value.copy(
            selectedConversation = null,
            messages = ConversationMessagesUiState()
        )
    }

    fun backToList() = closeConversation()

    fun createDirect(memberId: Long) {
        createConversation(CreateConversationRequest(kind = ConversationKind.DIRECT, memberIds = listOf(memberId)))
    }

    fun createGroup(title: String, memberIds: List<Long>) {
        if (title.isBlank()) {
            _uiState.value = _uiState.value.copy(
                createError = ApiError("VALIDATION_ERROR", "Укажите название группового чата")
            )
            return
        }
        if (memberIds.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                createError = ApiError("VALIDATION_ERROR", "Выберите хотя бы одного участника")
            )
            return
        }
        createConversation(
            CreateConversationRequest(
                kind = ConversationKind.GROUP,
                title = title.trim(),
                memberIds = memberIds
            )
        )
    }

    private fun createConversation(request: CreateConversationRequest) {
        _uiState.value = _uiState.value.copy(createError = null)
        viewModelScope.launch {
            when (val result = conversationRepository.create(request)) {
                is ApiResult.Success -> {
                    selectConversation(result.data)
                    loadAll()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(createError = result.error)
            }
        }
    }

    fun dismissCreateError() {
        _uiState.value = _uiState.value.copy(createError = null)
    }

    fun updateDraft(value: String) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(draft = value)
        )
    }

    fun pickFile() {
        if (_uiState.value.selectedConversation == null) return
        pickAttachmentFile { file ->
            if (_uiState.value.selectedConversation != null) {
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages.copy(pendingFile = file)
                )
            }
        }
    }

    fun clearPendingFile() {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(pendingFile = null)
        )
    }

    fun sendMessage() {
        val selected = _uiState.value.selectedConversation ?: return
        if (!selected.canWrite) return
        val content = _uiState.value.messages.draft
        if (content.isBlank() && _uiState.value.messages.pendingFile == null) {
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.copy(
                    sendError = ApiError("VALIDATION_ERROR", "Введите текст сообщения")
                )
            )
            return
        }
        val file = _uiState.value.messages.pendingFile
        val key = pendingMessageKey ?: newIdempotencyKey().also { pendingMessageKey = it }
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(isSending = true, sendError = null)
        )
        viewModelScope.launch {
            when (val result = conversationRepository.sendMessage(
                selected.id,
                SendChatMessageRequest(content.trim(), key),
                file
            )) {
                is ApiResult.Success -> {
                    pendingMessageKey = null
                    // поздний ответ применяется, только если разговор всё ещё выбран
                    if (isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages.copy(
                                messages = _uiState.value.messages.messages.appendDistinctMessages(
                                    listOf(result.data)
                                ),
                                draft = "",
                                pendingFile = null,
                                isSending = false,
                                sendError = null
                            )
                        )
                    }
                }
                is ApiResult.Error -> {
                    if (isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) {
                        // текст и выбранный файл сохраняются при ошибке
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages.copy(isSending = false, sendError = result.error)
                        )
                    }
                }
            }
        }
    }

    fun downloadAttachment(attachment: AttachmentDto) {
        if (_uiState.value.messages.downloadingAttachmentId != null) return
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(
                downloadingAttachmentId = attachment.id,
                downloadError = null
            )
        )
        viewModelScope.launch {
            when (val result = attachmentRepository.download(attachment.id)) {
                is ApiResult.Success -> saveAttachmentFile(
                    safeAttachmentFileName(attachment.fileName, attachment.id),
                    result.data.mimeType ?: attachment.mimeType,
                    result.data.bytes
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages.copy(downloadError = result.error)
                )
            }
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.copy(downloadingAttachmentId = null)
            )
        }
    }

    fun refreshMessages(manual: Boolean = true) {
        val selected = _uiState.value.selectedConversation ?: return
        viewModelScope.launch {
            catchUp(selected.id, selected.kind, reportErrors = manual)
        }
    }

    fun loadMoreNewer() {
        val selected = _uiState.value.selectedConversation ?: return
        val after = _uiState.value.messages.messages.lastOrNull()?.id ?: return
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(isLoadingMore = true)
        )
        viewModelScope.launch {
            when (val result = conversationRepository.listMessages(selected.id, after = after)) {
                is ApiResult.Success -> {
                    if (isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages.copy(
                                messages = _uiState.value.messages.messages.appendDistinctMessages(
                                    result.data.messages
                                ),
                                isLoadingMore = false,
                                hasMoreNewer = result.data.hasMore
                            )
                        )
                    }
                }
                is ApiResult.Error -> {
                    if (isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages.copy(isLoadingMore = false, error = result.error)
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadMessagesInitial(conversationId: Long, kind: ConversationKind) {
        var collected = emptyList<ConversationMessageDto>()
        var after: Long? = null
        var hasMore = false
        var iterations = 0
        while (iterations < MAX_MESSAGE_PAGES) {
            when (val result = conversationRepository.listMessages(conversationId, after = after)) {
                is ApiResult.Success -> {
                    collected = collected + result.data.messages
                    after = result.data.messages.lastOrNull()?.id
                    hasMore = result.data.hasMore
                    if (!result.data.hasMore || result.data.messages.isEmpty()) break
                }
                is ApiResult.Error -> {
                    if (isSameConversation(_uiState.value.selectedConversation, conversationId, kind)) {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages.copy(
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
        if (isSameConversation(_uiState.value.selectedConversation, conversationId, kind)) {
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.copy(
                    messages = collected,
                    isLoading = false,
                    hasMoreNewer = hasMore,
                    error = null
                )
            )
        }
    }

    private fun startRealtime(conversationId: Long, kind: ConversationKind) {
        realtimeJob?.cancel()
        realtimeJob = viewModelScope.launch {
            var attempt = 0
            while (isActive && isSameConversation(_uiState.value.selectedConversation, conversationId, kind)) {
                try {
                    catchUp(conversationId, kind, reportErrors = false)
                    val lastId = _uiState.value.messages.messages.lastOrNull()?.id
                    conversationRepository.observeMessages(conversationId, lastId).collect { message ->
                        appendMessages(conversationId, kind, listOf(message))
                    }
                } catch (e: ConversationAccessRevokedException) {
                    if (isSameConversation(_uiState.value.selectedConversation, conversationId, kind)) {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages.copy(
                                error = ApiError("CONVERSATION_ACCESS_ENDED", "Доступ к обсуждению завершён")
                            )
                        )
                    }
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

    private suspend fun catchUp(conversationId: Long, kind: ConversationKind, reportErrors: Boolean) {
        var after = _uiState.value.messages.messages.lastOrNull()?.id
        var iterations = 0
        while (iterations < MAX_MESSAGE_PAGES) {
            when (val result = conversationRepository.listMessages(conversationId, after = after)) {
                is ApiResult.Success -> {
                    appendMessages(conversationId, kind, result.data.messages)
                    if (!result.data.hasMore || result.data.messages.isEmpty()) break
                    after = result.data.messages.last().id
                }
                is ApiResult.Error -> {
                    if (isConversationAccessTerminated(result.statusCode)) {
                        throw ConversationAccessRevokedException()
                    }
                    if (reportErrors && isSameConversation(
                            _uiState.value.selectedConversation, conversationId, kind
                        )
                    ) {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages.copy(error = result.error)
                        )
                    }
                    break
                }
            }
            iterations++
        }
    }

    private fun appendMessages(
        conversationId: Long,
        kind: ConversationKind,
        incoming: List<ConversationMessageDto>
    ) {
        if (incoming.isEmpty()) return
        if (!isSameConversation(_uiState.value.selectedConversation, conversationId, kind)) return
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(
                messages = _uiState.value.messages.messages.appendDistinctMessages(incoming)
            )
        )
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceAtMost(5)
        return (RECONNECT_BASE_MS shl shift).coerceAtMost(RECONNECT_MAX_MS)
    }

    override fun onCleared() {
        realtimeJob?.cancel()
        super.onCleared()
    }

    private fun newIdempotencyKey(): String =
        "ui-conv-${Random.nextLong().toString(16)}-${Random.nextLong().toString(16)}"

    private companion object {
        const val RECONNECT_BASE_MS = 1_000L
        const val RECONNECT_MAX_MS = 30_000L
        const val MAX_MESSAGE_PAGES = 10
    }
}

internal fun ConversationsUiState.withMergedEntries(currentUser: UserDto): ConversationsUiState {
    val coveredProjects = conversations
        .mapNotNull { if (it.kind == ConversationKind.PROJECT) it.projectId else null }
        .toSet()
    val virtualProjects = projects
        .filter { it.isVisibleTo(currentUser) && it.id !in coveredProjects }
        .map { it.toVirtualConversation() }
    val virtualTasks = tasks.map { it.toVirtualConversation(currentUser, userOptions) }
    val namedConversations = conversations.map { conversation ->
        val project = projects.find { it.id == conversation.projectId }
        if (conversation.kind == ConversationKind.PROJECT && conversation.title.isNullOrBlank() && project != null) {
            conversation.copy(title = "${project.number} · ${project.name}")
        } else {
            conversation
        }
    }
    val merged = (namedConversations + virtualProjects + virtualTasks).sortedBy {
        when (it.kind) {
            ConversationKind.DIRECT -> 0
            ConversationKind.GROUP -> 1
            ConversationKind.PROJECT -> 2
            ConversationKind.TASK -> 3
        }
    }
    return copy(entries = merged)
}

data class ConversationsUiState(
    val conversations: List<ConversationDto> = emptyList(),
    val tasks: List<TaskDto> = emptyList(),
    val projects: List<ProjectDto> = emptyList(),
    val entries: List<ConversationDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: ApiError? = null,
    val selectedConversation: ConversationDto? = null,
    val messages: ConversationMessagesUiState = ConversationMessagesUiState(),
    val userOptions: List<UserOptionDto> = emptyList(),
    val createError: ApiError? = null
)

data class ConversationMessagesUiState(
    val messages: List<ConversationMessageDto> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMoreNewer: Boolean = false,
    val isSending: Boolean = false,
    val error: ApiError? = null,
    val sendError: ApiError? = null,
    val draft: String = "",
    val pendingFile: AttachmentFileData? = null,
    val downloadingAttachmentId: Long? = null,
    val downloadError: ApiError? = null
)
