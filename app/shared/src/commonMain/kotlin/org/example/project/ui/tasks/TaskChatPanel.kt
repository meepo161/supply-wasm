package org.example.project.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.dto.AttachmentDto
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ChatMessageType
import org.example.project.ui.conversations.AttachmentRow
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Avatar
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SoftButton

/**
 * Обсуждение задачи (Task Chat).
 *
 * Оформление повторяет остальную дизайн-систему: у сообщений аватар автора с
 * градиентом по имени, у системных событий — компактная строка с иконкой.
 * Данные только реальные: счётчик сообщений считается по загруженной странице,
 * «Новые» показывается лишь когда сервер сообщил о более поздних сообщениях,
 * а дата-разделитель строится из того же `createdAt`, что пришёл в сообщении.
 */
@Composable
fun TaskChatPanel(
    chat: ChatUiState,
    readOnly: Boolean,
    currentUserId: Long?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onFilterChange: (ChatMessageFilter) -> Unit,
    onPickFile: () -> Unit,
    onClearFile: () -> Unit,
    onDownloadAttachment: (AttachmentDto) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        ChatHeader(
            chat = chat,
            onRefresh = onRefresh,
            onFilterChange = onFilterChange
        )

        chat.error?.let {
            ErrorMessageCard(
                error = it,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        if (chat.isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            ChatTimeline(
                chat = chat,
                currentUserId = currentUserId,
                onLoadMore = onLoadMore,
                onDownloadAttachment = onDownloadAttachment,
                modifier = Modifier.weight(1f)
            )
        }

        chat.sendError?.let {
            ErrorMessageCard(
                error = it,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
        chat.downloadError?.let {
            ErrorMessageCard(
                error = it,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }

        if (readOnly) {
            ReadOnlyNotice()
        } else {
            ChatComposer(
                chat = chat,
                onDraftChange = onDraftChange,
                onSend = onSend,
                onPickFile = onPickFile,
                onClearFile = onClearFile
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Шапка: счётчики, фильтр и обновление
// ---------------------------------------------------------------------------

@Composable
private fun ChatHeader(
    chat: ChatUiState,
    onRefresh: () -> Unit,
    onFilterChange: (ChatMessageFilter) -> Unit
) {
    val userCount = chat.messages.count { it.type == ChatMessageType.USER }
    val systemCount = chat.messages.count { it.type == ChatMessageType.SYSTEM }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Обсуждение",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Сообщений: $userCount · событий: $systemCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (chat.hasMoreNewer) {
                    PillBadge(
                        text = "Есть новые",
                        accent = MaterialTheme.colorScheme.tertiary
                    )
                    Spacer(Modifier.width(4.dp))
                }
                ChatIconButton(
                    glyph = ErpIconGlyph.Refresh,
                    contentDescription = "Обновить обсуждение",
                    enabled = !chat.isLoading,
                    onClick = onRefresh
                )
            }
        }

        // Фильтр «Все / Сообщения / События» остаётся сегментированным контролом:
        // это переключение представления, а не выбор одного из многих значений.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ChatMessageFilter.entries.forEach { filter ->
                val count = when (filter) {
                    ChatMessageFilter.ALL -> userCount + systemCount
                    ChatMessageFilter.USER -> userCount
                    ChatMessageFilter.SYSTEM -> systemCount
                }
                ChatFilterTab(
                    label = filter.label,
                    count = count,
                    selected = chat.filter == filter,
                    onClick = { onFilterChange(filter) }
                )
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

@Composable
private fun ChatFilterTab(
    label: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .background(
                color = if (selected) scheme.primary.copy(alpha = 0.12f)
                else scheme.surfaceContainerLow.copy(alpha = 0.6f),
                shape = RoundedCornerShape(6.dp)
            )
            .border(
                width = 1.dp,
                color = if (selected) scheme.primary.copy(alpha = 0.45f)
                else scheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) scheme.primary else scheme.onSurfaceVariant
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) scheme.primary.copy(alpha = 0.8f)
                else scheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun ChatIconButton(
    glyph: ErpIconGlyph,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(34.dp)
            .background(
                color = scheme.surfaceContainerLow,
                shape = RoundedCornerShape(6.dp)
            )
            .semantics { this.contentDescription = contentDescription }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        ErpIcon(
            glyph = glyph,
            tint = if (enabled) scheme.onSurfaceVariant else scheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(17.dp)
        )
    }
}

// ---------------------------------------------------------------------------
// Лента сообщений
// ---------------------------------------------------------------------------

@Composable
private fun ChatTimeline(
    chat: ChatUiState,
    currentUserId: Long?,
    onLoadMore: () -> Unit,
    onDownloadAttachment: (AttachmentDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val visible = chat.messages.filter { message ->
        when (chat.filter) {
            ChatMessageFilter.ALL -> true
            ChatMessageFilter.USER -> message.type == ChatMessageType.USER
            ChatMessageFilter.SYSTEM -> message.type == ChatMessageType.SYSTEM
        }
    }
    val listState = rememberLazyListState()
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount == 0 || lastVisible >= info.totalItemsCount - 3
        }
    }

    // Лента растёт вниз: при новом сообщении держим последнее в поле зрения,
    // но не выдёргиваем пользователя, если он отмотал историю выше.
    LaunchedEffect(visible.size) {
        if (visible.isNotEmpty() && atBottom) {
            val target = listState.layoutInfo.totalItemsCount - 1
            if (target >= 0) listState.animateScrollToItem(target)
        }
    }

    if (visible.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            ChatEmptyState(filter = chat.filter)
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 10.dp,
            bottom = 10.dp
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        item(key = "history-anchor") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (chat.hasMoreNewer) {
                    TextButton(onClick = onLoadMore, enabled = !chat.isLoadingMore) {
                        Text(if (chat.isLoadingMore) "Загрузка…" else "Загрузить ещё сообщения")
                    }
                } else {
                    Text(
                        text = "Начало обсуждения",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        var previousDate: String? = null
        var previousAuthorId: Long? = null
        var previousType: ChatMessageType? = null
        visible.forEach { message ->
            val date = message.createdAt.datePart()
            val showDate = date.isNotBlank() && date != previousDate
            if (showDate) {
                item(key = "date-${message.id}") { DateDivider(date) }
            }
            val sameAuthor = previousType == ChatMessageType.USER &&
                message.type == ChatMessageType.USER &&
                message.author?.userId != null &&
                message.author?.userId == previousAuthorId &&
                !showDate
            item(key = message.id) {
                when (message.type) {
                    ChatMessageType.SYSTEM -> SystemEventItem(message)
                    ChatMessageType.USER -> UserMessageItem(
                        message = message,
                        isOwn = currentUserId != null && message.author?.userId == currentUserId,
                        grouped = sameAuthor,
                        downloadingAttachmentId = chat.downloadingAttachmentId,
                        onDownloadAttachment = onDownloadAttachment
                    )
                }
            }
            previousDate = date
            previousAuthorId = message.author?.userId
            previousType = message.type
        }
    }
}

@Composable
private fun DateDivider(date: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
        Text(
            text = date,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
    }
}

@Composable
private fun ChatEmptyState(filter: ChatMessageFilter, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(10.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            ErpIcon(
                glyph = ErpIconGlyph.Send,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = when (filter) {
                ChatMessageFilter.ALL -> "Сообщений пока нет"
                ChatMessageFilter.USER -> "Сообщений нет"
                ChatMessageFilter.SYSTEM -> "Событий нет"
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = when (filter) {
                ChatMessageFilter.ALL -> "Напишите первое сообщение: его увидят участники задачи."
                ChatMessageFilter.USER -> "Пока никто не писал в обсуждении."
                ChatMessageFilter.SYSTEM -> "Системные события появятся при изменении задачи."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun UserMessageItem(
    message: ChatMessageDto,
    isOwn: Boolean,
    grouped: Boolean,
    downloadingAttachmentId: Long?,
    onDownloadAttachment: (AttachmentDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val authorName = message.author?.fullName ?: "Неизвестный автор"
    val bubbleColor = if (isOwn) scheme.primary.copy(alpha = 0.10f)
    else scheme.surfaceContainerLow
    val borderColor = if (isOwn) scheme.primary.copy(alpha = 0.30f)
    else scheme.outlineVariant.copy(alpha = 0.45f)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Повторный аватар внутри серии сообщений не нужен: он уже показан выше.
        if (grouped) {
            Spacer(Modifier.width(30.dp))
        } else {
            Avatar(name = authorName, size = 30.dp)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (!grouped) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = authorName,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isOwn) scheme.primary else scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isOwn) {
                        Text(
                            text = "вы",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.primary.copy(alpha = 0.8f)
                        )
                    }
                    Text(
                        text = message.createdAt.toShortTime(),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .background(color = bubbleColor, shape = RoundedCornerShape(8.dp))
                    .border(1.dp, borderColor, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp)
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            message.attachments.forEach { attachment ->
                AttachmentRow(
                    attachment = attachment,
                    isDownloading = downloadingAttachmentId == attachment.id,
                    onDownload = { onDownloadAttachment(attachment) }
                )
            }
        }
    }
}

@Composable
private fun SystemEventItem(message: ChatMessageDto, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(
                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f),
                    shape = RoundedCornerShape(7.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            ErpIcon(
                glyph = ErpIconGlyph.Sparkle,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(11.dp),
                strokeWidth = 2.2.dp
            )
        }
        Text(
            text = message.content,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = message.createdAt.toShortTime(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        )
    }
}

// ---------------------------------------------------------------------------
// Ввод сообщения
// ---------------------------------------------------------------------------

@Composable
private fun ChatComposer(
    chat: ChatUiState,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickFile: () -> Unit,
    onClearFile: () -> Unit
) {
    val canSend = (chat.draft.isNotBlank() || chat.pendingFile != null) && !chat.isSending
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val pendingFile = chat.pendingFile
        if (pendingFile != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ErpIcon(
                    ErpIconGlyph.Documents,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Modifier.size(14.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = pendingFile.fileName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                ErpIcon(
                    ErpIconGlyph.Close,
                    MaterialTheme.colorScheme.onSurfaceVariant,
                    Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClearFile)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = chat.draft,
                onValueChange = onDraftChange,
                placeholder = { Text("Написать сообщение…") },
                minLines = 1,
                maxLines = 5,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f)
                ),
                modifier = Modifier.weight(1f)
            )
            SoftButton(
                text = "Файл",
                onClick = onPickFile,
                enabled = !chat.isSending,
                leadingIcon = ErpIconGlyph.Attachment
            )
            SoftButton(
                text = if (chat.isSending) "Отправка…" else "Отправить",
                onClick = onSend,
                enabled = canSend,
                leadingIcon = ErpIconGlyph.Send
            )
        }
        if (chat.draft.isNotEmpty()) {
            Text(
                text = "${chat.draft.length} симв.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }
    }
}

@Composable
private fun ReadOnlyNotice(modifier: Modifier = Modifier) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(6.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            ErpIcon(
                glyph = ErpIconGlyph.Clock,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp)
            )
        }
        Column {
            Text(
                text = "Только чтение",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Задача закрыта. Обсуждение сохранено, новые сообщения недоступны.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Разбор времени сообщения
//
// Модуля kotlinx-datetime в проекте нет, поэтому строки разбираются напрямую:
// время — это часть ISO-строки после `T`, дата — часть до `T`. Ничего не
// пересчитывается в другой часовой пояс и не подставляется: если формат
// неожиданный, показывается «—», а не правдоподобное время.
// ---------------------------------------------------------------------------

private fun String.toShortTime(): String {
    if (isBlank()) return "—"
    val time = substringAfter("T", "").take(5)
    return if (time.isBlank()) "—" else time
}

private fun String.datePart(): String = substringBefore("T")
