package org.example.project.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.ProjectMilestoneDto
import org.example.project.dto.ProjectMilestoneStatus
import org.example.project.dto.ProjectRiskCategory
import org.example.project.dto.ProjectRiskCriticality
import org.example.project.dto.ProjectRiskDto
import org.example.project.dto.ProjectRiskLevel
import org.example.project.dto.ProjectRiskStatus
import org.example.project.dto.ProjectStageDto
import org.example.project.dto.ProjectStageStatus
import org.example.project.dto.TaskDto
import org.example.project.ui.tasks.toShortDate
import org.example.project.ui.tasks.toShortDateTime
import org.example.project.ui.theme.EmptyState
import org.example.project.ui.theme.ErpDialog
import org.example.project.ui.theme.ErpTextField
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SectionHeader
import org.example.project.ui.theme.SoftButton

/**
 * Вкладки плана проекта: «Этапы» (PRJ-08) и «Риски» (PRJ-10).
 *
 * Проценты выполнения не показываются: методика не утверждена (M.4), поэтому
 * виден только статус этапа и реальные даты. Действия доступны только тем, кому
 * сервер их разрешит: владельцу проекта или держателю `projects:manage_plan`;
 * остальные видят план без кнопок.
 */

// ---------------------------------------------------------------------------
// Этапы
// ---------------------------------------------------------------------------

@Composable
fun StagesTab(
    stages: List<ProjectStageDto>,
    canManage: Boolean,
    busy: Boolean,
    onCreate: (name: String, plannedStartAt: String?, plannedEndAt: String?) -> Unit,
    onStart: (ProjectStageDto) -> Unit,
    onComplete: (ProjectStageDto) -> Unit,
    onCancel: (ProjectStageDto, String) -> Unit,
    onDelete: (ProjectStageDto) -> Unit,
    onCreateMilestone: (name: String, stageId: Long?, plannedAt: String?) -> Unit,
    onReachMilestone: (ProjectMilestoneDto) -> Unit,
    onMissMilestone: (ProjectMilestoneDto, String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreate by remember { mutableStateOf(false) }
    var showCreateMilestone by remember { mutableStateOf(false) }
    var cancelTarget by remember { mutableStateOf<ProjectStageDto?>(null) }
    var missTarget by remember { mutableStateOf<ProjectMilestoneDto?>(null) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeader(title = "Этапы", count = stages.size)
            if (canManage) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SoftButton(text = "Добавить этап", onClick = { showCreate = true }, enabled = !busy)
                    SoftButton(
                        text = "Добавить точку",
                        onClick = { showCreateMilestone = true },
                        enabled = !busy
                    )
                }
            }
        }

        if (stages.isEmpty()) {
            EmptyState(
                title = "Этапов нет",
                text = if (canManage) {
                    "Разбейте проект на этапы: у каждого свои сроки и ответственный."
                } else {
                    "План проекта ещё не составлен."
                }
            )
        } else {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
                )
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    stages.forEachIndexed { index, stage ->
                        if (index > 0) HorizontalDivider()
                        StageRow(
                            stage = stage,
                            canManage = canManage,
                            busy = busy,
                            onStart = { onStart(stage) },
                            onComplete = { onComplete(stage) },
                            onCancel = { cancelTarget = stage },
                            onDelete = { onDelete(stage) }
                        )
                        // Контрольные точки этапа (PRJ-09) — только реальные записи.
                        stage.milestones.forEach { milestone ->
                            MilestoneRow(
                                milestone = milestone,
                                canManage = canManage && stage.status != ProjectStageStatus.CANCELLED,
                                onReach = { onReachMilestone(milestone) },
                                onMiss = { missTarget = milestone }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreate) {
        CreateStageDialog(
            onDismiss = { showCreate = false },
            onCreate = { name, start, end ->
                showCreate = false
                onCreate(name, start, end)
            }
        )
    }

    cancelTarget?.let { stage ->
        CancelStageDialog(
            stage = stage,
            onDismiss = { cancelTarget = null },
            onConfirm = { reason ->
                cancelTarget = null
                onCancel(stage, reason)
            }
        )
    }

    if (showCreateMilestone) {
        CreateMilestoneDialog(
            stages = stages,
            onDismiss = { showCreateMilestone = false },
            onCreate = { name, stageId, plannedAt ->
                showCreateMilestone = false
                onCreateMilestone(name, stageId, plannedAt)
            }
        )
    }

    missTarget?.let { milestone ->
        MissMilestoneDialog(
            milestone = milestone,
            onDismiss = { missTarget = null },
            onConfirm = { note ->
                missTarget = null
                onMissMilestone(milestone, note)
            }
        )
    }
}

@Composable
private fun CreateMilestoneDialog(
    stages: List<ProjectStageDto>,
    onDismiss: () -> Unit,
    onCreate: (String, Long?, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var plannedAt by remember { mutableStateOf("") }
    var stageId by remember { mutableStateOf<Long?>(null) }
    ErpDialog(
        title = "Новая контрольная точка",
        subtitle = "Можно привязать к этапу или к проекту целиком",
        onDismiss = onDismiss,
        confirmText = "Создать",
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onCreate(name, stageId, plannedAt.takeIf { it.isNotBlank() }) }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
            ErpTextField(value = name, onValueChange = { name = it }, label = "Название *")
            ErpTextField(
                value = plannedAt,
                onValueChange = { plannedAt = it },
                label = "Плановая дата",
                placeholder = "2026-12-01T10:00:00Z"
            )
            if (stages.isNotEmpty()) {
                EnumRow(
                    label = "Этап",
                    values = buildList {
                        add(null to "Весь проект")
                        addAll(stages.map { it.id to "${it.sequence}. ${it.name}" })
                    },
                    selected = stageId,
                    onSelect = { stageId = it }
                )
            }
        }
    }
}

@Composable
private fun MissMilestoneDialog(
    milestone: ProjectMilestoneDto,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var note by remember { mutableStateOf("") }
    ErpDialog(
        title = "Контрольная точка не достигнута",
        subtitle = milestone.name,
        onDismiss = onDismiss,
        confirmText = "Отметить",
        onConfirm = { onConfirm(note) }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Комментарий необязателен, но помогает понять причину.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ErpTextField(
                value = note,
                onValueChange = { note = it },
                label = "Комментарий",
                singleLine = false,
                minLines = 2
            )
        }
    }
}

@Composable
private fun StageRow(
    stage: ProjectStageDto,
    canManage: Boolean,
    busy: Boolean,
    onStart: () -> Unit,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${stage.sequence}. ${stage.name}",
                style = MaterialTheme.typography.bodyMedium
            )
            StageStatusChip(stage.status)
        }
        Text(
            text = buildList {
                add("план ${stage.plannedStartAt.toShortDate()} — ${stage.plannedEndAt.toShortDate()}")
                if (stage.actualStartAt != null) add("начат ${stage.actualStartAt.toShortDate()}")
                if (stage.actualEndAt != null) add("завершён ${stage.actualEndAt.toShortDate()}")
            }.joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        stage.completionCriteria?.let {
            Text(
                text = "Критерий завершения: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        stage.cancelReason?.let {
            Text(
                text = "Причина отмены: $it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (canManage) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                when (stage.status) {
                    ProjectStageStatus.PLANNED -> {
                        TextButton(onClick = onStart, enabled = !busy) { Text("Начать") }
                        TextButton(onClick = onCancel, enabled = !busy) { Text("Отменить") }
                        TextButton(onClick = onDelete, enabled = !busy) { Text("Удалить") }
                    }
                    ProjectStageStatus.IN_PROGRESS -> {
                        TextButton(onClick = onComplete, enabled = !busy) { Text("Завершить") }
                        TextButton(onClick = onCancel, enabled = !busy) { Text("Отменить") }
                    }
                    // Завершённый и отменённый этап — факт, действия недоступны.
                    ProjectStageStatus.COMPLETED, ProjectStageStatus.CANCELLED -> Unit
                }
            }
        }
    }
}

@Composable
private fun MilestoneRow(
    milestone: ProjectMilestoneDto,
    canManage: Boolean,
    onReach: () -> Unit,
    onMiss: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "◆ ${milestone.name}", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = buildList {
                        add("план ${milestone.plannedAt.toShortDate()}")
                        if (milestone.actualAt != null) add("факт ${milestone.actualAt.toShortDate()}")
                        milestone.note?.let { add(it) }
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            PillBadge(
                text = milestone.status.localizedName(),
                accent = milestone.status.accentColor()
            )
        }
        // Отметка возможна только у ожидаемой точки: достигнутую нельзя переписать.
        if (canManage && milestone.status == ProjectMilestoneStatus.PLANNED) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onReach) { Text("Достигнута") }
                TextButton(onClick = onMiss) { Text("Пропущена") }
            }
        }
    }
}

@Composable
private fun StageStatusChip(status: ProjectStageStatus) {
    PillBadge(text = status.localizedName(), accent = status.accentColor())
}

private fun ProjectStageStatus.localizedName(): String = when (this) {
    ProjectStageStatus.PLANNED -> "Запланирован"
    ProjectStageStatus.IN_PROGRESS -> "В работе"
    ProjectStageStatus.COMPLETED -> "Завершён"
    ProjectStageStatus.CANCELLED -> "Отменён"
}

@Composable
private fun ProjectStageStatus.accentColor() = when (this) {
    ProjectStageStatus.PLANNED -> MaterialTheme.colorScheme.primary
    ProjectStageStatus.IN_PROGRESS -> MaterialTheme.colorScheme.tertiary
    ProjectStageStatus.COMPLETED -> MaterialTheme.colorScheme.secondary
    ProjectStageStatus.CANCELLED -> MaterialTheme.colorScheme.error
}

private fun ProjectMilestoneStatus.localizedName(): String = when (this) {
    ProjectMilestoneStatus.PLANNED -> "Ожидается"
    ProjectMilestoneStatus.REACHED -> "Достигнута"
    ProjectMilestoneStatus.MISSED -> "Пропущена"
    ProjectMilestoneStatus.CANCELLED -> "Отменена"
}

@Composable
private fun ProjectMilestoneStatus.accentColor() = when (this) {
    ProjectMilestoneStatus.PLANNED -> MaterialTheme.colorScheme.primary
    ProjectMilestoneStatus.REACHED -> MaterialTheme.colorScheme.secondary
    ProjectMilestoneStatus.MISSED -> MaterialTheme.colorScheme.error
    ProjectMilestoneStatus.CANCELLED -> MaterialTheme.colorScheme.outline
}

// ---------------------------------------------------------------------------
// Риски
// ---------------------------------------------------------------------------

@Composable
fun RisksTab(
    risks: List<ProjectRiskDto>,
    stages: List<ProjectStageDto>,
    tasks: List<TaskDto>,
    statusFilter: ProjectRiskStatus?,
    canManage: Boolean,
    busy: Boolean,
    onFilter: (ProjectRiskStatus?) -> Unit,
    onCreate: (
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
    ) -> Unit,
    onMitigate: (ProjectRiskDto) -> Unit,
    onReopen: (ProjectRiskDto) -> Unit,
    onClose: (ProjectRiskDto, String, Boolean) -> Unit,
    onDelete: (ProjectRiskDto) -> Unit,
    modifier: Modifier = Modifier
) {
    var showCreate by remember { mutableStateOf(false) }
    var closeTarget by remember { mutableStateOf<ProjectRiskDto?>(null) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SectionHeader(title = "Риски", count = risks.size)
            if (canManage) {
                SoftButton(text = "Добавить риск", onClick = { showCreate = true }, enabled = !busy)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // «Открытые» — это риски без закрытия: сервер отдаёт все, фильтр
            // по двум статусам сразу делается запросом на каждый.
            FilterChipButton("Все", statusFilter == null, onClick = { onFilter(null) })
            FilterChipButton("В работе", statusFilter == ProjectRiskStatus.MITIGATING, onClick = {
                onFilter(ProjectRiskStatus.MITIGATING)
            })
            FilterChipButton("Закрытые", statusFilter == ProjectRiskStatus.CLOSED, onClick = {
                onFilter(ProjectRiskStatus.CLOSED)
            })
            FilterChipButton("Принятые", statusFilter == ProjectRiskStatus.ACCEPTED, onClick = {
                onFilter(ProjectRiskStatus.ACCEPTED)
            })
        }

        if (risks.isEmpty()) {
            EmptyState(
                title = "Рисков нет",
                text = if (statusFilter == null) {
                    "По проекту не зафиксировано ни одного риска."
                } else {
                    "Рисков с выбранным статусом нет."
                }
            )
        } else {
            risks.forEach { risk ->
                RiskCard(
                    risk = risk,
                    canManage = canManage,
                    busy = busy,
                    onMitigate = { onMitigate(risk) },
                    onReopen = { onReopen(risk) },
                    onClose = { closeTarget = risk },
                    onDelete = { onDelete(risk) }
                )
            }
        }
    }

    if (showCreate) {
        CreateRiskDialog(
            stages = stages,
            tasks = tasks,
            onDismiss = { showCreate = false },
            onCreate = { title, description, category, probability, impact, criticality, plan, due, stageId, taskId ->
                showCreate = false
                onCreate(
                    title, description, category, probability, impact, criticality,
                    plan, due, stageId, taskId
                )
            }
        )
    }

    closeTarget?.let { risk ->
        CloseRiskDialog(
            risk = risk,
            onDismiss = { closeTarget = null },
            onConfirm = { outcome, accepted ->
                closeTarget = null
                onClose(risk, outcome, accepted)
            }
        )
    }
}

@Composable
private fun RiskCard(
    risk: ProjectRiskDto,
    canManage: Boolean,
    busy: Boolean,
    onMitigate: () -> Unit,
    onReopen: () -> Unit,
    onClose: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = risk.title, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    PillBadge(
                        text = risk.criticality.localizedName(),
                        accent = risk.criticality.accentColor(),
                        filled = risk.criticality == ProjectRiskCriticality.CRITICAL
                    )
                    PillBadge(text = risk.status.localizedName(), accent = MaterialTheme.colorScheme.outline)
                }
            }
            Text(
                text = listOf(
                    risk.category.localizedName(),
                    "вероятность ${risk.probability.localizedName()}",
                    "влияние ${risk.impact.localizedName()}",
                    risk.dueDate?.let { "срок ${it.toShortDate()}" }
                ).filterNotNull().joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            risk.responsePlan?.let {
                Text(
                    text = "План реагирования: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            risk.outcome?.let {
                Text(
                    text = "Исход: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            risk.closedAt?.let {
                Text(
                    text = "Закрыт ${it.toShortDateTime()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (canManage) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (risk.status) {
                        ProjectRiskStatus.OPEN -> {
                            TextButton(onClick = onMitigate, enabled = !busy) { Text("В работу") }
                            TextButton(onClick = onClose, enabled = !busy) { Text("Закрыть") }
                            TextButton(onClick = onDelete, enabled = !busy) { Text("Удалить") }
                        }
                        ProjectRiskStatus.MITIGATING -> {
                            TextButton(onClick = onClose, enabled = !busy) { Text("Закрыть") }
                            TextButton(onClick = onDelete, enabled = !busy) { Text("Удалить") }
                        }
                        ProjectRiskStatus.CLOSED, ProjectRiskStatus.ACCEPTED -> {
                            TextButton(onClick = onReopen, enabled = !busy) { Text("Переоткрыть") }
                        }
                    }
                }
            }
        }
    }
}

private fun ProjectRiskStatus.localizedName(): String = when (this) {
    ProjectRiskStatus.OPEN -> "Открыт"
    ProjectRiskStatus.MITIGATING -> "В работе"
    ProjectRiskStatus.CLOSED -> "Закрыт"
    ProjectRiskStatus.ACCEPTED -> "Принят"
}

private fun ProjectRiskCategory.localizedName(): String = when (this) {
    ProjectRiskCategory.TECHNICAL -> "Технический"
    ProjectRiskCategory.SUPPLY -> "Снабжение"
    ProjectRiskCategory.SCHEDULE -> "Сроки"
    ProjectRiskCategory.FINANCIAL -> "Финансовый"
    ProjectRiskCategory.ORGANIZATIONAL -> "Организационный"
    ProjectRiskCategory.OTHER -> "Прочее"
}

private fun ProjectRiskLevel.localizedName(): String = when (this) {
    ProjectRiskLevel.LOW -> "низкая"
    ProjectRiskLevel.MEDIUM -> "средняя"
    ProjectRiskLevel.HIGH -> "высокая"
}

private fun ProjectRiskCriticality.localizedName(): String = when (this) {
    ProjectRiskCriticality.LOW -> "Низкая"
    ProjectRiskCriticality.MEDIUM -> "Средняя"
    ProjectRiskCriticality.HIGH -> "Высокая"
    ProjectRiskCriticality.CRITICAL -> "Критичная"
}

@Composable
private fun ProjectRiskCriticality.accentColor() = when (this) {
    ProjectRiskCriticality.LOW -> MaterialTheme.colorScheme.outline
    ProjectRiskCriticality.MEDIUM -> MaterialTheme.colorScheme.primary
    ProjectRiskCriticality.HIGH -> MaterialTheme.colorScheme.tertiary
    ProjectRiskCriticality.CRITICAL -> MaterialTheme.colorScheme.error
}

// ---------------------------------------------------------------------------
// Диалоги
// ---------------------------------------------------------------------------

@Composable
private fun CreateStageDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String?, String?) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    ErpDialog(
        title = "Новый этап",
        subtitle = "Порядковый номер назначается автоматически",
        onDismiss = onDismiss,
        confirmText = "Создать",
        confirmEnabled = name.isNotBlank(),
        onConfirm = {
            onCreate(
                name,
                start.takeIf { it.isNotBlank() },
                end.takeIf { it.isNotBlank() }
            )
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
            ErpTextField(value = name, onValueChange = { name = it }, label = "Название *")
            ErpTextField(
                value = start,
                onValueChange = { start = it },
                label = "Плановое начало",
                placeholder = "2026-12-01T09:00:00Z"
            )
            ErpTextField(
                value = end,
                onValueChange = { end = it },
                label = "Плановое завершение",
                placeholder = "2026-12-20T18:00:00Z"
            )
        }
    }
}

@Composable
private fun CancelStageDialog(
    stage: ProjectStageDto,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var reason by remember { mutableStateOf("") }
    ErpDialog(
        title = "Отмена этапа",
        subtitle = stage.name,
        onDismiss = onDismiss,
        confirmText = "Отменить этап",
        confirmEnabled = reason.isNotBlank(),
        onConfirm = { onConfirm(reason) }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Причина обязательна: по ней видно, почему работа по этапу не выполняется.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ErpTextField(
                value = reason,
                onValueChange = { reason = it },
                label = "Причина *",
                singleLine = false,
                minLines = 3
            )
        }
    }
}

@Composable
private fun CreateRiskDialog(
    stages: List<ProjectStageDto>,
    tasks: List<TaskDto>,
    onDismiss: () -> Unit,
    onCreate: (
        String, String?, ProjectRiskCategory, ProjectRiskLevel,
        ProjectRiskLevel, ProjectRiskCriticality, String?, String?, Long?, Long?
    ) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var responsePlan by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(ProjectRiskCategory.OTHER) }
    var probability by remember { mutableStateOf(ProjectRiskLevel.MEDIUM) }
    var impact by remember { mutableStateOf(ProjectRiskLevel.MEDIUM) }
    var criticality by remember { mutableStateOf(ProjectRiskCriticality.MEDIUM) }
    var stageId by remember { mutableStateOf<Long?>(null) }
    var taskId by remember { mutableStateOf<Long?>(null) }

    ErpDialog(
        title = "Новый риск",
        subtitle = "Вероятность, влияние и критичность задаются явно",
        onDismiss = onDismiss,
        confirmText = "Сохранить",
        confirmEnabled = title.isNotBlank(),
        onConfirm = {
            onCreate(
                title,
                description.takeIf { it.isNotBlank() },
                category,
                probability,
                impact,
                criticality,
                responsePlan.takeIf { it.isNotBlank() },
                dueDate.takeIf { it.isNotBlank() },
                stageId,
                taskId
            )
        }
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp)
        ) {
            ErpTextField(value = title, onValueChange = { title = it }, label = "Название *")
            ErpTextField(
                value = description,
                onValueChange = { description = it },
                label = "Описание",
                singleLine = false,
                minLines = 2
            )
            EnumRow(
                label = "Категория",
                values = ProjectRiskCategory.entries.map { it to it.localizedName() },
                selected = category,
                onSelect = { category = it }
            )
            EnumRow(
                label = "Вероятность",
                values = ProjectRiskLevel.entries.map { it to it.localizedName() },
                selected = probability,
                onSelect = { probability = it }
            )
            EnumRow(
                label = "Влияние",
                values = ProjectRiskLevel.entries.map { it to it.localizedName() },
                selected = impact,
                onSelect = { impact = it }
            )
            EnumRow(
                label = "Критичность",
                values = ProjectRiskCriticality.entries.map { it to it.localizedName() },
                selected = criticality,
                onSelect = { criticality = it }
            )
            ErpTextField(
                value = responsePlan,
                onValueChange = { responsePlan = it },
                label = "План реагирования",
                singleLine = false,
                minLines = 2
            )
            org.example.project.ui.theme.ErpDateField(
                value = dueDate,
                onValueChange = { dueDate = it },
                label = "Срок",
                format = org.example.project.ui.theme.DateValueFormat.DATE,
                modifier = Modifier.fillMaxWidth()
            )
            if (stages.isNotEmpty()) {
                EnumRow(
                    label = "Этап",
                    values = buildList {
                        add(null to "Без этапа")
                        addAll(stages.map { it.id to "${it.sequence}. ${it.name}" })
                    },
                    selected = stageId,
                    onSelect = { stageId = it }
                )
            }
            if (tasks.isNotEmpty()) {
                EnumRow(
                    label = "Задача",
                    values = buildList {
                        add(null to "Без задачи")
                        addAll(tasks.map { it.id to "${it.number} · ${it.title}" })
                    },
                    selected = taskId,
                    onSelect = { taskId = it }
                )
            }
        }
    }
}

@Composable
private fun CloseRiskDialog(
    risk: ProjectRiskDto,
    onDismiss: () -> Unit,
    onConfirm: (String, Boolean) -> Unit
) {
    var outcome by remember { mutableStateOf("") }
    var accepted by remember { mutableStateOf(false) }
    ErpDialog(
        title = "Закрытие риска",
        subtitle = risk.title,
        onDismiss = onDismiss,
        confirmText = if (accepted) "Принять риск" else "Закрыть риск",
        confirmEnabled = outcome.isNotBlank(),
        onConfirm = { onConfirm(outcome, accepted) }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Исход обязателен: без него не отличить снятый риск от реализовавшегося.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            ErpTextField(
                value = outcome,
                onValueChange = { outcome = it },
                label = "Исход *",
                singleLine = false,
                minLines = 3
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChipButton("Риск снят", !accepted, onClick = { accepted = false })
                FilterChipButton("Риск принят", accepted, onClick = { accepted = true })
            }
        }
    }
}

/**
 * Ряд выбора из перечисления. Значение `null` означает «не указано» и
 * допустимо только там, где у сущности поле действительно необязательно.
 */
@Composable
private fun <T> EnumRow(
    label: String,
    values: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            values.forEach { (value, text) ->
                SoftButton(
                    text = text,
                    onClick = { onSelect(value) },
                    enabled = value != selected
                )
            }
        }
    }
}
