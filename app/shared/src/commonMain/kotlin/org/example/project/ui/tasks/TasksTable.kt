package org.example.project.ui.tasks

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unchil.un7datagrid.Un7KCMPDataGrid
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus
import org.example.project.dto.UserDto

@Composable
fun TasksTable(
    tasks: List<TaskDto>,
    users: List<UserDto>,
    onTaskClick: (TaskDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Номер" to tasks.map { it.number },
        "Название" to tasks.map { it.title },
        "Статус" to tasks.map { it.status.localizedName() },
        "Приоритет" to tasks.map { it.priority.localizedName() },
        "Исполнитель" to tasks.map { task -> task.assigneeId?.let { userName(users, it) } ?: "—" },
        "Срок" to tasks.map { it.dueDate.toShortDate() }
    )

    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = Un7KCMPDataGridConfig(
            isUsableHaptic = false,
            pageSizeList = listOf("10", "20", "50", "100"),
            defaultPageSizeListIndex = 0
        ),
        onClick = { selected ->
            val number = selected.firstOrNull()?.second?.firstOrNull() as? String
            tasks.firstOrNull { it.number == number }?.let(onTaskClick)
        }
    )
}

@Composable
fun TaskStatusChip(status: TaskStatus, modifier: Modifier = Modifier) {
    val containerColor = when (status) {
        TaskStatus.NEW -> MaterialTheme.colorScheme.outlineVariant
        TaskStatus.ASSIGNED -> MaterialTheme.colorScheme.primaryContainer
        TaskStatus.IN_PROGRESS -> MaterialTheme.colorScheme.secondaryContainer
        TaskStatus.BLOCKED -> MaterialTheme.colorScheme.errorContainer
        TaskStatus.ON_REVIEW -> MaterialTheme.colorScheme.tertiaryContainer
        TaskStatus.REWORK -> MaterialTheme.colorScheme.tertiaryContainer
        TaskStatus.CLOSED -> MaterialTheme.colorScheme.surfaceVariant
        TaskStatus.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when (status) {
        TaskStatus.NEW -> MaterialTheme.colorScheme.onSurfaceVariant
        TaskStatus.ASSIGNED -> MaterialTheme.colorScheme.onPrimaryContainer
        TaskStatus.IN_PROGRESS -> MaterialTheme.colorScheme.onSecondaryContainer
        TaskStatus.BLOCKED -> MaterialTheme.colorScheme.onErrorContainer
        TaskStatus.ON_REVIEW -> MaterialTheme.colorScheme.onTertiaryContainer
        TaskStatus.REWORK -> MaterialTheme.colorScheme.onTertiaryContainer
        TaskStatus.CLOSED -> MaterialTheme.colorScheme.onSurfaceVariant
        TaskStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

internal fun userName(users: List<UserDto>, userId: Long): String =
    users.find { it.id == userId }?.fullName ?: "ID $userId"
