package org.example.project.ui.production

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.tables.PersistentDataGrid
import org.example.project.ui.theme.ErpFormat
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.ProductInstanceDto
import org.example.project.dto.ProductionOrderDto
import org.example.project.dto.ProductionOrderStageDto

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun ProductionOrdersGrid(
    orders: List<ProductionOrderDto>,
    onClick: (ProductionOrderDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Номер" to orders.map { it.number },
        "Статус" to orders.map { it.status.localizedName() },
        "План" to orders.map { ErpFormat.qty(it.plannedQuantity) },
        "Факт" to orders.map { ErpFormat.qty(it.actualQuantity) },
        "Начало" to orders.map { ErpFormat.dateRu(it.plannedStartAt) },
        "Окончание" to orders.map { ErpFormat.dateRu(it.plannedEndAt) }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "production-orders",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                orders.getOrNull(idx)?.let(onClick)
            }
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
        "Статус" to sorted.map { it.status.localizedName() },
        "План старт" to sorted.map { ErpFormat.dateRu(it.plannedStartAt) },
        "Факт старт" to sorted.map { ErpFormat.dateRu(it.actualStartAt) },
        "Факт финиш" to sorted.map { ErpFormat.dateRu(it.actualEndAt) },
        "Основание" to sorted.map { it.skipReason.orEmpty() }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "production-stages",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                sorted.getOrNull(idx)?.let(onClick)
            }
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
        "Серийный номер" to instances.map { it.serialNumber },
        "Статус" to instances.map { it.status.productionCodeLabel() },
        "Изготовлен" to instances.map { ErpFormat.dateRu(it.manufacturedAt) }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "production-instances",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                instances.getOrNull(idx)?.let(onClick)
            }
        }
    )
}
