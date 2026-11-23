package org.example.project.ui.documents

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.tables.PersistentDataGrid
import org.example.project.ui.theme.ErpFormat
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.DocumentDto
import org.example.project.dto.UserDto

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun DocumentsGrid(
    documents: List<DocumentDto>,
    users: List<UserDto>,
    onClick: (DocumentDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Номер" to documents.map { it.number },
        "Название" to documents.map { it.name },
        "Тип" to documents.map { it.docType.localizedName() },
        "Статус" to documents.map { it.status.localizedName() },
        "Доступ" to documents.map { it.confidentiality.localizedName() },
        "Владелец" to documents.map { doc ->
            users.find { it.id == doc.ownerId }?.fullName ?: "Сотрудник #${doc.ownerId}"
        },
        "Обновлён" to documents.map { ErpFormat.dateRu(it.updatedAt) }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "documents-registry",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                documents.getOrNull(idx)?.let(onClick)
            }
        }
    )
}
