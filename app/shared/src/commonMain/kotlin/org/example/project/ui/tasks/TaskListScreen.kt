package org.example.project.ui.tasks

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.dto.ApiError
import org.example.project.dto.ProjectDto
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskPriority
import org.example.project.dto.TaskStatus
import org.example.project.dto.UserDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.erpInteractive
import org.example.project.ui.theme.Avatar
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.EmptyState
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.IconBadge
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SkeletonLine
import org.example.project.ui.theme.SoftButton

@Composable
fun TaskListScreen(
    tasks: List<TaskDto>,
    users: List<UserDto>,
    projects: List<ProjectDto> = emptyList(),
    isLoading: Boolean,
    error: ApiError?,
    onTaskClick: (TaskDto) -> Unit,
    onCreateClick: () -> Unit,
    onRefresh: () -> Unit,
    compact: Boolean = false,
    currentUserId: Long? = null,
    modifier: Modifier = Modifier
) {
    var statusFilter by rememberSaveable { mutableStateOf(TaskStatusFilter.ALL) }
    var areaFilter by rememberSaveable { mutableStateOf(TaskAreaFilter.ALL) }
    var mineOnly by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(tasks, statusFilter, areaFilter, mineOnly, currentUserId, query) {
        tasks
            .filterByStatuses(statusFilter.statuses)
            .filterByArea(areaFilter)
            .let { if (mineOnly) it.filterMine(currentUserId) else it }
            .filterByQuery(query)
            .sortedBy { it.dueDate?.takeIf { d -> d.isNotBlank() } ?: "9999-12-31" }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = if (compact) 12.dp else 24.dp)
    ) {
        // Шапка. FlowRow переносит кнопки под заголовок на узком экране, а не сжимает их.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        if (!compact) androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(ErpIconGlyph.Tasks, gradient = Gradients.Ocean, size = 40.dp, iconSize = 20.dp)
                Spacer(Modifier.width(13.dp))
                Column {
                    Text(
                        text = "Задачи",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Всего ${tasks.size} · в выборке ${filtered.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SoftButton(
                    text = "Обновить",
                    onClick = onRefresh,
                    enabled = !isLoading,
                    leadingIcon = ErpIconGlyph.Refresh
                )
                Spacer(Modifier.width(10.dp))
                GradientButton(
                    text = "Создать задачу",
                    onClick = onCreateClick,
                    leadingIcon = ErpIconGlyph.Plus
                )
            }
        } else Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Задачи", style = MaterialTheme.typography.titleMedium)
            SoftButton(text = "Создать", onClick = onCreateClick, leadingIcon = ErpIconGlyph.Plus)
        }

        if (compact) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Поиск по названию") },
                singleLine = true,
                leadingIcon = {
                    ErpIcon(ErpIconGlyph.Search, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }, modifier = Modifier.size(36.dp)) {
                            ErpIcon(ErpIconGlyph.Close, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
        }

        // Единая компактная строка фильтров (статус + область + мои + сбросить)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TaskStatusFilter.entries.forEach { item ->
                FilterPill(
                    label = item.label,
                    count = if (item.statuses.isEmpty()) tasks.size
                    else tasks.count { it.status in item.statuses },
                    selected = statusFilter == item,
                    onClick = { statusFilter = item }
                )
            }
            Spacer(Modifier.width(6.dp))
            TaskAreaFilter.entries.filter { it != TaskAreaFilter.ALL }.forEach { item ->
                FilterPill(
                    label = item.label,
                    count = tasks.filterByArea(item).size,
                    selected = areaFilter == item,
                    onClick = { areaFilter = if (areaFilter == item) TaskAreaFilter.ALL else item }
                )
            }
            if (currentUserId != null) {
                FilterPill(
                    label = "Мои",
                    count = tasks.filterMine(currentUserId).size,
                    selected = mineOnly,
                    onClick = { mineOnly = !mineOnly }
                )
            }
            if (statusFilter != TaskStatusFilter.ALL || areaFilter != TaskAreaFilter.ALL || mineOnly || query.isNotBlank()) {
                SoftButton(
                    text = "Сбросить",
                    onClick = {
                        statusFilter = TaskStatusFilter.ALL
                        areaFilter = TaskAreaFilter.ALL
                        mineOnly = false
                        query = ""
                    },
                    leadingIcon = ErpIconGlyph.Close
                )
            }
        }

        error?.let {
            ErrorMessageCard(error = it, modifier = Modifier.padding(bottom = 12.dp))
        }

        when {
            isLoading && tasks.isEmpty() -> Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                repeat(5) { SkeletonLine(height = 74.dp) }
            }
            filtered.isEmpty() -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    title = if (tasks.isEmpty()) "Пока нет задач" else "В этой выборке пусто",
                    text = if (tasks.isEmpty()) "Создайте первую задачу, чтобы начать работу"
                    else "Попробуйте выбрать другой фильтр",
                    glyph = ErpIconGlyph.Tasks,
                    accent = Brand.Blue
                )
            }
            else -> BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                if (maxWidth >= 760.dp) {
                    TasksTable(
                        tasks = filtered,
                        users = users,
                        projects = projects,
                        onTaskClick = onTaskClick,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    org.example.project.ui.tables.PaginatedCardList(
                        items = filtered,
                        modifier = Modifier.fillMaxSize(),
                        itemSpacing = 10.dp
                    ) { _, task ->
                        TaskCard(
                            task = task,
                            users = users,
                            projects = projects,
                            currentUserId = currentUserId,
                            onClick = { onTaskClick(task) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(
                when {
                    selected -> scheme.primary
                    hovered -> scheme.surfaceContainerHigh
                    else -> scheme.surface
                }
            )
            .border(1.dp, if (selected) scheme.primary else scheme.outline, shape)
            .erpInteractive(interaction, shape, focusColor = scheme.onBackground)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) scheme.onPrimary else scheme.onSurface
            )
            Spacer(Modifier.width(7.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (selected) Color.White.copy(alpha = 0.22f)
                        else scheme.onSurfaceVariant.copy(alpha = 0.10f)
                    )
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) {
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun TaskCard(
    task: TaskDto,
    users: List<UserDto>,
    projects: List<ProjectDto> = emptyList(),
    currentUserId: Long?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = tween(200),
        label = "taskCard"
    )
    val statusAccent = task.status.accentColor()
    val assignee = task.assigneeId?.let { id -> users.find { it.id == id } }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (hovered) scheme.surfaceContainerLow else scheme.surface)
            .border(
                1.dp,
                if (hovered) scheme.primary.copy(alpha = 0.55f) else scheme.outlineVariant,
                RoundedCornerShape(10.dp)
            )
            .erpInteractive(interaction, RoundedCornerShape(10.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(16.dp)
    ) {
        BoxWithConstraints {
            val compact = maxWidth < 620.dp
            if (compact) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TaskCardHeader(task, statusAccent)
                    TaskCardMeta(task, assignee, projects, currentUserId)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 4.dp, height = 46.dp)
                            .clip(RoundedCornerShape(2.dp))
                            // Полоса дублирует цветом статус, который назван текстом в шапке карточки.
                            .background(statusAccent)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TaskCardHeader(task, statusAccent)
                        TaskCardMeta(task, assignee, projects, currentUserId)
                    }
                    Spacer(Modifier.width(12.dp))
                    ErpIcon(
                        ErpIconGlyph.ChevronRight,
                        scheme.onSurfaceVariant.copy(alpha = 0.6f + 0.4f * lift),
                        Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCardHeader(task: TaskDto, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = task.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        PillBadge(text = task.status.localizedName(), accent = accent)
    }
}

@Composable
private fun TaskCardMeta(
    task: TaskDto,
    assignee: UserDto?,
    projects: List<ProjectDto>,
    currentUserId: Long?
) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Левая часть занимает остаток ширины; дата снаружи и поэтому никогда не сжимается.
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            PriorityTag(task.priority)
            Spacer(Modifier.width(12.dp))
            if (assignee != null) {
                Avatar(name = assignee.fullName, size = 22.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (assignee.id == currentUserId) "Вы" else assignee.fullName,
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            } else {
                Text(
                    text = "Без исполнителя",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            task.projectId?.let { projectId ->
                val project = projects.find { it.id == projectId }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = project?.let { "${it.number} · ${it.name}" } ?: "Проект #$projectId",
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        if (!task.dueDate.isNullOrBlank()) {
            ErpIcon(
                ErpIconGlyph.Clock,
                scheme.onSurfaceVariant,
                Modifier.size(13.dp)
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = task.dueDate.toShortDate(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun PriorityTag(priority: TaskPriority) {
    val color = priority.accentColor()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = priority.localizedName(),
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

internal fun TaskStatus.accentColor(): Color = when (this) {
    TaskStatus.NEW -> Brand.Blue
    TaskStatus.ASSIGNED -> Brand.Indigo
    TaskStatus.IN_PROGRESS -> Brand.Teal
    TaskStatus.BLOCKED -> Brand.Crimson
    TaskStatus.ON_REVIEW -> Brand.Amber
    TaskStatus.REWORK -> Brand.Orange
    TaskStatus.CLOSED -> Brand.Mint
    TaskStatus.CANCELLED -> Brand.Slate
}

internal fun TaskPriority.accentColor(): Color = when (this) {
    TaskPriority.LOW -> Brand.Mint
    TaskPriority.NORMAL -> Brand.Blue
    TaskPriority.HIGH -> Brand.Orange
    TaskPriority.CRITICAL -> Brand.Crimson
}
