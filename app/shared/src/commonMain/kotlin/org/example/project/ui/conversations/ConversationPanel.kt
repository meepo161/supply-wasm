package org.example.project.ui.conversations

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.example.project.dto.AttachmentDto
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
import org.example.project.ui.tasks.shouldAutoScrollToNew
import org.example.project.ui.tasks.toShortDateTime
import org.example.project.ui.theme.Avatar

fun formatAttachmentSize(sizeBytes: Long): String = when {
    sizeBytes >= 1024 * 1024 -> "${sizeBytes / (1024 * 1024)} МБ"
    sizeBytes >= 1024 -> "${sizeBytes / 1024} КБ"
    else -> "$sizeBytes Б"
}

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
    modifier: Modifier = Modifier,
    conversationKey: String = "",
    participants: List<ConversationMemberDto> = emptyList(),
    forwardTargets: List<ConversationListItemDto> = emptyList(),
    viewModel: ConversationViewModel? = null
) {
    var searchOpen by rememberSaveable(conversationKey) { mutableStateOf(false) }
    Column(
        modifier = modifier.fillMaxSize().padding(8.dp).onPreviewKeyEvent { event ->
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
                        ConversationMessageItem(
                            message = message,
                            currentUserId = currentUserId,
                            canWrite = canWrite,
                            messages = messages,
                            onDownloadAttachment = onDownloadAttachment,
                            viewModel = viewModel,
                            forwardTargets = forwardTargets
                        )
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
                        "${result.author?.fullName ?: "—"} · ${chatSnippet(result.content, 100)}",
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
    val reply = state.messages.find { it.id == state.replyToMessageId }
    if (reply != null) {
        Surface(Modifier.fillMaxWidth().padding(8.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Ответ: ${reply.author?.fullName ?: "—"} · ${chatSnippet(reply.content)}",
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
    state.pendingFiles.forEach { file ->
        PendingAttachmentRow(file, onRemove = { viewModel?.removePendingFile(file) ?: onClearFile() })
    }
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            value = state.draft,
            onValueChange = onDraftChange,
            label = { Text("Сообщение (Ctrl+Enter — отправить)") },
            minLines = 1,
            maxLines = 4,
            modifier = Modifier.weight(1f).onPreviewKeyEvent { event ->
                when {
                    event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Enter -> {
                        onSend()
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
        TextButton(onClick = onPickFile, enabled = !state.isSending) { Text("Файлы") }
        Button(onClick = onSend, enabled = (state.draft.isNotBlank() || state.pendingFiles.isNotEmpty()) && !state.isSending) {
            Text(if (state.isSending) "Отправка…" else "Отправить")
        }
    }
}

@Composable
private fun ConversationMessageItem(
    message: ConversationMessageDto,
    currentUserId: Long,
    canWrite: Boolean,
    messages: ConversationMessagesUiState,
    onDownloadAttachment: (AttachmentDto) -> Unit,
    viewModel: ConversationViewModel?,
    forwardTargets: List<ConversationListItemDto>
) {
    val own = message.author?.userId == currentUserId
    var reactionPicker by remember(message.id) { mutableStateOf(false) }
    var editing by remember(message.id) { mutableStateOf(false) }
    var revoking by remember(message.id) { mutableStateOf(false) }
    var forwarding by remember(message.id) { mutableStateOf(false) }
    var editDraft by remember(message.id, message.version) { mutableStateOf(message.content) }
    Box(Modifier.fillMaxWidth(), contentAlignment = if (own) Alignment.CenterEnd else Alignment.CenterStart) {
        Surface(
            modifier = Modifier.widthIn(max = 620.dp),
            shape = MaterialTheme.shapes.medium,
            color = when {
                messages.highlightedMessageId == message.id -> MaterialTheme.colorScheme.tertiaryContainer
                own -> MaterialTheme.colorScheme.primaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainerLowest
            }
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Surface(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("${reply.author?.fullName ?: "—"}: ${reply.contentPreview}", Modifier.padding(6.dp), maxLines = 2)
                    }
                }
                if (message.revokedAt != null) {
                    Text("Сообщение удалено", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(formattedChatText(message.content, messages.searchQuery), style = MaterialTheme.typography.bodyMedium)
                    message.attachments.forEach { attachment ->
                        if (isPreviewableImage(attachment.mimeType, attachment.sizeBytes)) {
                            LaunchedEffect(attachment.id) { viewModel?.loadImagePreview(attachment) }
                            messages.imagePreviews[attachment.id]?.let { DecodedImagePreview(it) }
                        }
                        AttachmentRow(
                            attachment = attachment,
                            isDownloading = messages.downloadingAttachmentId == attachment.id,
                            onDownload = { onDownloadAttachment(attachment) }
                        )
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
                    }
                )
                if (canWrite && message.revokedAt == null) {
                    Row {
                        TextButton(onClick = { viewModel?.setReplyToMessageId(message.id) }) { Text("Ответить") }
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
                    viewModel?.editMessage(message, editDraft)
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
                    viewModel?.revokeMessage(message)
                    revoking = false
                }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { revoking = false }) { Text("Отмена") } }
        )
    }
    if (forwarding) {
        ForwardConversationDialog(message, forwardTargets, onDismiss = { forwarding = false }) { target ->
            viewModel?.forwardMessage(message, target)
            forwarding = false
        }
    }
}

@Composable
private fun ForwardConversationDialog(
    message: ConversationMessageDto,
    targets: List<ConversationListItemDto>,
    onDismiss: () -> Unit,
    onForward: (ConversationListItemDto) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переслать сообщение") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(chatSnippet(message.content, 120))
                targets.filter { it.canWrite }.forEach { target ->
                    TextButton(onClick = { onForward(target) }, modifier = Modifier.fillMaxWidth()) {
                        Text(target.title ?: "${target.kind.localizedName()} #${target.id}")
                    }
                }
                if (targets.none { it.canWrite }) Text("Нет доступных чатов для пересылки.")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun MessageDateSeparator(date: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f))
        Text(date, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
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
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(attachment.fileName, style = MaterialTheme.typography.labelMedium)
            Text(formatAttachmentSize(attachment.sizeBytes), style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = onDownload, enabled = !isDownloading) {
            Text(if (isDownloading) "Скачивание…" else "Скачать")
        }
    }
}
