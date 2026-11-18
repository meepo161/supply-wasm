package org.example.project.ui.conversations

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import org.example.project.dto.ConversationMessageDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.tasks.toShortDateTime
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Avatar

fun formatAttachmentSize(sizeBytes: Long): String = when {
    sizeBytes >= 1024 * 1024 -> "${sizeBytes / (1024 * 1024)} МБ"
    sizeBytes >= 1024 -> "${sizeBytes / 1024} КБ"
    else -> "$sizeBytes Б"
}

/**
 * Панель сообщений общего/проектного/задачного чата в разделе «Чат».
 * При canWrite=false композитор заменяется подсказкой только для чтения.
 */
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
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onRefresh, enabled = !messages.isLoading) {
                Text("Обновить")
            }
        }

        messages.error?.let {
            ErrorMessageCard(
                error = it,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        if (messages.isLoading && messages.messages.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            val listState = rememberLazyListState()
            val headerOffset = if (messages.hasMoreNewer) 1 else 0
            val itemCount = headerOffset + messages.messages.size +
                if (messages.messages.isEmpty()) 1 else 0
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
                if (messages.hasMoreNewer) {
                    item(key = "load-more") {
                        Box(
                            modifier = Modifier.fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            TextButton(onClick = onLoadMore, enabled = !messages.isLoadingMore) {
                                Text(if (messages.isLoadingMore) "Загрузка…" else "Загрузить ещё сообщения")
                            }
                        }
                    }
                }
                items(messages.messages, key = { it.id }) { message ->
                    ConversationMessageItem(
                        message = message,
                        currentUserId = currentUserId,
                        downloadingAttachmentId = messages.downloadingAttachmentId,
                        onDownloadAttachment = onDownloadAttachment
                    )
                }
                if (messages.messages.isEmpty()) {
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
        }

        messages.sendError?.let {
            ErrorMessageCard(
                error = it,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        messages.downloadError?.let {
            ErrorMessageCard(
                error = it,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }

        if (!canWrite) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = readOnlyHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        } else {
            val pendingFile = messages.pendingFile
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
                    value = messages.draft,
                    onValueChange = onDraftChange,
                    label = { Text("Сообщение") },
                    minLines = 1,
                    maxLines = 4,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onPickFile, enabled = !messages.isSending) {
                    Text("Файл")
                }
                Button(
                    onClick = onSend,
                    enabled = (messages.draft.isNotBlank() || messages.pendingFile != null) && !messages.isSending
                ) {
                    Text(if (messages.isSending) "…" else "Отправить")
                }
            }
        }
    }
}

@Composable
private fun ConversationMessageItem(
    message: ConversationMessageDto,
    currentUserId: Long,
    downloadingAttachmentId: Long?,
    onDownloadAttachment: (AttachmentDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val isOwn = message.author?.userId == currentUserId
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (isOwn) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 620.dp),
            shape = MaterialTheme.shapes.medium,
            color = if (isOwn) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLowest
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(name = message.author?.fullName ?: "—", size = 24.dp)
                Text(
                    text = if (isOwn) "Вы" else message.author?.fullName ?: "—",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isOwn) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.primary
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
}

@Composable
internal fun AttachmentRow(
    attachment: AttachmentDto,
    isDownloading: Boolean,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ErpIcon(
            ErpIconGlyph.Documents,
            MaterialTheme.colorScheme.onSurfaceVariant,
            Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = formatAttachmentSize(attachment.sizeBytes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onDownload, enabled = !isDownloading) {
            Text(if (isDownloading) "Скачивание…" else "Скачать")
        }
    }
}
