package org.example.project.ui.notifications

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.tables.PersistentDataGrid
import org.example.project.ui.theme.ErpFormat
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.NotificationDto

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun NotificationsGrid(
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
        tableKey = "notifications-registry",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                notifications.getOrNull(idx)?.let(onClick)
            }
        }
    )
}
