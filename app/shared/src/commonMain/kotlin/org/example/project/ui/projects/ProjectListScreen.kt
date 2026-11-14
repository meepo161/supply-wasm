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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
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
                        text = "Всего ${projects.size} · в работе ${projects.count { it.status == ProjectStatus.IN_PROGRESS }}",
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
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 340.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectCard(project = project, onClick = { onProjectClick(project) })
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
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        scheme.surfaceContainerLowest,
                        accent.copy(alpha = 0.05f + 0.06f * lift)
                    )
                )
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        Color.White.copy(alpha = 0.4f),
                        accent.copy(alpha = 0.16f + 0.22f * lift)
                    )
                ),
                RoundedCornerShape(20.dp)
            )
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
                            text = "${project.plannedStartAt.toShortDate()} — ${project.plannedEndAt.toShortDate()}",
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
