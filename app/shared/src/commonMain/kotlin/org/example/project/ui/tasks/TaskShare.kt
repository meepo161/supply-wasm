package org.example.project.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import org.example.project.ui.theme.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import org.example.project.ui.theme.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.dto.TaskDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Avatar
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph

private const val TASK_LINK_PREFIX = "erp://tasks/"

internal fun taskShareContent(task: TaskDto): String =
    "Задача ${task.number}: ${task.title}\n$TASK_LINK_PREFIX${task.id}"

internal data class TaskMessageLink(val title: String, val taskId: Long)

internal fun parseTaskMessageLink(content: String): TaskMessageLink? {
    val lines = content.lines()
    val linkIndex = lines.indexOfLast { it.trim().startsWith(TASK_LINK_PREFIX) }
    if (linkIndex < 0) return null
    val taskId = lines[linkIndex].trim().removePrefix(TASK_LINK_PREFIX).toLongOrNull()
        ?.takeIf { it > 0 } ?: return null
    val title = lines.filterIndexed { index, _ -> index != linkIndex }.joinToString("\n").trim()
    return TaskMessageLink(title.ifBlank { "Задача" }, taskId)
}

@Composable
internal fun TaskShareAction(
    task: TaskDto,
    state: TasksUiState,
    viewModel: TaskViewModel,
    modifier: Modifier = Modifier
) {
    var dialogOpen by remember(task.id) { mutableStateOf(false) }
    TextButton(
        onClick = {
            viewModel.beginTaskShare()
            dialogOpen = true
        },
        modifier = modifier
    ) {
        ErpIcon(ErpIconGlyph.Send, modifier = Modifier.size(16.dp))
        Text("Переслать", modifier = Modifier.padding(start = 6.dp))
    }
    if (dialogOpen) {
        TaskShareDialog(
            task = task,
            state = state,
            viewModel = viewModel,
            onDismiss = {
                dialogOpen = false
                viewModel.finishTaskShare()
            }
        )
    }
}

@Composable
private fun TaskShareDialog(
    task: TaskDto,
    state: TasksUiState,
    viewModel: TaskViewModel,
    onDismiss: () -> Unit
) {
    var query by remember(task.id) { mutableStateOf("") }
    var selectedUserId by remember(task.id) { mutableStateOf<Long?>(null) }
    val sent = state.sharedTaskId == task.id
    val recipients = state.shareUsers.filter {
        query.isBlank() || it.fullName.contains(query.trim(), ignoreCase = true)
    }

    org.example.project.ui.theme.ErpDialog(
        title = "Отправить задачу в личные сообщения",
        glyph = org.example.project.ui.theme.ErpIconGlyph.Send,
        gradient = org.example.project.ui.theme.Gradients.Primary,
        width = 580.dp,
        confirmText = if (sent) "Готово" else if (state.isSharingTask) "Отправка…" else "Отправить",
        onConfirm = {
            if (sent) {
                onDismiss()
            } else {
                selectedUserId?.let { viewModel.shareTask(task, it) }
            }
        },
        confirmEnabled = sent || (selectedUserId != null && !state.isSharingTask),
        onDismiss = { if (!state.isSharingTask) onDismiss() }
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(task.number, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(task.title, style = MaterialTheme.typography.titleSmall)
            }
        }
        if (sent) {
            Text(
                "Задача отправлена. Получатель сможет открыть её по ссылке в сообщении.",
                color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Сотрудник") },
                        singleLine = true,
                        enabled = !state.isSharingTask,
                        modifier = Modifier.fillMaxWidth()
                    )
                    state.shareUsersError?.let {
                        ErrorMessageCard(it)
                        TextButton(onClick = viewModel::loadShareUsers) { Text("Повторить загрузку") }
                    }
                    when {
                        state.shareUsersLoading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        recipients.isEmpty() -> Text(
                            if (state.shareUsers.isEmpty()) "Нет доступных сотрудников." else "Сотрудники не найдены.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> LazyColumn(
                            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(recipients, key = { it.id }) { user ->
                                val selected = selectedUserId == user.id
                                Surface(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).clickable(
                                        enabled = !state.isSharingTask
                                    ) { selectedUserId = user.id },
                                    color = if (selected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLowest
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Avatar(user.fullName, size = 30.dp)
                                        Text(
                                            user.fullName,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (selected) ErpIcon(ErpIconGlyph.CheckCircle, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                    state.shareError?.let { ErrorMessageCard(it) }
                }
            }
    }


@Composable
internal fun SharedTaskMessageCard(
    link: TaskMessageLink,
    onClick: () -> Unit,
    contentColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = contentColor.copy(alpha = 0.08f)
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(link.title, color = contentColor, style = MaterialTheme.typography.bodyMedium)
            Text(
                "Открыть задачу →",
                color = contentColor,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
