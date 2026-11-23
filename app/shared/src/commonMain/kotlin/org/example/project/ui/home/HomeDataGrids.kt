package org.example.project.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.tables.PersistentDataGrid
import org.example.project.ui.theme.ErpFormat
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.NotificationDto
import org.example.project.dto.TaskDto
import org.example.project.ui.tasks.localizedName

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun HomeTaskGrid(
    tasks: List<TaskDto>,
    onClick: (TaskDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val todayIso = currentIsoDate()
    val data = mapOf(
        "Номер" to tasks.map { it.number },
        "Название" to tasks.map { it.title },
        "Статус" to tasks.map { it.status.localizedName() },
        "Приоритет" to tasks.map { if (it.priority.name == "NORMAL") "—" else it.priority.localizedName() },
        "Срок" to tasks.map { ErpFormat.deadlineLabel(it.dueDate, todayIso) }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "home-tasks",
        compact = true,
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                tasks.getOrNull(idx)?.let(onClick)
            }
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
        "Тип" to notifications.map { it.type },
        "Объект" to notifications.map { "${it.entityType} #${it.entityId}" },
        "Заголовок" to notifications.map { it.title },
        "Прочитано" to notifications.map { if (it.isRead) "Да" else "Нет" },
        "Дата" to notifications.map { ErpFormat.dateTimeRu(it.createdAt) }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "home-notifications",
        compact = true,
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                notifications.getOrNull(idx)?.let(onClick)
            }
        }
    )
}
