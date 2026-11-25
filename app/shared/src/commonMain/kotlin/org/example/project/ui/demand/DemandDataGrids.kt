package org.example.project.ui.demand

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.DemandLineDto
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ProjectDto
import org.example.project.ui.home.currentIsoDate
import org.example.project.ui.tables.PersistentDataGrid
import org.example.project.ui.theme.ErpFormat

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("20", "50", "100"),
    defaultPageSizeListIndex = 0
)

/** В реестре потребностей номер уже есть отдельной колонкой, поэтому «#» с порядковым номером не нужен. */
private val registryGridConfig = gridConfig.copy(isVisibilityRowNumber = false)

@Composable
fun DemandsGrid(
    demands: List<DemandHeaderDto>,
    onClick: (DemandHeaderDto) -> Unit,
    modifier: Modifier = Modifier,
    projects: List<ProjectDto> = emptyList(),
    offersCountByDemand: Map<Long, Int> = emptyMap(),
    offersCountLoaded: Boolean = false
) {
    val todayIso = currentIsoDate()
    val data = mapOf(
        "Номер" to demands.map { it.number },
        "Что нужно" to demands.map { it.itemsSummary ?: "—" },
        "Количество" to demands.map { it.quantitySummary ?: "—" },
        "Основание / Проект" to demands.map { demand ->
            demand.projectId?.let { id ->
                projects.firstOrNull { it.id == id }?.let { "${it.number} · ${it.name}" } ?: "Проект №$id"
            } ?: listOfNotNull(
                demand.sourceType.localizedName(),
                demand.stockpileNote?.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
        },
        "Инициатор" to demands.map { it.initiatorName ?: "—" },
        "Срок" to demands.map { ErpFormat.deadlineLabel(it.requiredDate, todayIso) },
        "Этап" to demands.map { demand ->
            val offersCount = if (offersCountLoaded) offersCountByDemand[demand.id] ?: 0 else null
            demandStageLabel(demand, offersCount)
        }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "demands-registry-v3",
        config = registryGridConfig,
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
    modifier: Modifier = Modifier,
    nomenclature: List<NomenclatureDto> = emptyList()
) {
    val data = mapOf(
        "№" to lines.map { it.lineNo.toString() },
        "Номенклатура" to lines.map { nomenclatureLabel(nomenclature, it.nomenclatureId) },
        "Кол-во" to lines.map { ErpFormat.qty(it.quantity) },
        "Ед." to lines.map { it.unit },
        "Принято" to lines.map { ErpFormat.qty(it.coveredQuantity) },
        "Осталось" to lines.map { ErpFormat.qty(it.deficitQuantity) },
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

/** «Код · Наименование» вместо внутреннего id; id — только если справочник не загружен. */
internal fun nomenclatureLabel(items: List<NomenclatureDto>, id: Long): String =
    items.firstOrNull { it.id == id }?.let { "${it.code} · ${it.name}" } ?: "Позиция №$id"
