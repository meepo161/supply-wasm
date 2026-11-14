package org.example.project.ui.tasks

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.unchil.un7datagrid.Un7KCMPDataGrid
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus
import org.example.project.dto.UserDto
import org.example.project.ui.theme.PillBadge

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
    PillBadge(
        text = status.localizedName(),
        accent = status.accentColor(),
        modifier = modifier
    )
}

internal fun userName(users: List<UserDto>, userId: Long): String =
    users.find { it.id == userId }?.fullName ?: "ID $userId"
