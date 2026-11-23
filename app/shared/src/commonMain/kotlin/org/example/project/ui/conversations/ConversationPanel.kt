package org.example.project.ui.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import org.example.project.ui.theme.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import org.example.project.ui.theme.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.example.project.dto.AttachmentDto
import org.example.project.dto.ChatMessageType
import org.example.project.dto.ConversationListItemDto
import org.example.project.dto.ConversationMemberDto
import org.example.project.dto.ConversationMessageDto
import org.example.project.dto.UserOptionDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.tasks.canEditChatMessage
import org.example.project.ui.tasks.chatSnippet
import org.example.project.ui.tasks.insertMention
import org.example.project.ui.tasks.isPreviewableImage
import org.example.project.ui.tasks.mentionSuggestions
import org.example.project.ui.tasks.restorePrependAnchor
import org.example.project.ui.tasks.SharedTaskMessageCard
import org.example.project.ui.tasks.parseTaskMessageLink
import org.example.project.ui.tasks.shouldAutoScrollToNew
import org.example.project.ui.theme.Avatar
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph

fun formatAttachmentSize(sizeBytes: Long): String = when {
    sizeBytes >= 1024 * 1024 -> "${sizeBytes / (1024 * 1024)} МБ"
    sizeBytes >= 1024 -> "${sizeBytes / 1024} КБ"
    else -> "$sizeBytes Б"
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ConversationPanel(
    messages: ConversationMessagesUiState,
    canWrite: Boolean,
    currentUserId: Long,
    readOnlyHint: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onPickFile: () -> Unit,
    onClearFile: () -> Unit,
    onDownloadAttachment: (AttachmentDto) -> Unit,
    onOpenTask: (Long) -> Unit = {},
    modifier: Modifier = Modifier,
    conversationKey: String = "",
    participants: List<ConversationMemberDto> = emptyList(),
    forwardTargets: List<ConversationListItemDto> = emptyList(),
    typingUsers: List<String> = emptyList(),
    viewModel: ConversationViewModel? = null
) {
    var searchOpen by rememberSaveable(conversationKey) { mutableStateOf(false) }
    var dragActive by remember(conversationKey) { mutableStateOf(false) }
    val dropTarget = remember(viewModel, conversationKey, canWrite) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dragActive = false
                return viewModel?.dropFiles(event) == true
            }

            override fun onEntered(event: DragAndDropEvent) {
                dragActive = true
            }

            override fun onExited(event: DragAndDropEvent) {
                dragActive = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                dragActive = false
            }
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(if (dragActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.07f) else Color.Transparent)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { canWrite && viewModel != null },
                target = dropTarget
            )
            .padding(8.dp)
            .onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.F -> {
                        searchOpen = true
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.key == Key.Escape && searchOpen -> {
                        searchOpen = false
                        viewModel?.closeMessageSearch()
                        true
                    }
                    else -> false
                }
            }
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) {
            if (viewModel != null) TextButton(onClick = { searchOpen = !searchOpen }) { Text("Поиск") }
            TextButton(onClick = onRefresh, enabled = !messages.isLoading) { Text("Обновить") }
        }
        if (searchOpen && viewModel != null) {
            ConversationMessageSearch(messages, viewModel)
        }
        messages.error?.let { ErrorMessageCard(it, Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
        messages.actionError?.let { ErrorMessageCard(it, Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }

        if (messages.isLoading && messages.messages.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val listState = remember(conversationKey) { LazyListState(messages.scrollPosition) }
            val initialized = remember(conversationKey) { mutableStateOf(false) }
            var previousMessages by remember(conversationKey) { mutableStateOf(messages.messages) }
            var pendingNewCount by remember(conversationKey) { mutableIntStateOf(0) }
            LaunchedEffect(messages.messages) {
                val old = previousMessages
                val current = messages.messages
                if (!initialized.value && current.isNotEmpty()) {
                    if (messages.scrollPosition == 0) listState.scrollToItem(current.lastIndex)
                    initialized.value = true
                } else if (old.isNotEmpty() && current.isNotEmpty()) {
                    val oldFirstId = old.first().id
                    val oldLastId = old.last().id
                    val prepended = current.indexOfFirst { it.id == oldFirstId }
                    if (current.first().id != oldFirstId && prepended > 0) {
                        listState.scrollToItem(
                            restorePrependAnchor(listState.firstVisibleItemIndex, prepended),
                            listState.firstVisibleItemScrollOffset
                        )
                    } else if (current.size > old.size && current.last().id != oldLastId) {
                        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                        val own = current.last().author?.userId == currentUserId
                        if (shouldAutoScrollToNew(lastVisible, old.size, own)) {
                            listState.animateScrollToItem(current.lastIndex)
                            pendingNewCount = 0
                        } else {
                            pendingNewCount += current.size - old.size
                        }
                    }
                }
                previousMessages = current
            }
            LaunchedEffect(messages.scrollToMessageId) {
                val target = messages.scrollToMessageId ?: return@LaunchedEffect
                val index = messages.messages.indexOfFirst { it.id == target }
                if (index >= 0) listState.animateScrollToItem(index)
                viewModel?.clearScrollToMessage()
            }
            LaunchedEffect(listState, conversationKey, messages.messages) {
                snapshotFlow {
                    Triple(
                        listState.firstVisibleItemIndex,
                        listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0,
                        listState.layoutInfo.totalItemsCount
                    )
                }.distinctUntilChanged().collect { (firstVisible, lastVisible, totalCount) ->
                    viewModel?.updateScrollPosition(firstVisible)
                    val headerOffset = if (messages.hasMoreNewer) 1 else 0
                    val lastVisibleMessageId = messages.messages.getOrNull(lastVisible - headerOffset)?.id
                    viewModel?.updateVisibleRange(lastVisibleMessageId, lastVisible >= totalCount - 1)
                }
            }
            val scrollScope = rememberCoroutineScope()
            Box(Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (messages.hasMoreNewer) {
                        item(key = "load-more") {
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                TextButton(onClick = onLoadMore, enabled = !messages.isLoadingMore) {
                                    Text(if (messages.isLoadingMore) "Загрузка…" else "Загрузить ещё сообщения")
                                }
                            }
                        }
                    }
                    items(messages.messages, key = { it.id }) { message ->
                        val index = messages.messages.indexOfFirst { it.id == message.id }
                        val previous = messages.messages.getOrNull(index - 1)
                        if (previous?.createdAt?.substringBefore('T') != message.createdAt.substringBefore('T')) {
                            MessageDateSeparator(message.createdAt.substringBefore('T'))
                        }
                        if (messages.firstUnreadMessageId == message.id) FirstUnreadDivider()
                        when (message.type) {
                            ChatMessageType.SYSTEM -> ConversationSystemEvent(message)
                            ChatMessageType.USER -> ConversationMessageItem(
                                message = message,
                                currentUserId = currentUserId,
                                canWrite = canWrite,
                                messages = messages,
                                onDownloadAttachment = onDownloadAttachment,
                                onOpenTask = onOpenTask,
                                viewModel = viewModel,
                                forwardTargets = forwardTargets
                            )
                        }
                    }
                    if (messages.messages.isEmpty()) {
                        item(key = "empty") { Text("Сообщений пока нет. Напишите первым.", Modifier.padding(8.dp)) }
                    }
                }
                if (pendingNewCount > 0) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        TextButton(onClick = {
                            scrollScope.launch { listState.animateScrollToItem(messages.messages.lastIndex) }
                            pendingNewCount = 0
                        }) { Text("Новые сообщения: $pendingNewCount") }
                    }
                }
            }
        }

        if (typingUsers.isNotEmpty()) {
            Text(
                text = typingUsers.joinToString(", ") + " печатает…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        messages.sendError?.let {
            ErrorMessageCard(it, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            if (canWrite) TextButton(onClick = onSend, enabled = !messages.isSending) { Text("Повторить отправку") }
        }
        messages.downloadError?.let { ErrorMessageCard(it, Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
        messages.attachmentPickerError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp))
        }

        if (!canWrite) {
            Surface(Modifier.fillMaxWidth().padding(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(readOnlyHint, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            ConversationComposer(messages, participants, onDraftChange, onSend, onPickFile, viewModel, onClearFile)
        }
    }
}

@Composable
private fun ConversationMessageSearch(state: ConversationMessagesUiState, viewModel: ConversationViewModel) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = viewModel::updateMessageSearchQuery,
            label = { Text("Поиск по сообщениям") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        state.searchError?.let { ErrorMessageCard(it) }
        if (state.searchLoading) Text("Поиск…")
        if (state.searchQuery.isNotBlank() && !state.searchLoading) {
            if (state.searchResults.isEmpty()) Text("Ничего не найдено.")
            Column(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
                state.searchResults.forEach { result ->
                    Text(
                        "${result.author?.fullName ?: "Неизвестный автор"} · ${chatSnippet(result.content, 100)}",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable {
                            viewModel.scrollToSearchResult(result.id)
                        }.padding(4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationComposer(
    state: ConversationMessagesUiState,
    participants: List<ConversationMemberDto>,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickFile: () -> Unit,
    viewModel: ConversationViewModel?,
    onClearFile: () -> Unit
) {
    val editingMessage = state.messages.find { it.id == state.editingMessageId }
    val reply = state.messages.find { it.id == state.replyToMessageId }
    if (editingMessage != null) {
        Surface(Modifier.fillMaxWidth().padding(8.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
            Column(Modifier.fillMaxWidth().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Редактирование сообщения",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel?.cancelEditingMessage() }) { Text("Отмена") }
                }
                Text(
                    text = "Исходный текст:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = editingMessage.content,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 96.dp).verticalScroll(rememberScrollState())
                )
            }
        }
    } else if (reply != null) {
        Surface(Modifier.fillMaxWidth().padding(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ответ: ${reply.author?.fullName ?: "Неизвестный автор"} · ${chatSnippet(reply.content)}",
                    Modifier.weight(1f).padding(8.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(onClick = { viewModel?.setReplyToMessageId(null) }) { Text("Отмена") }
            }
        }
    }
    val options = participants.map { UserOptionDto(it.userId, it.fullName) }
    mentionSuggestions(options, state.draft).take(5).forEach { user ->
        Text(user.fullName, Modifier.fillMaxWidth().clickable {
            onDraftChange(insertMention(state.draft, user.fullName))
        }.padding(horizontal = 12.dp, vertical = 5.dp))
    }
    if (editingMessage == null) {
        state.pendingFiles.forEach { file ->
            PendingAttachmentRow(file, onRemove = { viewModel?.removePendingFile(file) ?: onClearFile() })
        }
    }
    val canSubmit = if (editingMessage != null) {
        state.draft.isNotBlank() && !state.isSending
    } else {
        (state.draft.isNotBlank() || state.pendingFiles.isNotEmpty()) && !state.isSending
    }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = onDraftChange,
            label = {
                Text(if (editingMessage != null) "Изменить сообщение (Ctrl+Enter: сохранить)" else "Сообщение (Ctrl+Enter: отправить)")
            },
            minLines = 1,
            maxLines = 4,
            modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Enter -> {
                        onSend()
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.key == Key.Escape && editingMessage != null -> {
                        viewModel?.cancelEditingMessage()
                        true
                    }
                    event.type == KeyEventType.KeyDown && event.key == Key.Escape && state.replyToMessageId != null -> {
                        viewModel?.setReplyToMessageId(null)
                        true
                    }
                    else -> false
                }
            }
        )
        IconButton(
            onClick = onPickFile,
            enabled = editingMessage == null && !state.isSending,
            modifier = Modifier.size(40.dp).semantics { contentDescription = "Прикрепить файлы" }
        ) {
            ErpIcon(
                glyph = ErpIconGlyph.Attachment,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
        }
        Button(onClick = onSend, enabled = canSubmit) {
            Text(
                when {
                    state.isSending && editingMessage != null -> "Сохранение…"
                    state.isSending -> "Отправка…"
                    editingMessage != null -> "Сохранить"
                    else -> "Отправить"
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationMessageItem(
    message: ConversationMessageDto,
    currentUserId: Long,
    canWrite: Boolean,
    messages: ConversationMessagesUiState,
    onDownloadAttachment: (AttachmentDto) -> Unit,
    onOpenTask: (Long) -> Unit = {},
    viewModel: ConversationViewModel?,
    forwardTargets: List<ConversationListItemDto>
) {
    val own = message.author?.userId == currentUserId
    var reactionPicker by remember(message.id) { mutableStateOf(false) }
    var actionsOpen by remember(message.id) { mutableStateOf(false) }
    var revoking by remember(message.id) { mutableStateOf(false) }
    var forwarding by remember(message.id) { mutableStateOf(false) }
    val highlighted = messages.highlightedMessageId == message.id
    val bubbleShape = RoundedCornerShape(
        topStart = 20.dp,
        topEnd = 20.dp,
        bottomEnd = if (own) 6.dp else 20.dp,
        bottomStart = if (own) 20.dp else 6.dp
    )
    val bubbleBrush = when {
        highlighted -> SolidColor(MaterialTheme.colorScheme.tertiaryContainer)
        own -> SolidColor(Brand.Blue)
        else -> SolidColor(MaterialTheme.colorScheme.surfaceContainerLowest)
    }
    val contentColor = when {
        highlighted -> MaterialTheme.colorScheme.onTertiaryContainer
        own -> Color.White
        else -> MaterialTheme.colorScheme.onSurface
    }
    val status = buildString {
        append(message.createdAt.substringAfter('T').take(5))
        if (message.editedAt != null) append(" · изменено")
        if (own && message.revokedAt == null) {
            append(if (message.readByCount > 0) " · Прочитано" else " · Доставлено")
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (own) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        if (!own) {
            Avatar(message.author?.fullName ?: "Неизвестный автор", size = 32.dp)
            Spacer(Modifier.width(8.dp))
        }
        Column(Modifier.widthIn(max = 560.dp)) {
            if (!own) {
                Text(
                    text = message.author?.fullName ?: "Неизвестный автор",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 3.dp)
                )
            }
            Box(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .shadow(if (own) 6.dp else 1.dp, bubbleShape, clip = false)
                    .clip(bubbleShape)
                    .background(bubbleBrush, bubbleShape)
                    .then(
                        if (own || highlighted) Modifier
                        else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, bubbleShape)
                    )
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    message.source?.let { source ->
                        Text(
                            text = "Переслано от ${source.author?.fullName ?: "Неизвестный автор"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = contentColor.copy(alpha = 0.65f),
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    message.replyTo?.let { reply ->
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            shape = RoundedCornerShape(6.dp),
                            color = contentColor.copy(alpha = 0.06f)
                        ) {
                            Text(
                                text = "${reply.author?.fullName ?: "Неизвестный автор"}: ${reply.contentPreview}",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                color = contentColor,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    if (message.revokedAt != null) {
                        Text(
                            text = "Сообщение удалено",
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor.copy(alpha = 0.65f)
                        )
                    } else {
                        val taskLink = parseTaskMessageLink(message.content)
                        if (taskLink != null) {
                            SharedTaskMessageCard(
                                link = taskLink,
                                onClick = { onOpenTask(taskLink.taskId) },
                                contentColor = contentColor
                            )
                        } else {
                            Text(
                                text = formattedChatText(message.content, messages.searchQuery),
                                style = MaterialTheme.typography.bodyLarge,
                                color = contentColor
                            )
                        }
                        message.attachments.forEach { attachment ->
                            if (isPreviewableImage(attachment.mimeType, attachment.sizeBytes)) {
                                LaunchedEffect(attachment.id) { viewModel?.loadImagePreview(attachment) }
                                messages.imagePreviews[attachment.id]?.let { DecodedImagePreview(it) }
                            }
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = contentColor.copy(alpha = if (own && !highlighted) 0.14f else 0.06f)
                            ) {
                                AttachmentRow(
                                    attachment = attachment,
                                    isDownloading = messages.downloadingAttachmentId == attachment.id,
                                    onDownload = { onDownloadAttachment(attachment) },
                                    contentColor = contentColor,
                                    actionColor = if (own && !highlighted) Color.White else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    ChatReactionRow(
                        reactions = message.reactions,
                        canWrite = canWrite && message.revokedAt == null,
                        pickerOpen = reactionPicker,
                        onPickerToggle = { reactionPicker = !reactionPicker },
                        onToggle = {
                            viewModel?.toggleReaction(message.id, it)
                            reactionPicker = false
                        },
                        actionColor = if (own && !highlighted) Color.White else MaterialTheme.colorScheme.primary,
                        showAddButton = false
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val statusContent: @Composable () -> Unit = {
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelSmall,
                                color = contentColor.copy(alpha = 0.72f)
                            )
                        }
                        if (message.readBy.isEmpty()) {
                            statusContent()
                        } else {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
                                tooltip = {
                                    PlainTooltip {
                                        Column {
                                            Text("Прочитали", style = MaterialTheme.typography.labelSmall)
                                            message.readBy.forEach { reader ->
                                                Text(reader.fullName, maxLines = 1)
                                            }
                                        }
                                    }
                                },
                                state = rememberTooltipState()
                            ) {
                                statusContent()
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        if (canWrite && message.revokedAt == null) {
                            Box {
                                IconButton(
                                    onClick = { actionsOpen = !actionsOpen },
                                    modifier = Modifier.size(28.dp).semantics { contentDescription = "Действия с сообщением" }
                                ) {
                                    ErpIcon(
                                        glyph = ErpIconGlyph.MoreHoriz,
                                        tint = contentColor.copy(alpha = 0.72f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                DropdownMenu(expanded = actionsOpen, onDismissRequest = { actionsOpen = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Добавить реакцию") },
                                        onClick = {
                                            actionsOpen = false
                                            reactionPicker = true
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Ответить") },
                                        onClick = {
                                            actionsOpen = false
                                            viewModel?.setReplyToMessageId(message.id)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text("Переслать") },
                                        onClick = {
                                            actionsOpen = false
                                            forwarding = true
                                        }
                                    )
                                    if (canEditChatMessage(own, canWrite, message.revokedAt != null)) {
                                        DropdownMenuItem(
                                            text = { Text("Изменить") },
                                            onClick = {
                                                actionsOpen = false
                                                viewModel?.startEditingMessage(message)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Удалить", color = MaterialTheme.colorScheme.error) },
                                            onClick = {
                                                actionsOpen = false
                                                revoking = true
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (revoking) {
        org.example.project.ui.theme.ErpDialog(
            title = "Удалить сообщение?",
            glyph = ErpIconGlyph.Send,
            gradient = org.example.project.ui.theme.Gradients.Sunset,
            width = 460.dp,
            confirmText = "Удалить",
            onConfirm = {
                viewModel?.revokeMessage(message)
                revoking = false
            },

            confirmGradient = org.example.project.ui.theme.Gradients.Danger,

            onDismiss = { revoking = false }
        ) {
            Text("Текст будет скрыт, а системный след сохранится.")
        }
    }
    if (forwarding) {
        ForwardConversationDialog(message, forwardTargets, currentUserId, onDismiss = { forwarding = false }) { target ->
            viewModel?.forwardMessage(message, target)
            forwarding = false
        }
    }
}

@Composable
private fun ForwardConversationDialog(
    message: ConversationMessageDto,
    targets: List<ConversationListItemDto>,
    currentUserId: Long,
    onDismiss: () -> Unit,
    onForward: (ConversationListItemDto) -> Unit
) {
    var query by remember(message.id) { mutableStateOf("") }
    var selectedKey by remember(message.id) { mutableStateOf<String?>(null) }
    val targetRows = targets.filter { it.canWrite }.map { target ->
        target to target.toConversationDto().displayTitle(currentUserId)
    }
    val filteredRows = targetRows.filter { (target, title) ->
        query.isBlank() || title.contains(query.trim(), ignoreCase = true) ||
            target.kind.localizedName().contains(query.trim(), ignoreCase = true)
    }
    val selectedTarget = targetRows.firstOrNull { (target, _) ->
        "${target.kind}:${target.id}" == selectedKey
    }?.first

    org.example.project.ui.theme.ErpDialog(
        title = "Кому переслать?",
        glyph = ErpIconGlyph.Send,
        gradient = org.example.project.ui.theme.Gradients.Primary,
        width = 580.dp,
        confirmText = "Переслать",
        onConfirm = { selectedTarget?.let(onForward) },
        confirmEnabled = selectedTarget != null,
        onDismiss = onDismiss
    ) {
        Surface(

            shape = RoundedCornerShape(8.dp),

            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            text = "Пересылаемое сообщение",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = chatSnippet(message.content, 140).ifBlank {
                                if (message.attachments.isNotEmpty()) "Вложение" else "Сообщение"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Поиск по чатам") },
                    singleLine = true,
                    leadingIcon = {
                        ErpIcon(ErpIconGlyph.Search, modifier = Modifier.size(18.dp))
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }, modifier = Modifier.size(36.dp)) {
                                ErpIcon(ErpIconGlyph.Close, modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                )
                if (filteredRows.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (targetRows.isEmpty()) "Нет доступных чатов для пересылки." else "Чаты не найдены. Измените запрос.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp).padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(filteredRows, key = { (target, _) -> "${target.kind}:${target.id}" }) { (target, title) ->
                            val key = "${target.kind}:${target.id}"
                            val selected = key == selectedKey
                            Surface(
                                onClick = { selectedKey = key },
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Avatar(name = title, size = 32.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = target.kind.localizedName(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                                        if (selected) {
                                            ErpIcon(
                                                glyph = ErpIconGlyph.CheckCircle,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }

@Composable
private fun ConversationSystemEvent(message: ConversationMessageDto) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                text = message.createdAt.substringAfter('T').take(5),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MessageDateSeparator(date: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text(
            org.example.project.ui.theme.ErpFormat.dateRu(date),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}

@Composable
private fun FirstUnreadDivider() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
        Text("Непрочитанные", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
internal fun AttachmentRow(
    attachment: AttachmentDto,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = LocalContentColor.current,
    actionColor: Color = MaterialTheme.colorScheme.primary
) {
    Row(modifier = modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(attachment.fileName, style = MaterialTheme.typography.labelMedium, color = contentColor)
            Text(
                text = formatAttachmentSize(attachment.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f)
            )
        }
        TextButton(
            onClick = onDownload,
            enabled = !isDownloading,
            colors = ButtonDefaults.textButtonColors(contentColor = actionColor)
        ) {
            Text(if (isDownloading) "Скачивание…" else "Скачать")
        }
    }
}
