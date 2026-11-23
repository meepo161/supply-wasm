package org.example.project.ui.attachments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import org.example.project.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.data.AttachmentRepository
import org.example.project.dto.AttachmentDto
import org.example.project.dto.AttachmentEntityType
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.tasks.toShortDateTime
import org.koin.compose.koinInject

/**
 * Секция вложений карточки задачи/проекта. Список и скачивание доступны
 * при чтении сущности; загрузка и удаление показываются только при праве
 * записи ([canWrite]) и скрываются для закрытой задачи ([readOnly]) либо
 * после отказа сервера (403).
 */
@Composable
fun AttachmentsSection(
    entityType: AttachmentEntityType,
    entityId: Long,
    canWrite: Boolean,
    readOnly: Boolean,
    modifier: Modifier = Modifier
) {
    val repository: AttachmentRepository = koinInject()
    val viewModel = remember(entityType, entityId) { AttachmentsViewModel(repository) }
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(entityType, entityId) {
        viewModel.load(entityType, entityId)
    }

    if (state.readDenied) return

    val showEdit = canWrite && !readOnly && !state.writeDenied

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Вложения",
                    style = MaterialTheme.typography.titleMedium
                )
                if (showEdit) {
                    TextButton(
                        onClick = viewModel::upload,
                        enabled = !state.isUploading
                    ) {
                        Text(if (state.isUploading) "Загрузка…" else "Прикрепить файл")
                    }
                }
            }

            state.error?.let {
                ErrorMessageCard(error = it)
            }

            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                state.items.isEmpty() -> Text(
                    text = "Вложений пока нет.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.items.forEach { attachment ->
                        AttachmentRow(
                            attachment = attachment,
                            busy = state.busyId == attachment.id,
                            canDelete = showEdit,
                            onDownload = { viewModel.download(attachment) },
                            onDelete = { viewModel.delete(attachment) }
                        )
                    }
                }
            }

            if (readOnly) {
                Text(
                    text = "Задача закрыта: изменение вложений недоступно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AttachmentRow(
    attachment: AttachmentDto,
    busy: Boolean,
    canDelete: Boolean,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = attachment.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    formatSize(attachment.sizeBytes),
                    attachment.uploadedByName ?: "ID ${attachment.uploadedBy}",
                    attachment.uploadedAt.toShortDateTime()
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onDownload, enabled = !busy) {
            Text("Скачать")
        }
        if (canDelete) {
            TextButton(onClick = onDelete, enabled = !busy) {
                Text("Удалить", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun formatSize(sizeBytes: Long): String = when {
    sizeBytes >= 1024 * 1024 -> "${sizeBytes / (1024 * 1024)} МБ"
    sizeBytes >= 1024 -> "${sizeBytes / 1024} КБ"
    else -> "$sizeBytes Б"
}
