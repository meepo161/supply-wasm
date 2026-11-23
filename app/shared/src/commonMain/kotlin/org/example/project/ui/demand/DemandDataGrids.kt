package org.example.project.ui.demand

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.home.currentIsoDate
import org.example.project.ui.tables.PersistentDataGrid
import org.example.project.ui.theme.ErpFormat
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.DemandCoverageLineDto
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.DemandLineDto

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun DemandsGrid(
    demands: List<DemandHeaderDto>,
    onClick: (DemandHeaderDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val todayIso = currentIsoDate()
    val data = mapOf(
        "Номер" to demands.map { it.number },
        "Основание" to demands.map { it.sourceType.localizedName() },
        "Проект" to demands.map { it.projectId?.let { id -> "Проект #$id" } ?: "—" },
        "Приоритет" to demands.map { if (it.priority.name == "NORMAL") "—" else it.priority.localizedName() },
        "Статус" to demands.map { it.status.localizedName() },
        "Требуемая дата" to demands.map { ErpFormat.deadlineLabel(it.requiredDate, todayIso) }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "demands-registry",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                demands.getOrNull(idx)?.let(onClick)
            }
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
        "Номенклатура" to lines.map { "Поз. #${it.nomenclatureId}" },
        "Кол-во" to lines.map { ErpFormat.qty(it.quantity) },
        "Ед." to lines.map { it.unit },
        "Покрыто" to lines.map { ErpFormat.qty(it.coveredQuantity) },
        "Дефицит" to lines.map { ErpFormat.qty(it.deficitQuantity) },
        "Статус" to lines.map { it.status.localizedName() }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "demand-lines",
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
        "Номенклатура" to coverage.map { "Поз. #${it.nomenclatureId}" },
        "Ед." to coverage.map { it.unit },
        "Требуется" to coverage.map { ErpFormat.qty(it.requiredQuantity) },
        "Доступно" to coverage.map { ErpFormat.qty(it.stockAvailable) },
        "Резерв проектов" to coverage.map { ErpFormat.qty(it.projectReservations) },
        "Ожидается" to coverage.map { ErpFormat.qty(it.expectedReceipts) },
        "Дефицит" to coverage.map { ErpFormat.qty(it.deficit) },
        "Рекомендуемая закупка" to coverage.map { ErpFormat.qty(it.recommendedPurchase) }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "demand-coverage",
        config = gridConfig,
        onClick = { }
    )
}
