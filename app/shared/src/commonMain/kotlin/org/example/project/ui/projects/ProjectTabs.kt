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
import org.example.project.dto.ProjectMilestoneDto
import org.example.project.dto.ProjectRiskCategory
import org.example.project.dto.ProjectRiskCriticality
import org.example.project.dto.ProjectRiskDto
import org.example.project.dto.ProjectRiskLevel
import org.example.project.dto.ProjectRiskStatus
import org.example.project.dto.ProjectStageDto
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
/**
 * Колбэки действий плана. Передаются одним объектом: иначе сигнатура секции
 * разрастается на полтора десятка параметров, и вызывающий код становится
 * нечитаемым.
 */
data class ProjectPlanActions(
    val onCreateStage: (String, String?, String?) -> Unit = { _, _, _ -> },
    val onStartStage: (ProjectStageDto) -> Unit = {},
    val onCompleteStage: (ProjectStageDto) -> Unit = {},
    val onCancelStage: (ProjectStageDto, String) -> Unit = { _, _ -> },
    val onDeleteStage: (ProjectStageDto) -> Unit = {},
    val onCreateMilestone: (String, Long?, String?) -> Unit = { _, _, _ -> },
    val onReachMilestone: (ProjectMilestoneDto) -> Unit = {},
    val onMissMilestone: (ProjectMilestoneDto, String) -> Unit = { _, _ -> },
    val onCreateRisk: (
        title: String,
        description: String?,
        category: ProjectRiskCategory,
        probability: ProjectRiskLevel,
        impact: ProjectRiskLevel,
        criticality: ProjectRiskCriticality,
        responsePlan: String?,
        dueDate: String?,
        stageId: Long?,
        taskId: Long?
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _ -> },
    val onMitigateRisk: (ProjectRiskDto) -> Unit = {},
    val onReopenRisk: (ProjectRiskDto) -> Unit = {},
    val onCloseRisk: (ProjectRiskDto, String, Boolean) -> Unit = { _, _, _ -> },
    val onDeleteRisk: (ProjectRiskDto) -> Unit = {},
    val onDismissError: () -> Unit = {}
)

@Composable
fun ProjectTabsSection(
    state: ProjectsUiState,
    currentUser: UserDto,
    onTabSelected: (ProjectTab) -> Unit,
    onRefresh: () -> Unit,
    onTaskStatusFilter: (TaskStatus?) -> Unit,
    onTaskClick: (Long) -> Unit,
    canManagePlan: Boolean,
    onRiskFilter: (ProjectRiskStatus?) -> Unit,
    planActions: ProjectPlanActions,
    modifier: Modifier = Modifier
) {
    val project = state.selectedProject ?: return
    // Вкладки «Обзор», «Задачи» и «Обсуждение» рисует ProjectDetailScreen;
    // здесь остаётся только содержимое вкладок плана проекта.
    if (state.selectedTab != ProjectTab.STAGES &&
        state.selectedTab != ProjectTab.RISKS &&
        state.selectedTab != ProjectTab.DEMANDS &&
        state.selectedTab != ProjectTab.DOCUMENTS &&
        state.selectedTab != ProjectTab.HISTORY
    ) return
    val canSeeTabs = project.ownerId == currentUser.id ||
        project.members.any { it.userId == currentUser.id } ||
        currentUser.hasPermission("projects", "read_any")
    if (!canSeeTabs) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        state.planError?.let { TabErrorCard(it, onDismiss = planActions.onDismissError) }

        when (state.selectedTab) {
            ProjectTab.OVERVIEW -> OverviewTab(state.summary, state.planSummary)
            ProjectTab.STAGES -> StagesTab(
                stages = state.stages,
                canManage = canManagePlan,
                busy = state.planBusy,
                onCreate = planActions.onCreateStage,
                onStart = planActions.onStartStage,
                onComplete = planActions.onCompleteStage,
                onCancel = planActions.onCancelStage,
                onDelete = planActions.onDeleteStage,
                onCreateMilestone = planActions.onCreateMilestone,
                onReachMilestone = planActions.onReachMilestone,
                onMissMilestone = planActions.onMissMilestone
            )
            ProjectTab.RISKS -> RisksTab(
                risks = state.risks,
                stages = state.stages,
                tasks = state.tasks,
                statusFilter = state.riskStatusFilter,
                canManage = canManagePlan,
                busy = state.planBusy,
                onFilter = onRiskFilter,
                onCreate = planActions.onCreateRisk,
                onMitigate = planActions.onMitigateRisk,
                onReopen = planActions.onReopenRisk,
                onClose = planActions.onCloseRisk,
                onDelete = planActions.onDeleteRisk
            )
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
private fun TabErrorCard(
    error: ApiError,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            if (onDismiss != null) {
                TextButton(onClick = onDismiss) { Text("Скрыть") }
            }
        }
    }
}

@Composable
internal fun OverviewTab(
    summary: ProjectSummaryDto?,
    planSummary: org.example.project.dto.ProjectPlanSummaryDto?,
    modifier: Modifier = Modifier
) {
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
            DetailLine("Завершено или отменено", summary.completedTaskCount.toString())
            DetailLine("Потребностей", summary.demandCount.toString())
            DetailLine("Документов", summary.documentCount.toString())
            DetailLine("Участников", summary.memberCount.toString())
            // Показатели плана — реальные значения; процентов здесь нет.
            planSummary?.let { plan ->
                HorizontalDivider()
                DetailLine(
                    "Этапов завершено",
                    "${plan.completedStageCount} из ${plan.stageCount}"
                )
                plan.currentStageName?.let { DetailLine("Текущий этап", it) }
                DetailLine(
                    "Контрольных точек достигнуто",
                    "${plan.reachedMilestoneCount} из ${plan.milestoneCount}"
                )
                plan.nextMilestoneName?.let {
                    DetailLine(
                        "Ближайшая точка",
                        "$it · ${plan.nextMilestonePlannedAt.toShortDate()}"
                    )
                }
                DetailLine("Открытых рисков", plan.openRiskCount.toString())
                if (plan.criticalRiskCount > 0) {
                    DetailLine("Критичных рисков", plan.criticalRiskCount.toString())
                }
            }
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
            FilterChipButton("Все", statusFilter == null, onClick = { onStatusFilter(null) })
            FilterChipButton("В работе", statusFilter == TaskStatus.IN_PROGRESS, onClick = {
                onStatusFilter(TaskStatus.IN_PROGRESS)
            })
            FilterChipButton("На проверке", statusFilter == TaskStatus.ON_REVIEW, onClick = {
                onStatusFilter(TaskStatus.ON_REVIEW)
            })
            FilterChipButton("Закрыты", statusFilter == TaskStatus.CLOSED, onClick = {
                onStatusFilter(TaskStatus.CLOSED)
            })
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
internal fun FilterChipButton(
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
