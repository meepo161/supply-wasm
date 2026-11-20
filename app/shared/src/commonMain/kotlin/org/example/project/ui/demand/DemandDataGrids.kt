package org.example.project.ui.demand

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.tables.PersistentDataGrid as Un7KCMPDataGrid
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.DemandCoverageLineDto
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.DemandLineDto

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("10", "20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun DemandsGrid(
    demands: List<DemandHeaderDto>,
    onClick: (DemandHeaderDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to demands.map { it.id.toString() },
        "Номер" to demands.map { it.number },
        "Основание" to demands.map { it.sourceType.localizedName() },
        "Проект" to demands.map { it.projectId?.toString() ?: "—" },
        "Приоритет" to demands.map { it.priority.localizedName() },
        "Статус" to demands.map { it.status.localizedName() },
        "Требуемая дата" to demands.map { it.requiredDate?.substringBefore("T") ?: "—" }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            demands.firstOrNull { it.id.toString() == id }?.let(onClick)
        }
    )
}

@Composable
fun DemandLinesGrid(
    lines: List<DemandLineDto>,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "№" to lines.map { it.lineNo.toString() },
        "Номенклатура" to lines.map { it.nomenclatureId.toString() },
        "Кол-во" to lines.map { it.quantity },
        "Ед." to lines.map { it.unit },
        "Покрыто" to lines.map { it.coveredQuantity },
        "Дефицит" to lines.map { it.deficitQuantity ?: "—" },
        "Статус" to lines.map { it.status.localizedName() }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { }
    )
}

@Composable
fun DemandCoverageGrid(
    coverage: List<DemandCoverageLineDto>,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "№" to coverage.map { it.lineNo.toString() },
        "Номенклатура" to coverage.map { it.nomenclatureId.toString() },
        "Ед." to coverage.map { it.unit },
        "Требуется" to coverage.map { it.requiredQuantity },
        "Доступно" to coverage.map { it.stockAvailable },
        "Резерв проектов" to coverage.map { it.projectReservations },
        "Ожидается" to coverage.map { it.expectedReceipts },
        "Дефицит" to coverage.map { it.deficit },
        "Рекомендуемая закупка" to coverage.map { it.recommendedPurchase }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { }
    )
}
