package org.example.project.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.example.project.dto.AttachmentDto
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.ChatMessageType
import org.example.project.ui.conversations.AttachmentRow
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph

@Composable
fun TaskChatPanel(
    chat: ChatUiState,
    readOnly: Boolean,
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
    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Обсуждение",
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = onRefresh, enabled = !chat.isLoading) {
                Text("Обновить")
            }
        }

        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChatMessageFilter.entries.forEach { filter ->
                FilterChip(
                    selected = chat.filter == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(filter.label) }
                )
            }
        }

        chat.error?.let {
            ErrorMessageCard(
                error = it,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
            val visible = chat.messages.filter { message ->
                when (chat.filter) {
                    ChatMessageFilter.ALL -> true
                    ChatMessageFilter.USER -> message.type == ChatMessageType.USER
                    ChatMessageFilter.SYSTEM -> message.type == ChatMessageType.SYSTEM
                }
            }
            val listState = rememberLazyListState()
            val headerOffset = if (chat.hasMoreNewer) 1 else 0
            val itemCount = headerOffset + visible.size + if (visible.isEmpty()) 1 else 0
            LaunchedEffect(itemCount) {
                if (itemCount > 0) {
                    listState.scrollToItem(itemCount - 1)
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().weight(1f).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (chat.hasMoreNewer) {
                    item(key = "load-more") {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            TextButton(onClick = onLoadMore, enabled = !chat.isLoadingMore) {
                                Text(if (chat.isLoadingMore) "Загрузка…" else "Загрузить ещё сообщения")
                            }
                        }
                    }
                }
                items(visible, key = { it.id }) { message ->
                    when (message.type) {
                        ChatMessageType.SYSTEM -> SystemEventItem(message)
                        ChatMessageType.USER -> UserMessageItem(
                            message = message,
                            downloadingAttachmentId = chat.downloadingAttachmentId,
                            onDownloadAttachment = onDownloadAttachment
                        )
                    }
                }
                if (visible.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = "Сообщений пока нет.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }

        chat.sendError?.let {
            ErrorMessageCard(
                error = it,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        chat.downloadError?.let {
            ErrorMessageCard(
                error = it,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        if (readOnly) {
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
            val pendingFile = chat.pendingFile
            if (pendingFile != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
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
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = chat.draft,
                    onValueChange = onDraftChange,
                    label = { Text("Сообщение") },
                    minLines = 1,
                    maxLines = 4,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onPickFile, enabled = !chat.isSending) {
                    Text("Файл")
                }
                Button(
                    onClick = onSend,
                    enabled = (chat.draft.isNotBlank() || chat.pendingFile != null) && !chat.isSending
                ) {
                    Text(if (chat.isSending) "…" else "Отправить")
                }
            }
        }
    }
}

@Composable
private fun UserMessageItem(
    message: ChatMessageDto,
    downloadingAttachmentId: Long?,
    onDownloadAttachment: (AttachmentDto) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(max = 480.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLowest
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.author?.fullName ?: "—",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = message.createdAt.toShortDateTime(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
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
