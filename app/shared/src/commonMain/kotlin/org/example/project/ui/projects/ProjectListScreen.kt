package org.example.project.ui.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.dto.ProjectDto
import org.example.project.dto.ProjectStatus

@Composable
fun ProjectListScreen(
    projects: List<ProjectDto>,
    isLoading: Boolean,
    error: org.example.project.dto.ApiError?,
    onProjectClick: (ProjectDto) -> Unit,
    onCreateClick: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Проекты",
                style = MaterialTheme.typography.headlineSmall
            )
            Row {
                Button(onClick = onCreateClick) {
                    Text("Создать")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onRefresh, enabled = !isLoading) {
                    Text("Обновить")
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return
        }

        error?.let {
            ErrorMessageCard(error = it, modifier = Modifier.padding(vertical = 8.dp))
        }

        if (projects.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Нет доступных проектов. Создайте первый проект.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(projects, key = { it.id }) { project ->
                    ProjectListItem(
                        project = project,
                        onClick = { onProjectClick(project) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProjectListItem(
    project: ProjectDto,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = project.number,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                StatusChip(status = project.status)
            }

            Text(
                text = project.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            project.fullName?.takeIf { it.isNotBlank() && it != project.name }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    InfoRow(label = "Руководитель", value = ownerName(project))
                    InfoRow(label = "Версия", value = project.version.toString())
                }
                Column(modifier = Modifier.weight(1f)) {
                    InfoRow(
                        label = "Начало",
                        value = project.plannedStartAt.toShortDate()
                    )
                    InfoRow(
                        label = "Окончание",
                        value = project.plannedEndAt.toShortDate()
                    )
                }
            }
        }
    }
}

@Composable
internal fun StatusChip(status: ProjectStatus, modifier: Modifier = Modifier) {
    val containerColor = when (status) {
        ProjectStatus.DRAFT -> MaterialTheme.colorScheme.outlineVariant
        ProjectStatus.PLANNED -> MaterialTheme.colorScheme.primaryContainer
        ProjectStatus.IN_PROGRESS -> MaterialTheme.colorScheme.secondaryContainer
        ProjectStatus.ON_HOLD -> MaterialTheme.colorScheme.tertiaryContainer
        ProjectStatus.COMPLETED -> MaterialTheme.colorScheme.inverseSurface
        ProjectStatus.CLOSED -> MaterialTheme.colorScheme.surfaceVariant
        ProjectStatus.CANCELLED -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (status) {
        ProjectStatus.DRAFT -> MaterialTheme.colorScheme.onSurfaceVariant
        ProjectStatus.PLANNED -> MaterialTheme.colorScheme.onPrimaryContainer
        ProjectStatus.IN_PROGRESS -> MaterialTheme.colorScheme.onSecondaryContainer
        ProjectStatus.ON_HOLD -> MaterialTheme.colorScheme.onTertiaryContainer
        ProjectStatus.COMPLETED -> MaterialTheme.colorScheme.inverseOnSurface
        ProjectStatus.CLOSED -> MaterialTheme.colorScheme.onSurfaceVariant
        ProjectStatus.CANCELLED -> MaterialTheme.colorScheme.onErrorContainer
    }

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = containerColor,
        contentColor = contentColor
    ) {
        Text(
            text = status.localizedName(),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun ownerName(project: ProjectDto): String {
    val owner = project.members.find { it.userId == project.ownerId }
    return owner?.fullName ?: "ID ${project.ownerId}"
}
