package org.example.project.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.ApiError
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.DocumentDto
import org.example.project.dto.ProjectHistoryEntryDto
import org.example.project.dto.ProjectSummaryDto
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus
import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission
import org.example.project.ui.demand.localizedName as demandLocalizedName
import org.example.project.ui.documents.localizedName as documentLocalizedName
import org.example.project.ui.tasks.TaskStatusChip
import org.example.project.ui.tasks.toShortDate
import org.example.project.ui.tasks.toShortDateTime
import org.example.project.ui.tasks.userName
import org.example.project.ui.theme.EmptyState
import org.example.project.ui.theme.SectionHeader
import org.example.project.ui.theme.SoftButton

/**
 * Вкладки карточки проекта (M.4, PRJ-13/PRJ-14).
 *
 * Только пять вкладок, для которых есть реальные данные: обзор, задачи,
 * потребности, документы, история. Этапы, риски и проценты готовности не
 * показываются — их методика не утверждена (M.1/M.4).
 */
@Composable
fun ProjectTabsSection(
    state: ProjectsUiState,
    currentUser: UserDto,
    onTabSelected: (ProjectTab) -> Unit,
    onRefresh: () -> Unit,
    onTaskStatusFilter: (TaskStatus?) -> Unit,
    onTaskClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val project = state.selectedProject ?: return
    val canSeeTabs = project.ownerId == currentUser.id ||
        project.members.any { it.userId == currentUser.id } ||
        currentUser.hasPermission("projects", "read_any")
    if (!canSeeTabs) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScrollableTabRow(
            selectedTabIndex = ProjectTab.entries.indexOf(state.selectedTab),
            edgePadding = 0.dp
        ) {
            ProjectTab.entries.forEach { tab ->
                Tab(
                    selected = state.selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    text = { Text(tab.title) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.selectedTab.title,
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = onRefresh, enabled = !state.tabLoading) { Text("Обновить") }
        }

        if (state.tabLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().height(80.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        state.tabError?.let { TabErrorCard(it) }

        when (state.selectedTab) {
            ProjectTab.OVERVIEW -> OverviewTab(state.summary)
            ProjectTab.TASKS -> TasksTab(
                tasks = state.tasks,
                users = state.users,
                statusFilter = state.taskStatusFilter,
                onStatusFilter = onTaskStatusFilter,
                onTaskClick = onTaskClick
            )
            ProjectTab.DEMANDS -> DemandsTab(state.demands)
            ProjectTab.DOCUMENTS -> DocumentsTab(state.documents)
            ProjectTab.HISTORY -> HistoryTab(state.history, state.users)
        }
    }
}

@Composable
private fun TabErrorCard(error: ApiError, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            text = error.message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun OverviewTab(summary: ProjectSummaryDto?, modifier: Modifier = Modifier) {
    if (summary == null) {
        EmptyState(title = "Сводка недоступна", text = "Проверьте права на проект и обновите вкладку.")
        return
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Только реальные счётчики: процентов готовности здесь нет, потому
            // что методика их расчёта не утверждена.
            DetailLine("Задач всего", summary.taskCount.toString())
            DetailLine("В работе", summary.openTaskCount.toString())
            DetailLine("Завершено", summary.completedTaskCount.toString())
            DetailLine("Потребностей", summary.demandCount.toString())
            DetailLine("Документов", summary.documentCount.toString())
            DetailLine("Участников", summary.memberCount.toString())
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TasksTab(
    tasks: List<TaskDto>,
    users: List<UserDto>,
    statusFilter: TaskStatus?,
    onStatusFilter: (TaskStatus?) -> Unit,
    onTaskClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Фильтр уходит на сервер: список задач проекта не фильтруется на клиенте.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChipButton("Все", statusFilter == null) { onStatusFilter(null) }
            FilterChipButton("В работе", statusFilter == TaskStatus.IN_PROGRESS) {
                onStatusFilter(TaskStatus.IN_PROGRESS)
            }
            FilterChipButton("На проверке", statusFilter == TaskStatus.ON_REVIEW) {
                onStatusFilter(TaskStatus.ON_REVIEW)
            }
            FilterChipButton("Закрыты", statusFilter == TaskStatus.CLOSED) {
                onStatusFilter(TaskStatus.CLOSED)
            }
        }

        if (tasks.isEmpty()) {
            EmptyState(
                title = "Задач нет",
                text = "В проекте пока нет задач с выбранным статусом."
            )
        } else {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    tasks.forEachIndexed { index, task ->
                        if (index > 0) HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "${task.number} · ${task.title}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = listOf(
                                        task.assigneeId?.let { userName(users, it) } ?: "без исполнителя",
                                        "срок ${task.dueDate.toShortDate()}"
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TaskStatusChip(task.status)
                            TextButton(onClick = { onTaskClick(task.id) }) { Text("Открыть") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterChipButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        SoftButton(text = text, onClick = onClick, modifier = modifier)
    } else {
        TextButton(onClick = onClick, modifier = modifier) { Text(text) }
    }
}

@Composable
private fun DemandsTab(demands: List<DemandHeaderDto>, modifier: Modifier = Modifier) {
    if (demands.isEmpty()) {
        EmptyState(
            title = "Потребностей нет",
            text = "Потребности оформляются в разделе «Снабжение» с привязкой к проекту."
        )
        return
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            demands.forEachIndexed { index, demand ->
                if (index > 0) HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = demand.number, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "требуется к ${demand.requiredDate.toShortDate()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = demand.status.demandLocalizedName(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentsTab(documents: List<DocumentDto>, modifier: Modifier = Modifier) {
    if (documents.isEmpty()) {
        EmptyState(
            title = "Документов нет",
            text = "Документы связываются с проектом в разделе «Документы»."
        )
        return
    }
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            documents.forEachIndexed { index, document ->
                if (index > 0) HorizontalDivider()
                Column {
                    Text(
                        text = "${document.number} · ${document.name}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "${document.docType.documentLocalizedName()} · ${document.status.documentLocalizedName()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryTab(
    history: List<ProjectHistoryEntryDto>,
    users: List<UserDto>,
    modifier: Modifier = Modifier
) {
    if (history.isEmpty()) {
        EmptyState(
            title = "История пуста",
            text = "События появятся после создания, открытия и изменения проекта."
        )
        return
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionHeader(title = "События", count = history.size)
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                history.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider()
                    Column {
                        Text(text = entry.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = listOfNotNull(
                                entry.userName ?: entry.userId?.let { userName(users, it) },
                                entry.createdAt.toShortDateTime()
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
