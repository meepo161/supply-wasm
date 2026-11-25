package org.example.project.ui.projects

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import org.example.project.ui.theme.erpInteractive
import org.example.project.ui.theme.ErpFilterChip as FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import org.example.project.dto.ProjectStatus
import org.example.project.ui.tables.PersistentDataGrid
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.ui.tasks.toShortDate
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
fun ProjectListScreen(
    projects: List<ProjectDto>,
    isLoading: Boolean,
    error: ApiError?,
    onProjectClick: (ProjectDto) -> Unit,
    onCreateClick: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        // FlowRow: на узком экране кнопки переносятся под заголовок, а не уходят за край.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(ErpIconGlyph.Projects, gradient = Gradients.Aurora, size = 40.dp, iconSize = 20.dp)
                Spacer(Modifier.width(13.dp))
                Column {
                    Text(
                        text = "Портфель проектов",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Всего ${projects.size} · в работе ${projects.count { it.status == ProjectStatus.IN_PROGRESS || it.status == ProjectStatus.PLANNED || it.status == ProjectStatus.ON_HOLD }}",
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
                    text = "Новый проект",
                    onClick = onCreateClick,
                    gradient = Gradients.Aurora,
                    leadingIcon = ErpIconGlyph.Plus
                )
            }
        }

        error?.let {
            ErrorMessageCard(error = it, modifier = Modifier.padding(bottom = 12.dp))
        }

        when {
            isLoading && projects.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { SkeletonLine(height = 96.dp) }
            }
            projects.isEmpty() -> GlassCard(modifier = Modifier.fillMaxWidth()) {
                EmptyState(
                    title = "Пока нет проектов",
                    text = "Создайте первый проект, чтобы объединить задачи и документы",
                    glyph = ErpIconGlyph.Projects,
                    accent = Brand.Indigo
                )
            }
            else -> {
                var statusFilter by remember { mutableStateOf<String>("ALL") }
                val filteredProjects = remember(projects, statusFilter) {
                    when (statusFilter) {
                        "ACTIVE" -> projects.filter { it.status == ProjectStatus.IN_PROGRESS || it.status == ProjectStatus.PLANNED || it.status == ProjectStatus.ON_HOLD }
                        "DRAFT" -> projects.filter { it.status == ProjectStatus.DRAFT }
                        "DONE" -> projects.filter { it.status == ProjectStatus.COMPLETED || it.status == ProjectStatus.CLOSED }
                        else -> projects
                    }
                }
                Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = statusFilter == "ALL",
                            onClick = { statusFilter = "ALL" },
                            label = { Text("Все (${projects.size})") }
                        )
                        FilterChip(
                            selected = statusFilter == "ACTIVE",
                            onClick = { statusFilter = "ACTIVE" },
                            label = { Text("В работе (${projects.count { it.status == ProjectStatus.IN_PROGRESS || it.status == ProjectStatus.PLANNED || it.status == ProjectStatus.ON_HOLD }})") }
                        )
                        FilterChip(
                            selected = statusFilter == "DRAFT",
                            onClick = { statusFilter = "DRAFT" },
                            label = { Text("Черновики (${projects.count { it.status == ProjectStatus.DRAFT }})") }
                        )
                        FilterChip(
                            selected = statusFilter == "DONE",
                            onClick = { statusFilter = "DONE" },
                            label = { Text("Завершённые (${projects.count { it.status == ProjectStatus.COMPLETED || it.status == ProjectStatus.CLOSED }})") }
                        )
                    }
                    val data = remember(filteredProjects) {
                        mapOf(
                            "Номер" to filteredProjects.map { it.number },
                            "Название" to filteredProjects.map { it.name },
                            "Статус" to filteredProjects.map {
                                if (it.status == ProjectStatus.DRAFT) "Черновик (откройте проект)" else it.status.localizedName()
                            },
                            "Руководитель" to filteredProjects.map { p ->
                                p.members.find { it.userId == p.ownerId }?.fullName ?: "—"
                            },
                            "Приоритет" to filteredProjects.map {
                                if (it.priority.name == "NORMAL") "—" else it.priority.localizedName()
                            },
                            "Заказчик" to filteredProjects.map { it.customer ?: "—" },
                            "Сроки" to filteredProjects.map { p ->
                                val s = org.example.project.ui.theme.ErpFormat.dateRu(p.plannedStartAt)
                                val e = org.example.project.ui.theme.ErpFormat.dateRu(p.plannedEndAt)
                                if (s == "—" && e == "—") "—" else "$s · $e"
                            }
                        )
                    }
                    PersistentDataGrid(
                        data = data,
                        tableKey = "projects-portfolio",
                        modifier = Modifier.fillMaxSize(),
                        config = Un7KCMPDataGridConfig(
                            isUsableHaptic = false,
                            pageSizeList = listOf("20", "50", "100"),
                            defaultPageSizeListIndex = 1
                        ),
                        onClick = { selected ->
                            selected.firstOrNull()?.first?.let { idx ->
                                filteredProjects.getOrNull(idx)?.let(onProjectClick)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectCard(project: ProjectDto, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lift by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = tween(200),
        label = "projectCard"
    )
    val accent = project.status.accentColor()
    val gradient = project.status.gradient()

    Box(
        modifier = Modifier
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
    ) {
        Column {
            // Градиентная шапка карточки
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Brush.horizontalGradient(gradient))
            )
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(
                            ErpIconGlyph.Projects,
                            gradient = gradient,
                            size = 34.dp,
                            iconSize = 17.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = project.number,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                    }
                    PillBadge(text = project.status.localizedName(), accent = accent)
                }

                Spacer(Modifier.height(14.dp))

                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                project.fullName?.takeIf { it.isNotBlank() && it != project.name }?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Avatar(name = ownerName(project), size = 26.dp)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "Руководитель",
                                style = MaterialTheme.typography.labelSmall,
                                color = scheme.onSurfaceVariant.copy(alpha = 0.8f)
                            )
                            Text(
                                text = ownerName(project),
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Сроки",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                        Text(
                            text = "${project.plannedStartAt.toShortDate()} · ${project.plannedEndAt.toShortDate()}",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurface
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(scheme.surfaceContainerHigh)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "v${project.version}",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "Открыть",
                        style = MaterialTheme.typography.labelMedium,
                        color = accent
                    )
                    ErpIcon(
                        ErpIconGlyph.ChevronRight,
                        accent,
                        Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun StatusChip(status: ProjectStatus, modifier: Modifier = Modifier) {
    PillBadge(
        text = status.localizedName(),
        accent = status.accentColor(),
        modifier = modifier
    )
}

internal fun ProjectStatus.accentColor(): Color = when (this) {
    ProjectStatus.DRAFT -> Brand.Slate
    ProjectStatus.PLANNED -> Brand.Blue
    ProjectStatus.IN_PROGRESS -> Brand.Teal
    ProjectStatus.ON_HOLD -> Brand.Amber
    ProjectStatus.COMPLETED -> Brand.Mint
    ProjectStatus.CLOSED -> Brand.Slate
    ProjectStatus.CANCELLED -> Brand.Crimson
}

private fun ProjectStatus.gradient(): List<Color> = when (this) {
    ProjectStatus.DRAFT -> Gradients.Slate
    ProjectStatus.PLANNED -> Gradients.Primary
    ProjectStatus.IN_PROGRESS -> Gradients.Aurora
    ProjectStatus.ON_HOLD -> Gradients.AmberGlow
    ProjectStatus.COMPLETED -> Gradients.Emerald
    ProjectStatus.CLOSED -> Gradients.Slate
    ProjectStatus.CANCELLED -> Gradients.Berry
}
