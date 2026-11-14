package org.example.project.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.unchil.un7datagrid.Un7KCMPDataGrid
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.NotificationDto
import org.example.project.dto.TaskDto
import org.example.project.ui.tasks.localizedName
import org.example.project.ui.wms.toShortDate

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("10", "20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun HomeTaskGrid(
    tasks: List<TaskDto>,
    onClick: (TaskDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Номер" to tasks.map { it.number },
        "Название" to tasks.map { it.title },
        "Статус" to tasks.map { it.status.localizedName() },
        "Приоритет" to tasks.map { it.priority.localizedName() },
        "Срок" to tasks.map { it.dueDate.toShortDate() }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val number = selected.firstOrNull()?.second?.firstOrNull() as? String
            tasks.firstOrNull { it.number == number }?.let(onClick)
        }
    )
}

@Composable
fun HomeNotificationGrid(
    notifications: List<NotificationDto>,
    onClick: (NotificationDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to notifications.map { it.id.toString() },
        "Тип" to notifications.map { it.type },
        "Объект" to notifications.map { "${it.entityType} ${it.entityId}" },
        "Заголовок" to notifications.map { it.title },
        "Прочитано" to notifications.map { if (it.isRead) "Да" else "Нет" },
        "Дата" to notifications.map { it.createdAt.toShortDate() }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            notifications.firstOrNull { it.id.toString() == id }?.let(onClick)
        }
    )
}
