package org.example.project.ui.notifications

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.tables.PersistentDataGrid as Un7KCMPDataGrid
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.NotificationDto
import org.example.project.ui.wms.toShortDate

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("10", "20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun NotificationsGrid(
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
