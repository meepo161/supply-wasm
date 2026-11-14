package org.example.project.ui.documents

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.unchil.un7datagrid.Un7KCMPDataGrid
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.DocumentDto
import org.example.project.dto.UserDto

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("10", "20", "50", "100"),
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
        "ID" to documents.map { it.id.toString() },
        "Номер" to documents.map { it.number },
        "Название" to documents.map { it.name },
        "Тип" to documents.map { it.docType.localizedName() },
        "Статус" to documents.map { it.status.localizedName() },
        "Конфиденциальность" to documents.map { it.confidentiality.localizedName() },
        "Владелец" to documents.map { doc ->
            users.find { it.id == doc.ownerId }?.fullName ?: "ID ${doc.ownerId}"
        },
        "Обновлён" to documents.map { it.updatedAt.substringBefore("T") }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            documents.firstOrNull { it.id.toString() == id }?.let(onClick)
        }
    )
}
