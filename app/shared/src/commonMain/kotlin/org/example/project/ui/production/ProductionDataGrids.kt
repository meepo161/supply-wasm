package org.example.project.ui.production

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.tables.PersistentDataGrid as Un7KCMPDataGrid
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.ProductInstanceDto
import org.example.project.dto.ProductionOrderDto
import org.example.project.dto.ProductionOrderStageDto

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("10", "20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun ProductionOrdersGrid(
    orders: List<ProductionOrderDto>,
    onClick: (ProductionOrderDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to orders.map { it.id.toString() },
        "Номер" to orders.map { it.number },
        "Статус" to orders.map { it.status.localizedName() },
        "План" to orders.map { it.plannedQuantity.toString() },
        "Факт" to orders.map { it.actualQuantity.toString() },
        "Начало" to orders.map { it.plannedStartAt.toDisplayDateTime() },
        "Окончание" to orders.map { it.plannedEndAt.toDisplayDateTime() }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            orders.firstOrNull { it.id.toString() == id }?.let(onClick)
        }
    )
}

@Composable
fun ProductionStagesGrid(
    stages: List<ProductionOrderStageDto>,
    onClick: (ProductionOrderStageDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val sorted = stages.sortedBy { it.sequence }
    val data = mapOf(
        "№" to sorted.map { it.sequence.toString() },
        "Этап" to sorted.map { it.name },
        "Тип" to sorted.map { it.stageType.localizedName() },
        "Статус" to sorted.map { it.status.localizedName() },
        "План старт" to sorted.map { it.plannedStartAt.toDisplayDateTime() },
        "Факт старт" to sorted.map { it.actualStartAt.toDisplayDateTime() },
        "Факт финиш" to sorted.map { it.actualEndAt.toDisplayDateTime() },
        "Основание" to sorted.map { it.skipReason ?: "—" }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val sequence = selected.firstOrNull()?.second?.firstOrNull() as? String
            sorted.firstOrNull { it.sequence.toString() == sequence }?.let(onClick)
        }
    )
}

@Composable
fun ProductInstancesGrid(
    instances: List<ProductInstanceDto>,
    onClick: (ProductInstanceDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to instances.map { it.id.toString() },
        "Серийный номер" to instances.map { it.serialNumber },
        "Статус" to instances.map { it.status },
        "Изготовлен" to instances.map { it.manufacturedAt.toDisplayDateTime() }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            instances.firstOrNull { it.id.toString() == id }?.let(onClick)
        }
    )
}
