package org.example.project.ui.conversations

import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
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
import org.example.project.data.ConversationApiClient
import org.example.project.data.ConversationDisconnect
import org.example.project.data.ConversationMessageEventType
import org.example.project.data.ConversationOverflowException
import org.example.project.data.ConversationRealtimeEvent
import org.example.project.data.ConversationRealtimeMerger
import org.example.project.data.ConversationReconnectCursor
import org.example.project.data.ConversationRepository
import org.example.project.data.ProjectRepository
import org.example.project.data.TaskRepository
import org.example.project.data.UserRepository
import org.example.project.data.conversationKey
import org.example.project.data.isConversationAccessTerminated
import org.example.project.data.isSameConversation
import org.example.project.data.pickAttachmentFile
import org.example.project.data.safeAttachmentFileName
import org.example.project.data.saveAttachmentFile
import org.example.project.data.validateAttachmentSelection
import org.example.project.dto.ApiError
import org.example.project.dto.AttachmentDto
import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind
import org.example.project.dto.ConversationListFilter
import org.example.project.dto.ConversationListItemDto
import org.example.project.dto.ConversationDialogStateRequest
import org.example.project.dto.ConversationMessageDto
import org.example.project.dto.CreateConversationRequest
import org.example.project.dto.EditMessageRequest
import org.example.project.dto.ForwardMessageRequest
import org.example.project.dto.ProjectDto
import org.example.project.dto.SendChatMessageRequest
import org.example.project.dto.TaskDto
import org.example.project.dto.UserDto
import org.example.project.dto.UserOptionDto
import org.example.project.ui.tasks.firstUnreadMessageId

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
    val currentUser: UserDto,
    coroutineScope: CoroutineScope? = null,
    private val reconnectDelay: suspend (Long) -> Unit = { delay(it) }
) : ViewModel() {

    private val coroutineScope = coroutineScope ?: viewModelScope
    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    private var realtimeJob: Job? = null
    private var historyJob: Job? = null
    private var queryJob: Job? = null
    private var messageSearchJob: Job? = null
    private var messageSearchGeneration = 0L
    private val localState = ConversationLocalStateStore()
    private val reconnectCursors = mutableMapOf<String, ConversationReconnectCursor>()
    private val lastReadRequests = mutableMapOf<String, Long>()
    private var realtimeMerger = ConversationRealtimeMerger()
    private var listRequestVersion = 0
    private var released = false
    private val navigationGate = ConversationNavigationGate()

    init {
        loadAll()
        loadUserOptions()
    }

    fun loadAll() {
        if (released) return
        queryJob?.cancel()
        coroutineScope.launch { refreshPresence() }
        loadWorkspace(append = false)
    }

    fun loadMore() {
        val state = _uiState.value
        if (
            !released && state.hasMore && !state.isLoading && !state.isLoadingMore &&
            state.nextCursor != null
        ) {
            loadWorkspace(append = true)
        }
    }

    fun updateQuery(value: String) {
        if (released || _uiState.value.query == value) return
        listRequestVersion++
        _uiState.value = _uiState.value.copy(
            query = value,
            nextCursor = null,
            hasMore = false,
            isLoading = true,
            isLoadingMore = false,
            error = null
        )
        queryJob?.cancel()
        queryJob = coroutineScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            loadWorkspace(append = false)
        }
    }

    fun selectKind(value: ConversationKind?) {
        if (released || _uiState.value.kindFilter == value) return
        _uiState.value = _uiState.value.copy(kindFilter = value)
        loadAll()
    }

    fun selectFilter(value: ConversationListFilter) {
        if (released || _uiState.value.listFilter == value) return
        _uiState.value = _uiState.value.copy(listFilter = value)
        loadAll()
    }

    private fun loadWorkspace(append: Boolean) {
        if (released) return
        val state = _uiState.value
        val requestVersion = if (append) listRequestVersion else ++listRequestVersion
        val cursor = if (append) state.nextCursor ?: return else null
        if (append && (state.isLoading || state.isLoadingMore || !state.hasMore)) return
        _uiState.value = if (append) {
            state.copy(isLoadingMore = true, error = null)
        } else {
            state.copy(
                nextCursor = null,
                hasMore = false,
                isLoading = true,
                isLoadingMore = false,
                error = null
            )
        }
        coroutineScope.launch {
            when (
                val result = conversationRepository.listWorkspace(
                    query = state.query.takeIf { it.isNotBlank() },
                    kind = state.kindFilter,
                    filter = state.listFilter,
                    cursor = cursor,
                    limit = WORKSPACE_PAGE_SIZE
                )
            ) {
                is ApiResult.Success -> {
                    if (released || requestVersion != listRequestVersion) return@launch
                    if (append && _uiState.value.nextCursor != cursor) return@launch
                    val items = if (append) {
                        mergeWorkspaceItems(_uiState.value.items, result.data.items)
                    } else {
                        result.data.items
                    }
                    _uiState.value = _uiState.value.copy(
                        items = items,
                        conversations = items.map { it.toConversationDto() },
                        nextCursor = result.data.nextCursor,
                        hasMore = result.data.hasMore,
                        isLoading = false,
                        isLoadingMore = false,
                        error = null
                    ).withMergedEntries(currentUser)
                }
                is ApiResult.Error -> {
                    if (released || requestVersion != listRequestVersion) return@launch
                    if (append && _uiState.value.nextCursor != cursor) return@launch
                    _uiState.value = if (append) {
                        _uiState.value.copy(isLoadingMore = false, error = result.error)
                    } else {
                        _uiState.value.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = result.error
                        )
                    }
                }
            }
        }
    }

    fun loadConversations() = loadAll()

    fun loadUserOptions() {
        if (released) return
        _uiState.value = _uiState.value.copy(isLoadingUserOptions = true, userOptionsError = null)
        coroutineScope.launch {
            if (released) return@launch
            when (val result = userRepository.listOptions()) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    userOptions = result.data,
                    isLoadingUserOptions = false,
                    userOptionsError = null
                ).withMergedEntries(currentUser)
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isLoadingUserOptions = false,
                    userOptionsError = result.error
                )
            }
        }
    }

    fun openListItem(item: ConversationListItemDto) {
        openEntry(item.toConversationDto())
    }

    /** Выбор записи единого списка; виртуальный PROJECT сначала материализуется на сервере. */
    fun openEntry(conversation: ConversationDto) {
        if (conversation.kind == ConversationKind.PROJECT && conversation.id < 0) {
            conversation.projectId?.let { openProjectChat(it) }
            return
        }
        selectConversation(conversation, refreshDetails = true)
    }

    /** Task Chat использует общий экран; репозиторий направляет вызовы в Task Chat API. */
    fun openTaskChat(conversation: ConversationDto) {
        if (released) return
        require(conversation.kind == ConversationKind.TASK)
        selectConversation(conversation)
    }

    fun selectConversation(conversation: ConversationDto, refreshDetails: Boolean = false) {
        if (released || isSameConversation(_uiState.value.selectedConversation, conversation.id, conversation.kind)) return
        val navigation = navigationGate.next()
        messageSearchGeneration++
        messageSearchJob?.cancel()
        realtimeJob?.cancel()
        realtimeJob = null
        historyJob?.cancel()
        historyJob = null
        realtimeMerger = ConversationRealtimeMerger(
            initialEventId = reconnectCursors[conversation.localKey()]?.lastEventId ?: 0
        )
        val prevKey = _uiState.value.selectedConversation?.localKey()
        val updatedDrafts = if (prevKey != null) {
            val d = localState.draft(prevKey)
            if (d.isBlank()) _uiState.value.drafts - prevKey else _uiState.value.drafts + (prevKey to d)
        } else _uiState.value.drafts
        _uiState.value = _uiState.value.copy(
            selectedConversation = conversation,
            messages = restoredMessages(conversation, isLoading = true),
            drafts = updatedDrafts
        )
        historyJob = coroutineScope.launch {
            if (refreshDetails && conversation.id > 0 && conversation.pinnedMessages.isEmpty()) {
                launch {
                    val fresh = conversationRepository.get(conversation.id)
                    if (fresh is ApiResult.Success &&
                        navigationGate.isCurrent(navigation) &&
                        isSameConversation(_uiState.value.selectedConversation, conversation.id, conversation.kind)
                    ) {
                        _uiState.value = _uiState.value.copy(selectedConversation = fresh.data)
                    }
                }
            }
            loadMessagesInitial(conversation.id, conversation.kind, navigation)
            if (navigationGate.isCurrent(navigation) &&
                isSameConversation(_uiState.value.selectedConversation, conversation.id, conversation.kind)
            ) {
                startRealtime(conversation.id, conversation.kind, navigation)
            }
        }
    }

    /** Прямой переход (например, из уведомления): сервер повторно проверяет права. */
    fun openConversationById(conversationId: Long) {
        if (released) return
        val navigation = navigationGate.next()
        _uiState.value = _uiState.value.copy(error = null)
        coroutineScope.launch {
            if (!navigationGate.isCurrent(navigation)) return@launch
            when (val result = conversationRepository.get(conversationId)) {
                is ApiResult.Success -> if (navigationGate.isCurrent(navigation)) selectConversation(result.data)
                is ApiResult.Error -> if (navigationGate.isCurrent(navigation)) {
                    _uiState.value = _uiState.value.copy(error = result.error)
                }
            }
        }
    }

    /** Проектное обсуждение: get-or-create на сервере, права проверяются там же. */
    fun openProjectChat(projectId: Long) {
        if (released) return
        val navigation = navigationGate.next()
        val current = _uiState.value.selectedConversation
        if (current?.kind == ConversationKind.PROJECT && current.projectId == projectId && current.id > 0) return
        messageSearchGeneration++
        messageSearchJob?.cancel()
        realtimeJob?.cancel()
        realtimeJob = null
        historyJob?.cancel()
        historyJob = null
        _uiState.value = _uiState.value.copy(
            selectedConversation = null,
            messages = ConversationMessagesUiState(isLoading = true)
        )
        coroutineScope.launch {
            if (!navigationGate.isCurrent(navigation)) return@launch
            when (val result = conversationRepository.getOrCreateProjectChat(projectId)) {
                is ApiResult.Success -> if (navigationGate.isCurrent(navigation)) selectConversation(result.data)
                is ApiResult.Error -> if (navigationGate.isCurrent(navigation)) {
                    _uiState.value = _uiState.value.copy(
                        messages = ConversationMessagesUiState(isLoading = false, error = result.error)
                    )
                }
            }
        }
    }

    fun closeConversation() {
        navigationGate.next()
        messageSearchGeneration++
        messageSearchJob?.cancel()
        realtimeJob?.cancel()
        realtimeJob = null
        historyJob?.cancel()
        historyJob = null
        _uiState.value = _uiState.value.copy(
            selectedConversation = null,
            messages = ConversationMessagesUiState()
        )
    }

    fun backToList() = closeConversation()

    fun openCreateDialog() {
        _uiState.value = _uiState.value.copy(createError = null, createdConversationKey = null)
    }

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

    fun createChannel(title: String, memberIds: List<Long>) {
        if (title.isBlank()) {
            _uiState.value = _uiState.value.copy(
                createError = ApiError("VALIDATION_ERROR", "Укажите название канала")
            )
            return
        }
        createConversation(
            CreateConversationRequest(
                kind = ConversationKind.CHANNEL,
                title = title.trim(),
                memberIds = memberIds
            )
        )
    }

    private fun createConversation(request: CreateConversationRequest) {
        if (_uiState.value.isCreating) return
        _uiState.value = _uiState.value.copy(
            isCreating = true,
            createError = null,
            createdConversationKey = null
        )
        coroutineScope.launch {
            when (val result = conversationRepository.create(request)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        isCreating = false,
                        createdConversationKey = conversationKey(result.data.kind, result.data.id)
                    )
                    selectConversation(result.data)
                    loadAll()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    isCreating = false,
                    createError = result.error
                )
            }
        }
    }

    fun consumeCreateSuccess() {
        _uiState.value = _uiState.value.copy(createdConversationKey = null)
    }

    fun dismissCreateError() {
        _uiState.value = _uiState.value.copy(createError = null)
    }

    fun changeDialogState(
        item: ConversationListItemDto,
        pinned: Boolean? = null,
        muted: Boolean? = null,
        archived: Boolean? = null
    ) {
        val key = conversationKey(item.kind, item.id)
        if (key in _uiState.value.pendingDialogActions) return
        val client = conversationRepository as? ConversationApiClient
        if (client == null) {
            _uiState.value = _uiState.value.copy(
                error = ApiError("UNSUPPORTED", "Изменение состояния диалога не поддерживается")
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            pendingDialogActions = _uiState.value.pendingDialogActions + key,
            error = null
        )
        coroutineScope.launch {
            when (
                val result = client.changeDialogState(
                    item.kind,
                    item.id,
                    ConversationDialogStateRequest(pinned = pinned, muted = muted, archived = archived)
                )
            ) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        items = applyConversationDialogState(_uiState.value.items, result.data),
                        pendingDialogActions = _uiState.value.pendingDialogActions - key
                    ).withMergedEntries(currentUser)
                    loadAll()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    pendingDialogActions = _uiState.value.pendingDialogActions - key,
                    error = result.error
                )
            }
        }
    }

    fun updateDraft(value: String) {
        val selected = _uiState.value.selectedConversation ?: return
        val key = selected.localKey()
        localState.setDraft(key, value)
        val updatedDrafts = if (value.isBlank()) _uiState.value.drafts - key else _uiState.value.drafts + (key to value)
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(draft = value),
            drafts = updatedDrafts
        )
        if (value.isNotBlank() && selected.id > 0) {
            coroutineScope.launch { conversationRepository.notifyTyping(selected.id) }
        }
    }

    fun pickFile() {
        val selected = _uiState.value.selectedConversation ?: return
        val key = selected.localKey()
        pickAttachmentFile(
            onPicked = { addPendingFile(selected, key, it) },
            onError = { showAttachmentError(selected, it) },
            existingFileCount = localState.files(key).size,
            existingTotalBytes = localState.files(key).sumOf { it.bytes.size.toLong() }
        )
    }

    fun dropFiles(event: DragAndDropEvent): Boolean {
        val selected = _uiState.value.selectedConversation ?: return false
        if (!selected.canWrite || _uiState.value.messages.isSending) return false
        val key = selected.localKey()
        return event.readAttachmentFiles(
            onPicked = { addPendingFile(selected, key, it) },
            onError = { showAttachmentError(selected, it) },
            existingFileCount = localState.files(key).size,
            existingTotalBytes = localState.files(key).sumOf { it.bytes.size.toLong() }
        )
    }

    private fun addPendingFile(selected: ConversationDto, key: String, file: AttachmentFileData) {
        val currentFiles = localState.files(key)
        val validationError = validateAttachmentSelection(
            currentFiles.map { it.bytes.size.toLong() } + file.bytes.size.toLong()
        )
        if (validationError == null) {
            localState.setFiles(key, currentFiles + file)
        }
        if (isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) {
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.copy(
                    pendingFiles = localState.files(key),
                    attachmentPickerError = validationError
                )
            )
        }
    }

    private fun showAttachmentError(selected: ConversationDto, error: String) {
        if (isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) {
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.copy(attachmentPickerError = error)
            )
        }
    }

    fun clearPendingFile() {
        val selected = _uiState.value.selectedConversation ?: return
        localState.setFiles(selected.localKey(), emptyList())
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(pendingFiles = emptyList())
        )
    }

    fun removePendingFile(file: AttachmentFileData) {
        val selected = _uiState.value.selectedConversation ?: return
        val key = selected.localKey()
        localState.setFiles(key, localState.files(key).filterNot { it === file })
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(pendingFiles = localState.files(key))
        )
    }

    fun setReplyToMessageId(messageId: Long?) {
        val selected = _uiState.value.selectedConversation ?: return
        if (messageId != null && _uiState.value.messages.editingMessageId != null) {
            cancelEditingMessage()
        }
        localState.setReplyTo(selected.localKey(), messageId)
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(replyToMessageId = messageId)
        )
    }

    fun startEditingMessage(message: ConversationMessageDto) {
        val selected = _uiState.value.selectedConversation ?: return
        if (!selected.canWrite || message.author?.userId != currentUser.id || message.revokedAt != null) return
        val key = selected.localKey()
        val current = _uiState.value.messages
        val draftBackup = if (current.editingMessageId == null) current.draft else current.draftBeforeEditing
        localState.setDraft(key, message.content)
        localState.setReplyTo(key, null)
        _uiState.value = _uiState.value.copy(
            messages = current.copy(
                draft = message.content,
                replyToMessageId = null,
                editingMessageId = message.id,
                draftBeforeEditing = draftBackup,
                sendError = null
            )
        )
    }

    fun cancelEditingMessage() {
        val selected = _uiState.value.selectedConversation ?: return
        val current = _uiState.value.messages
        if (current.editingMessageId == null) return
        val restoredDraft = current.draftBeforeEditing.orEmpty()
        localState.setDraft(selected.localKey(), restoredDraft)
        _uiState.value = _uiState.value.copy(
            messages = current.copy(
                draft = restoredDraft,
                editingMessageId = null,
                draftBeforeEditing = null,
                sendError = null
            )
        )
    }

    fun updateScrollPosition(position: Int) {
        val selected = _uiState.value.selectedConversation ?: return
        localState.setScrollPosition(selected.localKey(), position)
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(scrollPosition = localState.scrollPosition(selected.localKey()))
        )
    }

    fun updateVisibleRange(lastVisibleMessageId: Long?, viewingLatest: Boolean) {
        val selected = _uiState.value.selectedConversation ?: return
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(viewingLatest = viewingLatest)
        )
        if (viewingLatest) {
            val latestLoadedId = _uiState.value.messages.messages.lastOrNull()?.id
            if (lastVisibleMessageId != null && lastVisibleMessageId == latestLoadedId) {
                markConversationRead(selected, lastVisibleMessageId)
            }
        }
    }

    private fun markConversationRead(selected: ConversationDto, messageId: Long) {
        val key = selected.localKey()
        if (messageId <= (lastReadRequests[key] ?: 0L)) return
        lastReadRequests[key] = messageId
        coroutineScope.launch {
            when (val result = conversationRepository.markRead(selected.id, messageId)) {
                is ApiResult.Success -> {
                    // В списке задачный чат хранится с ID задачи, а выбранный разговор с отрицательным ID.
                    val listId = if (selected.kind == ConversationKind.TASK) -selected.id else selected.id
                    val items = _uiState.value.items.map {
                        if (it.kind == selected.kind && it.id == listId) {
                            it.copy(unreadCount = 0, mentionCount = 0)
                        } else {
                            it
                        }
                    }
                    _uiState.value = _uiState.value.copy(items = items).withMergedEntries(currentUser)
                    if (isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) {
                        val cursors = _uiState.value.messages.readCursors +
                            (currentUser.id to maxOf(messageId, _uiState.value.messages.readCursors[currentUser.id] ?: 0L))
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages.copy(readCursors = cursors)
                        )
                    }
                }
                is ApiResult.Error -> if (lastReadRequests[key] == messageId) {
                    lastReadRequests -= key
                    if (isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages.copy(error = result.error)
                        )
                    }
                }
            }
        }
    }

    fun updateMessageSearchQuery(query: String) {
        val selected = _uiState.value.selectedConversation ?: return
        val generation = ++messageSearchGeneration
        messageSearchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(
                searchQuery = query,
                searchLoading = query.isNotBlank(),
                searchError = null
            )
        )
        if (query.isBlank()) {
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.copy(searchResults = emptyList(), searchLoading = false)
            )
            return
        }
        messageSearchJob = coroutineScope.launch {
            when (val result = conversationRepository.searchMessages(selected.id, query.trim())) {
                is ApiResult.Success -> if (
                    generation == messageSearchGeneration &&
                    isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind) &&
                    _uiState.value.messages.searchQuery == query
                ) {
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages.copy(
                            searchResults = result.data.messages,
                            searchLoading = false
                        )
                    )
                }
                is ApiResult.Error -> if (
                    generation == messageSearchGeneration &&
                    isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind) &&
                    _uiState.value.messages.searchQuery == query
                ) {
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages.copy(searchLoading = false, searchError = result.error)
                    )
                }
            }
        }
    }

    fun closeMessageSearch() {
        messageSearchGeneration++
        messageSearchJob?.cancel()
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(
                searchQuery = "",
                searchResults = emptyList(),
                searchLoading = false,
                searchError = null
            )
        )
    }

    fun scrollToSearchResult(messageId: Long) {
        val selected = _uiState.value.selectedConversation ?: return
        if (_uiState.value.messages.messages.any { it.id == messageId }) {
            showSearchTarget(messageId)
            return
        }
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(searchError = null)
        )
        coroutineScope.launch { loadSearchTarget(selected, messageId) }
    }

    private fun showSearchTarget(messageId: Long) {
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(
                scrollToMessageId = messageId,
                highlightedMessageId = messageId,
                searchError = null
            )
        )
    }

    private suspend fun loadSearchTarget(selected: ConversationDto, messageId: Long) {
        val loaded = _uiState.value.messages.messages
        var before = when {
            loaded.isEmpty() -> messageId + 1
            messageId < loaded.first().id -> loaded.first().id
            messageId > loaded.last().id -> null
            else -> messageId + 1
        }
        var after = if (before == null && loaded.isNotEmpty()) loaded.last().id else null
        repeat(MAX_SEARCH_TARGET_PAGES) {
            val result = conversationRepository.listMessages(
                selected.id,
                after = after,
                before = before,
                limit = SEARCH_HISTORY_PAGE_SIZE
            )
            when (result) {
                is ApiResult.Error -> {
                    if (isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages.copy(searchError = result.error)
                        )
                    }
                    return
                }
                is ApiResult.Success -> {
                    if (!isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) return
                    val merged = (_uiState.value.messages.messages + result.data.messages)
                        .associateBy { it.id }.values.sortedBy { it.id }
                    realtimeMerger.replaceHistory(merged)
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages.copy(messages = merged)
                    )
                    if (merged.any { it.id == messageId }) {
                        showSearchTarget(messageId)
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
        if (isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) {
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.copy(
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
            messages = _uiState.value.messages.copy(scrollToMessageId = null)
        )
    }

    fun editMessage(message: ConversationMessageDto, content: String) {
        val selected = _uiState.value.selectedConversation ?: return
        if (!selected.canWrite || message.author?.userId != currentUser.id || message.revokedAt != null) return
        if (content.isBlank()) {
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.copy(
                    sendError = ApiError("VALIDATION_ERROR", "Текст сообщения не может быть пустым")
                )
            )
            return
        }
        val client = conversationRepository as? ConversationApiClient ?: return
        val key = selected.localKey()
        val restoredDraft = _uiState.value.messages.draftBeforeEditing.orEmpty()
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(isSending = true, sendError = null)
        )
        coroutineScope.launch {
            when (val result = client.editMessage(selected.id, message.id, EditMessageRequest(content.trim(), message.version))) {
                is ApiResult.Success -> {
                    applyRealtimeEvent(
                        selected.id,
                        selected.kind,
                        ConversationRealtimeEvent.Message(result.data, ConversationMessageEventType.EDITED)
                    )
                    if (localState.draft(key) == content) localState.setDraft(key, restoredDraft)
                    if (
                        isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind) &&
                        _uiState.value.messages.editingMessageId == message.id
                    ) {
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages.copy(
                                draft = restoredDraft,
                                editingMessageId = null,
                                draftBeforeEditing = null,
                                isSending = false,
                                sendError = null
                            )
                        )
                    }
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages.copy(isSending = false, sendError = result.error)
                )
            }
        }
    }

    fun revokeMessage(message: ConversationMessageDto) {
        val selected = _uiState.value.selectedConversation ?: return
        if (!selected.canWrite || message.author?.userId != currentUser.id || message.revokedAt != null) return
        val client = conversationRepository as? ConversationApiClient ?: return
        coroutineScope.launch {
            when (val result = client.revokeMessage(selected.id, message.id, message.version)) {
                is ApiResult.Success -> applyRealtimeEvent(
                    selected.id,
                    selected.kind,
                    ConversationRealtimeEvent.Message(result.data, ConversationMessageEventType.REVOKED)
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages.copy(error = result.error)
                )
            }
        }
    }

    fun forwardMessage(message: ConversationMessageDto, target: ConversationListItemDto) {
        val selected = _uiState.value.selectedConversation ?: return
        if (!selected.canWrite || message.revokedAt != null || !target.canWrite) return
        val client = conversationRepository as? ConversationApiClient ?: return
        coroutineScope.launch {
            when (
                val result = client.forwardMessage(
                    selected.id,
                    message.id,
                    ForwardMessageRequest(target.kind, target.id, newIdempotencyKey())
                )
            ) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages.copy(actionError = null)
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages.copy(actionError = result.error)
                )
            }
        }
    }

    fun toggleReaction(messageId: Long, emoji: String) {
        val selected = _uiState.value.selectedConversation ?: return
        if (!selected.canWrite) return
        val client = conversationRepository as? ConversationApiClient ?: return
        coroutineScope.launch {
            when (val result = client.toggleReaction(selected.id, messageId, emoji, selected.kind)) {
                is ApiResult.Success -> applyRealtimeEvent(
                    selected.id,
                    selected.kind,
                    ConversationRealtimeEvent.Reaction(messageId, result.data)
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages.copy(error = result.error)
                )
            }
        }
    }

    fun loadImagePreview(attachment: AttachmentDto) {
        val current = _uiState.value.messages
        if (attachment.id in current.imagePreviews || attachment.id in current.loadingImagePreviews) return
        _uiState.value = _uiState.value.copy(
            messages = current.copy(loadingImagePreviews = current.loadingImagePreviews + attachment.id)
        )
        coroutineScope.launch {
            when (val result = attachmentRepository.download(attachment.id)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages.copy(
                        imagePreviews = _uiState.value.messages.imagePreviews + (attachment.id to result.data.bytes),
                        loadingImagePreviews = _uiState.value.messages.loadingImagePreviews - attachment.id
                    )
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages.copy(
                        loadingImagePreviews = _uiState.value.messages.loadingImagePreviews - attachment.id
                    )
                )
            }
        }
    }

    fun sendMessage() {
        val selected = _uiState.value.selectedConversation ?: return
        if (!selected.canWrite) return
        val key = selected.localKey()
        val content = localState.draft(key)
        val editingMessageId = _uiState.value.messages.editingMessageId
        if (editingMessageId != null) {
            val editingMessage = _uiState.value.messages.messages.find { it.id == editingMessageId }
            if (editingMessage == null) {
                cancelEditingMessage()
            } else {
                editMessage(editingMessage, content)
            }
            return
        }
        val files = localState.files(key)
        if (content.isBlank() && files.isEmpty()) {
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.copy(
                    sendError = ApiError("VALIDATION_ERROR", "Введите текст сообщения")
                )
            )
            return
        }
        val signature = localState.signature(key)
        val idempotencyKey = localState.commandKey(key, signature, ::newIdempotencyKey)
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(isSending = true, sendError = null)
        )
        coroutineScope.launch {
            when (
                val result = conversationRepository.sendMessage(
                    selected.id,
                    SendChatMessageRequest(content.trim(), idempotencyKey, signature.replyToMessageId),
                    files
                )
            ) {
                is ApiResult.Success -> {
                    localState.clearComposerAfterSuccess(key, signature)
                    applyListPreview(selected, result.data)
                    if (isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) {
                        realtimeMerger.apply(ConversationRealtimeEvent.Message(result.data))
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages.copy(
                                messages = realtimeMerger.messages,
                                draft = localState.draft(key),
                                pendingFiles = localState.files(key),
                                replyToMessageId = localState.replyTo(key),
                                isSending = false,
                                sendError = null
                            )
                        )
                    }
                }
                is ApiResult.Error -> {
                    if (isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) {
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
        coroutineScope.launch {
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
        coroutineScope.launch {
            try {
                catchUp(selected.id, selected.kind, reportErrors = manual)
            } catch (e: ConversationAccessRevokedException) {
                if (isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) {
                    revokeConversationAccess(selected.id, selected.kind)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual && isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) {
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages.copy(
                            error = ApiError("HISTORY_REFRESH_FAILED", "Не удалось обновить историю")
                        )
                    )
                }
            }
        }
    }

    fun loadMoreNewer() {
        val selected = _uiState.value.selectedConversation ?: return
        val after = _uiState.value.messages.messages.lastOrNull()?.id ?: return
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(isLoadingMore = true)
        )
        coroutineScope.launch {
            when (val result = conversationRepository.listMessages(selected.id, after = after)) {
                is ApiResult.Success -> {
                    if (isSameConversation(_uiState.value.selectedConversation, selected.id, selected.kind)) {
                        result.data.messages.forEach {
                            realtimeMerger.apply(ConversationRealtimeEvent.Message(it))
                        }
                        _uiState.value = _uiState.value.copy(
                            messages = _uiState.value.messages.copy(
                                messages = realtimeMerger.messages,
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

    private suspend fun loadMessagesInitial(conversationId: Long, kind: ConversationKind, navigation: Long) {
        var collected = emptyList<ConversationMessageDto>()
        var after: Long? = null
        var hasMore = false
        var iterations = 0
        while (iterations < MAX_MESSAGE_PAGES && navigationGate.isCurrent(navigation)) {
            when (val result = conversationRepository.listMessages(conversationId, after = after)) {
                is ApiResult.Success -> {
                    if (!navigationGate.isCurrent(navigation)) return
                    collected = collected + result.data.messages
                    after = result.data.messages.lastOrNull()?.id
                    hasMore = result.data.hasMore
                    if (!result.data.hasMore || result.data.messages.isEmpty()) break
                }
                is ApiResult.Error -> {
                    if (navigationGate.isCurrent(navigation) &&
                        isSameConversation(_uiState.value.selectedConversation, conversationId, kind)
                    ) {
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
        if (navigationGate.isCurrent(navigation) &&
            isSameConversation(_uiState.value.selectedConversation, conversationId, kind)
        ) {
            realtimeMerger.replaceHistory(collected)
            val serverId = if (kind == ConversationKind.TASK) -conversationId else conversationId
            val hasUnread = _uiState.value.items.any {
                it.kind == kind && it.id == serverId && it.unreadCount > 0
            }
            _uiState.value = _uiState.value.copy(
                messages = _uiState.value.messages.copy(
                    messages = realtimeMerger.messages,
                    isLoading = false,
                    hasMoreNewer = hasMore,
                    firstUnreadMessageId = firstUnreadMessageId(
                        realtimeMerger.messages.map { it.id to it.author?.userId },
                        currentUser.id,
                        hasUnread
                    ),
                    error = null
                )
            )
            val latest = collected.lastOrNull()
            if (!hasMore && latest != null && _uiState.value.messages.viewingLatest) {
                _uiState.value.selectedConversation?.let { markConversationRead(it, latest.id) }
            }
        }
    }

    private fun startRealtime(conversationId: Long, kind: ConversationKind, navigation: Long) {
        realtimeJob?.cancel()
        realtimeJob = coroutineScope.launch {
            var attempt = 0
            var initialSync = true
            val key = conversationKey(kind, conversationId)
            val cursor = reconnectCursors.getOrPut(key) { ConversationReconnectCursor() }
            while (
                isActive &&
                navigationGate.isCurrent(navigation) &&
                isSameConversation(_uiState.value.selectedConversation, conversationId, kind)
            ) {
                var disconnect = ConversationDisconnect.CLOSED
                try {
                    if (!initialSync) {
                        catchUp(conversationId, kind, reportErrors = false, navigation = navigation)
                    }
                    initialSync = false
                    if (!navigationGate.isCurrent(navigation)) break
                    cursor.recordHistory(_uiState.value.messages.messages)
                    val parameters = cursor.parameters()
                    conversationRepository.observeEvents(
                        conversationId,
                        parameters["after"]?.toLong(),
                        parameters["afterEvent"]?.toLong()
                    ).collect { event ->
                        if (!navigationGate.isCurrent(navigation)) return@collect
                        cursor.record(event)
                        applyRealtimeEvent(conversationId, kind, event)
                    }
                } catch (e: ConversationAccessRevokedException) {
                    if (
                        navigationGate.isCurrent(navigation) &&
                        isSameConversation(_uiState.value.selectedConversation, conversationId, kind)
                    ) {
                        revokeConversationAccess(conversationId, kind)
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ConversationOverflowException) {
                    disconnect = ConversationDisconnect.OVERFLOW
                } catch (e: Exception) {
                    disconnect = ConversationDisconnect.NETWORK
                }
                if (!navigationGate.isCurrent(navigation) || !cursor.shouldReconnect(disconnect)) break
                attempt++
                reconnectDelay(reconnectDelayMs(attempt))
            }
        }
    }

    private fun revokeConversationAccess(conversationId: Long, kind: ConversationKind) {
        val key = conversationKey(kind, conversationId)
        navigationGate.next()
        localState.clearSensitive(key)
        reconnectCursors -= key
        lastReadRequests -= key
        realtimeJob?.cancel()
        realtimeJob = null
        historyJob?.cancel()
        historyJob = null
        val serverId = if (kind == ConversationKind.TASK) -conversationId else conversationId
        _uiState.value = _uiState.value.copy(
            items = _uiState.value.items.filterNot { it.kind == kind && it.id == serverId },
            conversations = _uiState.value.conversations.filterNot {
                it.kind == kind && it.id == conversationId
            },
            selectedConversation = null,
            messages = ConversationMessagesUiState(
                error = ApiError("CONVERSATION_ACCESS_ENDED", "Доступ к обсуждению завершён")
            )
        ).withMergedEntries(currentUser)
    }

    private suspend fun catchUp(
        conversationId: Long,
        kind: ConversationKind,
        reportErrors: Boolean,
        navigation: Long? = null
    ) {
        var after = _uiState.value.messages.messages.lastOrNull()?.id
        var iterations = 0
        while (iterations < MAX_MESSAGE_PAGES) {
            if (navigation != null && !navigationGate.isCurrent(navigation)) return
            when (val result = conversationRepository.listMessages(conversationId, after = after)) {
                is ApiResult.Success -> {
                    if (navigation != null && !navigationGate.isCurrent(navigation)) return
                    result.data.messages.forEach { appendMessages(conversationId, kind, listOf(it)) }
                    if (!result.data.hasMore || result.data.messages.isEmpty()) break
                    after = result.data.messages.last().id
                }
                is ApiResult.Error -> {
                    if (navigation != null && !navigationGate.isCurrent(navigation)) return
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
        incoming.forEach { applyRealtimeEvent(conversationId, kind, ConversationRealtimeEvent.Message(it)) }
    }

    private fun applyRealtimeEvent(
        conversationId: Long,
        kind: ConversationKind,
        event: ConversationRealtimeEvent
    ) {
        if (!isSameConversation(_uiState.value.selectedConversation, conversationId, kind)) return
        val applied = realtimeMerger.apply(event)
        reconnectCursors.getOrPut(conversationKey(kind, conversationId)) {
            ConversationReconnectCursor()
        }.record(event)
        if (!applied) return
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages.copy(
                messages = realtimeMerger.messages,
                readCursors = realtimeMerger.readCursors,
                isPinned = realtimeMerger.isPinned
            )
        )
        when (event) {
            is ConversationRealtimeEvent.Message -> {
                applyListPreview(kind, conversationId, event.message)
                if (
                    event.type == ConversationMessageEventType.MESSAGE &&
                    _uiState.value.messages.viewingLatest &&
                    _uiState.value.messages.messages.lastOrNull()?.id == event.message.id
                ) {
                    _uiState.value.selectedConversation?.let { markConversationRead(it, event.message.id) }
                }
            }
            is ConversationRealtimeEvent.Pin -> {
                val serverId = if (kind == ConversationKind.TASK) -conversationId else conversationId
                val items = _uiState.value.items.map {
                    if (it.kind == kind && it.id == serverId) it.copy(isPinned = event.isPinned) else it
                }
                _uiState.value = _uiState.value.copy(items = items).withMergedEntries(currentUser)
            }
            is ConversationRealtimeEvent.Typing -> {
                if (event.userId != currentUser.id) {
                    val name = event.fullName?.takeIf { it.isNotBlank() } ?: return
                    if (name !in _uiState.value.typingUsers) {
                        _uiState.value = _uiState.value.copy(typingUsers = _uiState.value.typingUsers + name)
                    }
                    coroutineScope.launch {
                        delay(4_000)
                        _uiState.value = _uiState.value.copy(
                            typingUsers = _uiState.value.typingUsers.filterNot { it == name }
                        )
                    }
                }
            }
            else -> Unit
        }
    }

    fun openSavedMessages() {
        if (released) return
        coroutineScope.launch {
            when (val res = conversationRepository.getOrCreateSavedMessages()) {
                is ApiResult.Success -> {
                    selectConversation(res.data)
                    loadAll()
                }
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(error = res.error)
            }
        }
    }

    fun refreshPresence() {
        if (released) return
        coroutineScope.launch {
            when (val res = conversationRepository.listPresence()) {
                is ApiResult.Success -> {
                    val map = res.data.associateBy { it.userId }
                    val mine = map[currentUser.id]?.status ?: _uiState.value.myPresence
                    _uiState.value = _uiState.value.copy(presenceByUser = map, myPresence = mine)
                }
                is ApiResult.Error -> Unit
            }
        }
    }

    fun setMyPresence(status: org.example.project.dto.UserPresenceStatus) {
        if (released) return
        _uiState.value = _uiState.value.copy(myPresence = status)
        coroutineScope.launch {
            when (val res = conversationRepository.updatePresence(status)) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        presenceByUser = _uiState.value.presenceByUser + (currentUser.id to res.data),
                        myPresence = res.data.status
                    )
                }
                is ApiResult.Error -> Unit
            }
        }
    }

    fun pinMessage(messageId: Long) {
        val conv = _uiState.value.selectedConversation ?: return
        if (conv.id <= 0) return
        coroutineScope.launch {
            when (val res = conversationRepository.pinMessage(conv.id, messageId)) {
                is ApiResult.Success -> {
                    if (_uiState.value.selectedConversation?.id == conv.id) {
                        _uiState.value = _uiState.value.copy(
                            selectedConversation = conv.copy(pinnedMessages = res.data)
                        )
                    }
                }
                is ApiResult.Error -> Unit
            }
        }
    }

    fun unpinMessage(messageId: Long) {
        val conv = _uiState.value.selectedConversation ?: return
        if (conv.id <= 0) return
        coroutineScope.launch {
            when (val res = conversationRepository.unpinMessage(conv.id, messageId)) {
                is ApiResult.Success -> {
                    if (_uiState.value.selectedConversation?.id == conv.id) {
                        _uiState.value = _uiState.value.copy(
                            selectedConversation = conv.copy(pinnedMessages = res.data)
                        )
                    }
                }
                is ApiResult.Error -> Unit
            }
        }
    }

    fun createPoll(question: String, options: List<String>, isAnonymous: Boolean, isMultiple: Boolean) {
        val conv = _uiState.value.selectedConversation ?: return
        if (conv.id <= 0) return
        coroutineScope.launch {
            val res = conversationRepository.createPoll(
                conv.id,
                org.example.project.dto.CreatePollRequest(question, options, isAnonymous, isMultiple)
            )
            if (res is ApiResult.Success) {
                refreshMessages()
            }
        }
    }

    fun votePoll(pollId: Long, optionIds: List<Long>) {
        val conv = _uiState.value.selectedConversation ?: return
        if (conv.id <= 0) return
        coroutineScope.launch {
            val res = conversationRepository.votePoll(
                conv.id,
                pollId,
                org.example.project.dto.VotePollRequest(optionIds)
            )
            if (res is ApiResult.Success) {
                val updated = _uiState.value.messages.messages.map {
                    if (it.id == res.data.id) res.data else it
                }
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages.copy(messages = updated)
                )
            }
        }
    }

    fun openThread(rootMessage: ConversationMessageDto) {
        val conv = _uiState.value.selectedConversation ?: return
        if (conv.id <= 0) return
        _uiState.value = _uiState.value.copy(
            activeThreadRoot = rootMessage,
            threadMessages = emptyList(),
            threadLoading = true
        )
        coroutineScope.launch {
            when (val res = conversationRepository.listThread(conv.id, rootMessage.id)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    threadMessages = res.data,
                    threadLoading = false
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(threadLoading = false)
            }
        }
    }

    fun closeThread() {
        _uiState.value = _uiState.value.copy(
            activeThreadRoot = null,
            threadMessages = emptyList(),
            threadLoading = false
        )
    }

    fun sendThreadReply(content: String) {
        val conv = _uiState.value.selectedConversation ?: return
        val root = _uiState.value.activeThreadRoot ?: return
        if (conv.id <= 0 || content.isBlank()) return
        coroutineScope.launch {
            val res = conversationRepository.sendThreadReply(
                conv.id,
                root.id,
                SendChatMessageRequest(content = content.trim(), idempotencyKey = newIdempotencyKey())
            )
            if (res is ApiResult.Success) {
                val updatedMessages = _uiState.value.messages.messages.map { msg ->
                    if (msg.id == root.id) msg.copy(threadReplyCount = msg.threadReplyCount + 1) else msg
                }
                _uiState.value = _uiState.value.copy(
                    threadMessages = _uiState.value.threadMessages + res.data,
                    messages = _uiState.value.messages.copy(messages = updatedMessages)
                )
            }
        }
    }

    fun scheduleMessage(content: String, scheduledAt: String?, isSilent: Boolean) {
        val conv = _uiState.value.selectedConversation ?: return
        if (conv.id <= 0 || content.isBlank()) return
        coroutineScope.launch {
            val res = conversationRepository.scheduleOrSendSilent(
                conv.id,
                org.example.project.dto.ScheduledMessageRequest(
                    content = content.trim(),
                    scheduledAt = scheduledAt,
                    isSilent = isSilent,
                    replyToMessageId = _uiState.value.messages.replyToMessageId,
                    idempotencyKey = newIdempotencyKey()
                )
            )
            if (res is ApiResult.Success) {
                updateDraft("")
                setReplyToMessageId(null)
                if (scheduledAt.isNullOrBlank()) {
                    refreshMessages()
                } else {
                    loadScheduled()
                }
            }
        }
    }

    fun loadScheduled() {
        val conv = _uiState.value.selectedConversation ?: return
        if (conv.id <= 0) return
        coroutineScope.launch {
            when (val res = conversationRepository.listScheduled(conv.id)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(scheduledMessages = res.data)
                is ApiResult.Error -> Unit
            }
        }
    }

    fun summarizeActiveConversation() {
        val conv = _uiState.value.selectedConversation ?: return
        if (conv.id <= 0) return
        _uiState.value = _uiState.value.copy(aiSummaryLoading = true, aiSummary = null)
        coroutineScope.launch {
            when (val res = conversationRepository.summarizeConversation(conv.id)) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(
                    aiSummary = res.data,
                    aiSummaryLoading = false
                )
                is ApiResult.Error -> _uiState.value = _uiState.value.copy(aiSummaryLoading = false)
            }
        }
    }

    fun clearAiSummary() {
        _uiState.value = _uiState.value.copy(aiSummary = null, aiSummaryLoading = false)
    }

    fun updateGroupInfo(title: String?, description: String?) {
        val conv = _uiState.value.selectedConversation ?: return
        if (conv.id <= 0) return
        coroutineScope.launch {
            when (val res = conversationRepository.updateConversation(
                conv.id,
                org.example.project.dto.UpdateConversationRequest(title = title, description = description)
            )) {
                is ApiResult.Success -> {
                    _uiState.value = _uiState.value.copy(selectedConversation = res.data)
                    loadAll()
                }
                is ApiResult.Error -> Unit
            }
        }
    }

    fun updateGroupMembers(addIds: List<Long>, removeIds: List<Long>) {
        val conv = _uiState.value.selectedConversation ?: return
        if (conv.id <= 0) return
        coroutineScope.launch {
            when (val res = conversationRepository.updateMembers(
                conv.id,
                org.example.project.dto.UpdateConversationMembersRequest(addIds, removeIds)
            )) {
                is ApiResult.Success -> _uiState.value = _uiState.value.copy(selectedConversation = res.data)
                is ApiResult.Error -> Unit
            }
        }
    }

    fun leaveActiveGroup() {
        val conv = _uiState.value.selectedConversation ?: return
        if (conv.id <= 0) return
        coroutineScope.launch {
            val res = conversationRepository.leaveConversation(conv.id)
            if (res is ApiResult.Success) {
                backToList()
                loadAll()
            }
        }
    }

    private fun applyListPreview(selected: ConversationDto, message: ConversationMessageDto) {
        applyListPreview(selected.kind, selected.id, message)
    }

    private fun applyListPreview(kind: ConversationKind, conversationId: Long, message: ConversationMessageDto) {
        val serverId = if (kind == ConversationKind.TASK) -conversationId else conversationId
        val items = updateWorkspacePreview(
            _uiState.value.items,
            kind,
            serverId,
            WorkspacePreview(
                text = message.content,
                revoked = message.revokedAt != null,
                author = message.author,
                createdAt = message.createdAt
            )
        )
        _uiState.value = _uiState.value.copy(items = items).withMergedEntries(currentUser)
    }

    private fun restoredMessages(
        conversation: ConversationDto,
        isLoading: Boolean = false
    ): ConversationMessagesUiState {
        val key = conversation.localKey()
        return ConversationMessagesUiState(
            isLoading = isLoading,
            draft = localState.draft(key),
            pendingFiles = localState.files(key),
            replyToMessageId = localState.replyTo(key),
            scrollPosition = localState.scrollPosition(key),
            viewingLatest = localState.scrollPosition(key) == 0
        )
    }

    private fun reconnectDelayMs(attempt: Int): Long {
        val shift = (attempt - 1).coerceAtMost(5)
        return (RECONNECT_BASE_MS shl shift).coerceAtMost(RECONNECT_MAX_MS)
    }

    fun close() {
        if (released) return
        onCleared()
    }

    override fun onCleared() {
        if (!released) {
            released = true
            listRequestVersion++
            navigationGate.next()
            messageSearchGeneration++
            messageSearchJob?.cancel()
            realtimeJob?.cancel()
            realtimeJob = null
            historyJob?.cancel()
            historyJob = null
            queryJob?.cancel()
            queryJob = null
            localState.clear()
            reconnectCursors.clear()
            lastReadRequests.clear()
            realtimeMerger = ConversationRealtimeMerger()
            _uiState.value = ConversationsUiState()
            coroutineScope.cancel()
        }
        super.onCleared()
    }

    private fun newIdempotencyKey(): String =
        "ui-conv-${Random.nextLong().toString(16)}-${Random.nextLong().toString(16)}"

    private companion object {
        const val RECONNECT_BASE_MS = 1_000L
        const val RECONNECT_MAX_MS = 30_000L
        const val MAX_MESSAGE_PAGES = 10
        const val MAX_SEARCH_TARGET_PAGES = 10
        const val SEARCH_HISTORY_PAGE_SIZE = 200
        const val WORKSPACE_PAGE_SIZE = 50
        const val SEARCH_DEBOUNCE_MS = 250L
    }
}

private fun ConversationDto.localKey(): String = conversationKey(kind, id)

internal fun ConversationsUiState.withMergedEntries(currentUser: UserDto): ConversationsUiState {
    val sortedItems = items.sortedWith(
        compareByDescending<ConversationListItemDto> { it.isPinned }
            .thenByDescending { it.unreadCount > 0 }
            .thenByDescending { !it.lastMessageAt.isNullOrBlank() }
            .thenByDescending { it.lastMessageAt ?: "" }
    )
    val serverConversations = sortedItems.map { it.toConversationDto() }
    return copy(items = sortedItems, conversations = serverConversations, entries = serverConversations)
}

data class ConversationsUiState(
    val conversations: List<ConversationDto> = emptyList(),
    val items: List<ConversationListItemDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
    val query: String = "",
    val kindFilter: ConversationKind? = null,
    val listFilter: ConversationListFilter = ConversationListFilter.ALL,
    val isLoadingMore: Boolean = false,
    val tasks: List<TaskDto> = emptyList(),
    val projects: List<ProjectDto> = emptyList(),
    val entries: List<ConversationDto> = emptyList(),
    val isLoading: Boolean = false,
    val error: ApiError? = null,
    val selectedConversation: ConversationDto? = null,
    val messages: ConversationMessagesUiState = ConversationMessagesUiState(),
    val userOptions: List<UserOptionDto> = emptyList(),
    val isLoadingUserOptions: Boolean = false,
    val userOptionsError: ApiError? = null,
    val isCreating: Boolean = false,
    val createdConversationKey: String? = null,
    val pendingDialogActions: Set<String> = emptySet(),
    val createError: ApiError? = null,
    val drafts: Map<String, String> = emptyMap(),
    val presenceByUser: Map<Long, org.example.project.dto.UserPresenceDto> = emptyMap(),
    val myPresence: org.example.project.dto.UserPresenceStatus = org.example.project.dto.UserPresenceStatus.ONLINE,
    val typingUsers: List<String> = emptyList(),
    val activeThreadRoot: ConversationMessageDto? = null,
    val threadMessages: List<ConversationMessageDto> = emptyList(),
    val threadLoading: Boolean = false,
    val scheduledMessages: List<ConversationMessageDto> = emptyList(),
    val aiSummary: org.example.project.dto.ConversationAiSummaryDto? = null,
    val aiSummaryLoading: Boolean = false
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
    val pendingFiles: List<AttachmentFileData> = emptyList(),
    val replyToMessageId: Long? = null,
    val editingMessageId: Long? = null,
    val draftBeforeEditing: String? = null,
    val scrollPosition: Int = 0,
    val readCursors: Map<Long, Long> = emptyMap(),
    val isPinned: Boolean = false,
    val downloadingAttachmentId: Long? = null,
    val downloadError: ApiError? = null,
    val actionError: ApiError? = null,
    val attachmentPickerError: String? = null,
    val searchQuery: String = "",
    val searchResults: List<ConversationMessageDto> = emptyList(),
    val searchLoading: Boolean = false,
    val searchError: ApiError? = null,
    val scrollToMessageId: Long? = null,
    val highlightedMessageId: Long? = null,
    val firstUnreadMessageId: Long? = null,
    val viewingLatest: Boolean = true,
    val imagePreviews: Map<Long, ByteArray> = emptyMap(),
    val loadingImagePreviews: Set<Long> = emptySet()
) {
    val pendingFile: AttachmentFileData? get() = pendingFiles.firstOrNull()
}
