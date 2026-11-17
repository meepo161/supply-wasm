package org.example.project.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.dto.AttachmentEntityType
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus
import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission
import org.example.project.ui.attachments.AttachmentsSection
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PageHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailScreen(
    task: TaskDto,
    currentUser: UserDto,
    state: TasksUiState,
    viewModel: TaskViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = "Задача ${task.number}",
            glyph = ErpIconGlyph.Tasks,
            gradient = Gradients.Ocean,
            subtitle = task.title,
            onBack = onBack,
            actions = {
                Text(
                    text = task.status.localizedName(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = task.status.accentColor(),
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
                        .background(task.status.accentColor().copy(alpha = 0.12f))
                        .padding(horizontal = 12.dp, vertical = 5.dp)
                )
            }
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWide = maxWidth >= 840.dp
            if (isWide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        TaskDescription(task, currentUser, state, viewModel)
                    }
                    VerticalDivider()
                    TaskChatPanel(
                        chat = state.chat,
                        readOnly = task.isClosedLike(),
                        onDraftChange = viewModel::updateChatDraft,
                        onSend = viewModel::sendMessage,
                        onRefresh = { viewModel.refreshChat(manual = true) },
                        onLoadMore = viewModel::loadMoreNewer,
                        onFilterChange = viewModel::setChatFilter,
                        onPickFile = viewModel::pickChatFile,
                        onClearFile = viewModel::clearChatPendingFile,
                        onDownloadAttachment = viewModel::downloadChatAttachment,
                        modifier = Modifier.width(420.dp).fillMaxHeight()
                    )
                }
            } else {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                Column(modifier = Modifier.fillMaxSize()) {
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            text = { Text("Описание") }
                        )
                        Tab(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            text = { Text("Обсуждение") }
                        )
                    }
                    when (tab) {
                        0 -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            TaskDescription(task, currentUser, state, viewModel)
                        }
                        else -> TaskChatPanel(
                            chat = state.chat,
                            readOnly = task.isClosedLike(),
                            onDraftChange = viewModel::updateChatDraft,
                            onSend = viewModel::sendMessage,
                            onRefresh = { viewModel.refreshChat(manual = true) },
                            onLoadMore = viewModel::loadMoreNewer,
                            onFilterChange = viewModel::setChatFilter,
                            onPickFile = viewModel::pickChatFile,
                            onClearFile = viewModel::clearChatPendingFile,
                            onDownloadAttachment = viewModel::downloadChatAttachment,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

/** Desktop task workspace реализован в TaskChatWorkspace.kt (D-51). */

@Composable
private fun TaskDescription(
    task: TaskDto,
    currentUser: UserDto,
    state: TasksUiState,
    viewModel: TaskViewModel
) {
    if (state.detailLoading) {
        Box(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator()
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = task.title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        TaskStatusChip(status = task.status)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TaskDetailRow("Область", task.area.localizedName())
            task.projectId?.let { projectId ->
                val projectName = state.projects.find { it.id == projectId }
                    ?.let { "${it.number} ${it.name}" } ?: "ID $projectId"
                TaskDetailRow("Проект", projectName)
            }
            task.departmentId?.let { TaskDetailRow("Подразделение", "ID $it") }
            TaskDetailRow("Тип работы", task.type.localizedName())
            TaskDetailRow("Приоритет", task.priority.localizedName())
            TaskDetailRow("Инициатор", userName(state.users, task.initiatorId))
            TaskDetailRow("Исполнитель", task.assigneeId?.let { userName(state.users, it) } ?: "—")
            if (task.coexecutorIds.isNotEmpty()) {
                TaskDetailRow(
                    "Соисполнители",
                    task.coexecutorIds.joinToString(", ") { userName(state.users, it) }
                )
            }
            if (task.observerIds.isNotEmpty()) {
                TaskDetailRow(
                    "Наблюдатели",
                    task.observerIds.joinToString(", ") { userName(state.users, it) }
                )
            }
            task.reviewerId?.let { TaskDetailRow("Проверяющий", userName(state.users, it)) }
            TaskDetailRow("Плановое начало", task.plannedStartAt.toShortDate())
            TaskDetailRow("Срок выполнения", task.dueDate.toShortDate())
            TaskDetailRow("Создана", task.createdAt.toShortDateTime())
            task.closedAt?.let { TaskDetailRow("Завершена", it.toShortDateTime()) }
            if (task.status == TaskStatus.BLOCKED) {
                task.blockedReason?.let { TaskDetailRow("Причина блокировки", it) }
                task.blockedBy?.let { TaskDetailRow("Заблокировал", userName(state.users, it)) }
                task.blockedAt?.let { TaskDetailRow("Время блокировки", it.toShortDateTime()) }
                task.unblockResponsibleId?.let {
                    TaskDetailRow("Ответственный за снятие", userName(state.users, it))
                }
            }
            TaskDetailRow("Версия", task.version.toString())
        }
    }

    task.description?.takeIf { it.isNotBlank() }?.let {
        TaskTextSection(title = "Описание", text = it)
    }
    task.acceptanceCriteria?.takeIf { it.isNotBlank() }?.let {
        TaskTextSection(title = "Критерии приёмки", text = it)
    }
    task.result?.takeIf { it.isNotBlank() }?.let {
        TaskTextSection(title = "Результат выполнения", text = it)
    }

    AttachmentsSection(
        entityType = AttachmentEntityType.TASK,
        entityId = task.id,
        canWrite = currentUser.hasPermission("attachments", "manage") ||
            task.initiatorId == currentUser.id ||
            task.assigneeId == currentUser.id ||
            task.reviewerId == currentUser.id ||
            task.coexecutorIds.contains(currentUser.id),
        readOnly = task.isClosedLike()
    )

    TaskActionsSection(
        task = task,
        currentUser = currentUser,
        users = state.users,
        state = state,
        viewModel = viewModel
    )

    HorizontalDivider()
}

@Composable
private fun TaskTextSection(title: String, text: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun TaskDetailRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
