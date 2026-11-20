package org.example.project.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.example.project.data.chatMemberIds
import org.example.project.dto.AttachmentDto
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ChatMessageType
import org.example.project.dto.ConversationKind
import org.example.project.dto.ConversationListItemDto
import org.example.project.dto.TaskChatListFilter
import org.example.project.dto.TaskChatListItemDto
import org.example.project.dto.TaskDto
import org.example.project.dto.UserDto
import org.example.project.ui.conversations.AttachmentRow
import org.example.project.ui.conversations.ChatReactionRow
import org.example.project.ui.conversations.DecodedImagePreview
import org.example.project.ui.conversations.PendingAttachmentRow
import org.example.project.ui.conversations.formattedChatText
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Avatar
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.PillBadge

/**
 * Трёхколоночный Task Chat (D-51): слева список чатов задач (280 dp),
 * по центру сообщения, справа сворачиваемая ERP-панель (320 dp).
 * <1440 dp правая панель свёрнута по умолчанию; <1100 dp снаружи действует
 * вкладочный режим M.5 (см. taskWorkspaceLayout).
 */
@Composable
fun TaskChatWorkspaceScreen(
    task: TaskDto,
    currentUser: UserDto,
    state: TasksUiState,
    viewModel: TaskViewModel,
    showChatList: Boolean = true,
    unifiedForwardTargets: List<ConversationListItemDto> = emptyList(),
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        var contextOpen by rememberSaveable(task.id) {
            mutableStateOf(!taskChatContextCollapsed(maxWidth.value.toInt()))
        }
        Row(modifier = Modifier.fillMaxSize()) {
            if (showChatList) {
                TaskChatListColumn(
                    state = state,
                    selectedTaskId = task.id,
                    onSelect = viewModel::openTaskById,
                    onTogglePin = viewModel::toggleChatPin,
                    onQueryChange = viewModel::updateChatListQuery,
                    onFilterChange = viewModel::setChatListFilter,
                    modifier = Modifier.width(280.dp).fillMaxHeight()
                )
                VerticalDivider()
            }
            TaskChatCenterColumn(
                task = task,
                currentUser = currentUser,
                state = state,
                viewModel = viewModel,
                unifiedForwardTargets = unifiedForwardTargets,
                contextOpen = contextOpen,
                onToggleContext = { contextOpen = !contextOpen },
                modifier = Modifier.weight(1f).fillMaxHeight()
            )
            if (contextOpen) {
                VerticalDivider()
                TaskChatContextPanel(
                    task = task,
                    currentUser = currentUser,
                    state = state,
                    viewModel = viewModel,
                    onClose = { contextOpen = false },
                    modifier = Modifier.width(320.dp).fillMaxHeight()
                )
            }
        }
    }
}

/* ------------------------------ Левая колонка ------------------------------ */

@Composable
private fun TaskChatListColumn(
    state: TasksUiState,
    selectedTaskId: Long,
    onSelect: (Long) -> Unit,
    onTogglePin: (Long) -> Unit,
    onQueryChange: (String) -> Unit,
    onFilterChange: (TaskChatListFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = state.chatListQuery,
            onValueChange = onQueryChange,
            label = { Text("Поиск по чатам") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            TaskChatListFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.chatListFilter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(filter.label()) }
                )
            }
        }
        state.chatListError?.let { ErrorMessageCard(error = it) }
        val items = filterChatList(state.chatList, state.chatListFilter, state.chatListQuery)
        when {
            state.chatListLoading && items.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            items.isEmpty() -> Text(
                text = "Чатов по этому фильтру нет.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(items, key = { it.taskId }) { item ->
                    TaskChatListRow(
                        item = item,
                        selected = item.taskId == selectedTaskId,
                        onClick = { onSelect(item.taskId) },
                        onTogglePin = { onTogglePin(item.taskId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskChatListRow(
    item: TaskChatListItemDto,
    selected: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) scheme.primaryContainer else scheme.surfaceContainerLowest
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.number,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = scheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (item.status !in setOf("CLOSED", "CANCELLED")) {
                    TextButton(onClick = onTogglePin) {
                        Text(
                            text = if (item.isPinned) "Открепить" else "Закрепить",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
            if (!item.lastMessageSnippet.isNullOrBlank()) {
                Text(
                    text = listOfNotNull(item.lastMessageAuthor, item.lastMessageSnippet)
                        .joinToString(": "),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.lastMessageAt?.let {
                    Text(
                        text = it.toShortDateTime(),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                if (item.mentionCount > 0) {
                    PillBadge(text = "@${item.mentionCount}", accent = Brand.Crimson, filled = true)
                    Spacer(Modifier.width(6.dp))
                }
                if (item.unreadCount > 0) {
                    PillBadge(text = item.unreadCount.toString(), accent = Brand.Blue, filled = true)
                }
            }
        }
    }
}

/* ------------------------------ Центральная колонка ------------------------------ */

@Composable
private fun TaskChatCenterColumn(
    task: TaskDto,
    currentUser: UserDto,
    state: TasksUiState,
    viewModel: TaskViewModel,
    unifiedForwardTargets: List<ConversationListItemDto>,
    contextOpen: Boolean,
    onToggleContext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val chat = state.chat
    var searchOpen by rememberSaveable(task.id) { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(searchOpen) {
        if (searchOpen) searchFocus.requestFocus()
    }

    Column(
        modifier = modifier.onPreviewKeyEvent { event ->
            when {
                event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.F -> {
                    searchOpen = true
                    true
                }
                event.type == KeyEventType.KeyDown && event.key == Key.Escape && searchOpen -> {
                    searchOpen = false
                    viewModel.updateChatSearchQuery("")
                    true
                }
                else -> false
            }
        }
    ) {
        // Шапка чата
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${task.number} · ${task.title}",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            PillBadge(text = task.status.localizedName(), accent = task.status.accentColor())
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { searchOpen = !searchOpen }) {
                Text(if (searchOpen) "Скрыть поиск" else "Поиск")
            }
            TextButton(onClick = onToggleContext) {
                Text(if (contextOpen) "Скрыть сведения" else "Сведения")
            }
            TextButton(onClick = viewModel::openFullCard) {
                Text("Карточка")
            }
        }

        if (searchOpen) {
            Column(modifier = Modifier.padding(horizontal = 12.dp)) {
                OutlinedTextField(
                    value = chat.searchQuery,
                    onValueChange = viewModel::updateChatSearchQuery,
                    label = { Text("Поиск по сообщениям") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(searchFocus)
                )
                chat.searchError?.let { ErrorMessageCard(error = it) }
                if (chat.searchLoading) {
                    Text(
                        text = "Поиск…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                if (chat.searchQuery.isNotBlank() && !chat.searchLoading) {
                    if (chat.searchResults.isEmpty()) {
                        Text(
                            text = "Ничего не найдено.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 180.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            chat.searchResults.forEach { result ->
                                Text(
                                    text = "${result.author?.fullName ?: "—"} · " +
                                        "${result.createdAt.toShortDateTime()} · " +
                                        chatSnippet(result.content, 100),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.scrollToSearchResult(result.messageId) }
                                        .padding(vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        chat.error?.let {
            ErrorMessageCard(error = it, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }

        // Сообщения
        if (chat.isLoading && chat.messages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            val listStates = remember { mutableStateMapOf<Long, LazyListState>() }
            val listState = listStates.getOrPut(task.id) { LazyListState() }
            val initializedScroll = remember { mutableSetOf<Long>() }
            var previousMessages by remember(task.id) { mutableStateOf(chat.messages) }
            var pendingNewCount by remember(task.id) { mutableIntStateOf(0) }
            val scrollScope = rememberCoroutineScope()
            LaunchedEffect(chat.messages) {
                val old = previousMessages
                val current = chat.messages
                if (current.isNotEmpty() && initializedScroll.add(task.id)) {
                    listState.scrollToItem(current.lastIndex)
                } else if (old.isNotEmpty() && current.isNotEmpty()) {
                    val oldFirstId = old.first().id
                    val prependedIndex = current.indexOfFirst { it.id == oldFirstId }
                    if (current.first().id != oldFirstId && prependedIndex > 0) {
                        val firstVisibleMessageId = old.getOrNull(listState.firstVisibleItemIndex)?.id ?: oldFirstId
                        val restored = restoreTaskChatPrependAnchor(
                            anchor = TaskChatScrollAnchor(
                                firstVisibleMessageId,
                                listState.firstVisibleItemIndex,
                                listState.firstVisibleItemScrollOffset
                            ),
                            oldMessageIds = old.map { it.id },
                            newMessageIds = current.map { it.id }
                        )
                        listState.scrollToItem(restored.index, restored.offset)
                    } else if (current.size > old.size && current.last().id != old.last().id) {
                        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        val isOwn = current.last().author?.userId == currentUser.id
                        if (shouldAutoScrollToNew(lastVisible, old.size, isOwn)) {
                            listState.animateScrollToItem(current.lastIndex)
                            pendingNewCount = 0
                        } else {
                            pendingNewCount += current.size - old.size
                        }
                    }
                }
                previousMessages = current
            }
            LaunchedEffect(chat.scrollToMessageId) {
                val targetId = chat.scrollToMessageId ?: return@LaunchedEffect
                val index = chat.messages.indexOfFirst { it.id == targetId }
                if (index >= 0) listState.animateScrollToItem(index)
                viewModel.clearScrollToMessage()
            }
            LaunchedEffect(listState, task.id, chat.messages) {
                snapshotFlow {
                    listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index to listState.layoutInfo.totalItemsCount
                }.distinctUntilChanged().collect { (lastVisible, totalCount) ->
                    val lastVisibleIndex = lastVisible ?: -1
                    viewModel.updateChatVisibleRange(
                        lastVisibleMessageId = chat.messages.getOrNull(lastVisibleIndex)?.id,
                        viewingLatest = totalCount > 0 && lastVisibleIndex >= totalCount - 1
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(chat.messages, key = { it.id }) { message ->
                    val index = chat.messages.indexOfFirst { it.id == message.id }
                    val previous = chat.messages.getOrNull(index - 1)
                    if (previous?.createdAt?.substringBefore('T') != message.createdAt.substringBefore('T')) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            HorizontalDivider(Modifier.weight(1f))
                            Text(message.createdAt.substringBefore('T'), style = MaterialTheme.typography.labelSmall)
                            HorizontalDivider(Modifier.weight(1f))
                        }
                    }
                    if (chat.firstUnreadMessageId == message.id) {
                        Text("Непрочитанные", color = MaterialTheme.colorScheme.primary)
                    }
                    when (message.type) {
                        ChatMessageType.SYSTEM -> WorkspaceSystemEvent(message)
                        ChatMessageType.USER -> WorkspaceMessageItem(
                            message = message,
                            currentUserId = currentUser.id,
                            canWrite = !task.isClosedLike(),
                            highlighted = chat.highlightedMessageId == message.id,
                            searchQuery = chat.searchQuery,
                            imagePreviews = chat.imagePreviews,
                            downloadingAttachmentId = chat.downloadingAttachmentId,
                            onDownloadAttachment = viewModel::downloadChatAttachment,
                            onLoadImage = viewModel::loadChatImagePreview,
                            onReply = { viewModel.setReplyTo(message) },
                            onToggleReaction = { emoji -> viewModel.toggleReaction(message.id, emoji) },
                            onEdit = viewModel::editChatMessage,
                            onRevoke = viewModel::revokeChatMessage,
                            forwardTargets = unifiedForwardTargets.ifEmpty {
                                state.chatList.filter { it.status !in setOf("CLOSED", "CANCELLED") }.map {
                                    ConversationListItemDto(
                                        kind = ConversationKind.TASK,
                                        id = it.taskId,
                                        title = "${it.number} · ${it.title}"
                                    )
                                }
                            },
                            onForward = viewModel::forwardChatMessage
                        )
                    }
                }
                if (chat.messages.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = "Сообщений пока нет. Напишите первым.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
            if (pendingNewCount > 0) {
                TextButton(onClick = {
                    scrollScope.launch { listState.animateScrollToItem(chat.messages.lastIndex) }
                    pendingNewCount = 0
                }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Новые сообщения: $pendingNewCount")
                }
            }
        }

        if (chat.typingUsers.isNotEmpty()) {
            Text(
                text = chat.typingUsers.joinToString(", ") + " печатает…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        chat.sendError?.let {
            ErrorMessageCard(error = it, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            if (!task.isClosedLike()) {
                TextButton(onClick = viewModel::sendMessage, enabled = !chat.isSending) { Text("Повторить отправку") }
            }
        }
        chat.actionError?.let {
            ErrorMessageCard(error = it, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        chat.downloadError?.let {
            ErrorMessageCard(error = it, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }
        chat.attachmentPickerError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp))
        }

        if (task.isClosedLike()) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = "Задача закрыта. Обсуждение доступно только для чтения.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        } else {
            WorkspaceComposer(
                task = task,
                state = state,
                chat = chat,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun WorkspaceComposer(
    task: TaskDto,
    state: TasksUiState,
    chat: ChatUiState,
    viewModel: TaskViewModel
) {
    chat.replyTo?.let { reply ->
        Surface(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Ответ: ${reply.author?.fullName ?: "—"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = reply.contentPreview,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                ErpIcon(
                    ErpIconGlyph.Close,
                    MaterialTheme.colorScheme.onSecondaryContainer,
                    Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = viewModel::clearReply)
                )
            }
        }
    }

    val participants = state.users
        .filter { it.id in task.chatMemberIds() }
        .map { org.example.project.dto.UserOptionDto(it.id, it.fullName) }
    val suggestions = mentionSuggestions(participants, chat.draft)
    if (suggestions.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            suggestions.take(5).forEach { user ->
                Text(
                    text = user.fullName,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { viewModel.updateChatDraft(insertMention(chat.draft, user.fullName)) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }

    chat.pendingFiles.forEach { file ->
        PendingAttachmentRow(file = file, onRemove = { viewModel.removeChatPendingFile(file) })
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        OutlinedTextField(
            value = chat.draft,
            onValueChange = viewModel::updateChatDraft,
            label = { Text("Сообщение (Ctrl+Enter — отправить)") },
            minLines = 1,
            maxLines = 4,
            modifier = Modifier
                .weight(1f)
                .onPreviewKeyEvent { event ->
                    when {
                        event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Enter -> {
                            viewModel.sendMessage()
                            true
                        }
                        event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                            viewModel.clearReply()
                            chat.replyTo != null
                        }
                        else -> false
                    }
                }
        )
        TextButton(onClick = viewModel::pickChatFile, enabled = !chat.isSending) {
            Text("Файлы")
        }
        Button(
            onClick = viewModel::sendMessage,
            enabled = (chat.draft.isNotBlank() || chat.pendingFiles.isNotEmpty()) && !chat.isSending
        ) {
            Text(if (chat.isSending) "Отправка…" else "Отправить")
        }
    }
}

@Composable
private fun WorkspaceSystemEvent(message: ChatMessageDto, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = message.createdAt.toShortDateTime(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WorkspaceMessageItem(
    message: ChatMessageDto,
    currentUserId: Long,
    canWrite: Boolean,
    highlighted: Boolean,
    searchQuery: String,
    imagePreviews: Map<Long, ByteArray>,
    downloadingAttachmentId: Long?,
    onDownloadAttachment: (AttachmentDto) -> Unit,
    onLoadImage: (AttachmentDto) -> Unit,
    onReply: () -> Unit,
    onToggleReaction: (String) -> Unit,
    onEdit: (ChatMessageDto, String) -> Unit,
    onRevoke: (ChatMessageDto) -> Unit,
    forwardTargets: List<ConversationListItemDto>,
    onForward: (ChatMessageDto, ConversationListItemDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val own = message.author?.userId == currentUserId
    var reactionPickerOpen by remember(message.id) { mutableStateOf(false) }
    var editing by remember(message.id) { mutableStateOf(false) }
    var revoking by remember(message.id) { mutableStateOf(false) }
    var forwarding by remember(message.id) { mutableStateOf(false) }
    var editDraft by remember(message.id, message.version) { mutableStateOf(message.content) }
    Box(modifier.fillMaxWidth(), contentAlignment = if (own) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            modifier = Modifier.widthIn(max = 560.dp),
            shape = MaterialTheme.shapes.medium,
            color = when {
                highlighted -> MaterialTheme.colorScheme.tertiaryContainer
                own -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerLowest
            }
        ) {
            Column(Modifier.padding(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(message.author?.fullName ?: "—", size = 24.dp)
                    Text(if (own) "Вы" else message.author?.fullName ?: "—", style = MaterialTheme.typography.labelLarge)
                    Text(message.createdAt.toShortDateTime(), style = MaterialTheme.typography.labelSmall)
                    if (message.editedAt != null) Text("изменено", style = MaterialTheme.typography.labelSmall)
                    if (own && message.revokedAt == null) {
                        Text("Доставлено", style = MaterialTheme.typography.labelSmall)
                        if (message.readByCount > 0) Text("Прочитано: ${message.readByCount}", style = MaterialTheme.typography.labelSmall)
                    }
                }
                message.source?.let { source ->
                    Text("Переслано от ${source.author?.fullName ?: "—"}", style = MaterialTheme.typography.labelSmall)
                }
                message.replyTo?.let { reply ->
                    Surface(Modifier.fillMaxWidth().padding(top = 4.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                        Text("${reply.author?.fullName ?: "—"}: ${reply.contentPreview}", Modifier.padding(8.dp), maxLines = 2)
                    }
                }
                if (message.revokedAt != null) {
                    Text("Сообщение удалено", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(formattedChatText(message.content, searchQuery), style = MaterialTheme.typography.bodyMedium)
                    message.attachments.forEach { attachment ->
                        if (isPreviewableImage(attachment.mimeType, attachment.sizeBytes)) {
                            LaunchedEffect(attachment.id) { onLoadImage(attachment) }
                            imagePreviews[attachment.id]?.let { DecodedImagePreview(it) }
                        }
                        AttachmentRow(
                            attachment = attachment,
                            isDownloading = downloadingAttachmentId == attachment.id,
                            onDownload = { onDownloadAttachment(attachment) }
                        )
                    }
                }
                ChatReactionRow(
                    reactions = message.reactions,
                    canWrite = canWrite && message.revokedAt == null,
                    pickerOpen = reactionPickerOpen,
                    onPickerToggle = { reactionPickerOpen = !reactionPickerOpen },
                    onToggle = {
                        onToggleReaction(it)
                        reactionPickerOpen = false
                    }
                )
                if (canWrite && message.revokedAt == null) {
                    Row {
                        TextButton(onClick = onReply) { Text("Ответить") }
                        TextButton(onClick = { forwarding = true }) { Text("Переслать") }
                        if (canEditChatMessage(own, canWrite, message.revokedAt != null)) {
                            TextButton(onClick = { editing = true }) { Text("Изменить") }
                            TextButton(onClick = { revoking = true }) { Text("Удалить") }
                        }
                    }
                }
            }
        }
    }
    if (editing) {
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Изменить сообщение") },
            text = { OutlinedTextField(editDraft, { editDraft = it }, minLines = 3) },
            confirmButton = {
                TextButton(onClick = {
                    onEdit(message, editDraft)
                    editing = false
                }, enabled = editDraft.isNotBlank()) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Отмена") } }
        )
    }
    if (revoking) {
        AlertDialog(
            onDismissRequest = { revoking = false },
            title = { Text("Удалить сообщение?") },
            text = { Text("Текст будет скрыт, а системный след сохранится.") },
            confirmButton = {
                TextButton(onClick = {
                    onRevoke(message)
                    revoking = false
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { revoking = false }) { Text("Отмена") } }
        )
    }
    if (forwarding) {
        AlertDialog(
            onDismissRequest = { forwarding = false },
            title = { Text("Переслать сообщение") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    forwardTargets.filter { it.canWrite }.forEach { target ->
                        TextButton(onClick = {
                            onForward(message, target)
                            forwarding = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(target.title ?: "${target.kind} #${target.id}")
                        }
                    }
                    if (forwardTargets.none { it.canWrite }) Text("Нет доступных чатов для пересылки.")
                }
            },
            confirmButton = { TextButton(onClick = { forwarding = false }) { Text("Отмена") } }
        )
    }
}

/* ------------------------------ Правая колонка ------------------------------ */

@Composable
private fun TaskChatContextPanel(
    task: TaskDto,
    currentUser: UserDto,
    state: TasksUiState,
    viewModel: TaskViewModel,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Сведения о задаче", style = MaterialTheme.typography.titleSmall)
            ErpIcon(
                ErpIconGlyph.Close,
                MaterialTheme.colorScheme.onSurfaceVariant,
                Modifier
                    .size(16.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onClose)
            )
        }

        ContextRow("Номер", task.number)
        ContextRow("Название", task.title)
        ContextRow("Статус", task.status.localizedName())
        ContextRow("Приоритет", task.priority.localizedName())
        ContextRow("Исполнитель", task.assigneeId?.let { userName(state.users, it) } ?: "—")
        ContextRow("Инициатор", userName(state.users, task.initiatorId))
        if (task.coexecutorIds.isNotEmpty()) {
            ContextRow("Соисполнители", task.coexecutorIds.joinToString(", ") { userName(state.users, it) })
        }
        if (task.observerIds.isNotEmpty()) {
            ContextRow("Наблюдатели", task.observerIds.joinToString(", ") { userName(state.users, it) })
        }
        ContextRow("Срок", task.dueDate.toShortDate())
        task.projectId?.let { projectId ->
            val projectName = state.projects.find { it.id == projectId }
                ?.let { "${it.number} ${it.name}" } ?: "ID $projectId"
            ContextRow("Проект", projectName)
        }

        Text("Вложения", style = MaterialTheme.typography.titleSmall)
        val chatAttachments = collectChatAttachments(state.chat.messages)
        val allAttachments = (state.taskAttachments + chatAttachments).distinctBy { it.id }
        if (allAttachments.isEmpty()) {
            Text(
                text = "Вложений нет.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            allAttachments.forEach { attachment ->
                AttachmentRow(
                    attachment = attachment,
                    isDownloading = state.chat.downloadingAttachmentId == attachment.id,
                    onDownload = { viewModel.downloadChatAttachment(attachment) }
                )
            }
        }

        Text("Документы", style = MaterialTheme.typography.titleSmall)
        if (state.linkedDocuments.isEmpty()) {
            Text(
                text = "Связанных документов нет.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            state.linkedDocuments.forEach { document ->
                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(text = document.number, style = MaterialTheme.typography.labelSmall, color = Brand.Blue)
                    Text(
                        text = document.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Button(onClick = viewModel::openFullCard, modifier = Modifier.fillMaxWidth()) {
            Text("Открыть карточку задачи")
        }
    }
}

@Composable
private fun ContextRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
