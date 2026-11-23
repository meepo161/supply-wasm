package org.example.project.ui.bom

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.tables.PersistentDataGrid
import org.example.project.ui.theme.ErpFormat
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.BomExplodeLineDto
import org.example.project.dto.BomLineDto
import org.example.project.dto.BomMaterialSummaryDto
import org.example.project.dto.BomRevisionSummaryDto
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.ProductModelDto

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun ProductModelsGrid(
    models: List<ProductModelDto>,
    onClick: (ProductModelDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Код" to models.map { it.code },
        "Название" to models.map { it.name },
        "Статус" to models.map { it.status.localizedName() }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "bom-models",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                models.getOrNull(idx)?.let(onClick)
            }
        }
    )
}

@Composable
fun BomRevisionsGrid(
    revisions: List<BomRevisionSummaryDto>,
    onClick: (BomRevisionSummaryDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Ревизия" to revisions.map { it.revisionNumber },
        "Статус" to revisions.map { it.status.localizedName() }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "bom-revisions",
        compact = true,
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                revisions.getOrNull(idx)?.let(onClick)
            }
        }
    )
}

@Composable
fun BomLinesGrid(
    lines: List<BomLineDto>,
    onClick: (BomLineDto) -> Unit,
    nomenclature: List<NomenclatureDto> = emptyList(),
    modifier: Modifier = Modifier
) {
    val nomById = nomenclature.associateBy { it.id }
    val hasSubstitutes = lines.any { it.substituteOfLineId != null }
    val data = buildMap<String, List<Any?>> {
        put("Поз.", lines.map { it.position ?: "—" })
        put("Ур.", lines.map { it.level.toString() })
        put("Компонент", lines.map { line ->
            val prefix = "  ".repeat((line.level - 1).coerceAtLeast(0))
            val nom = nomById[line.childNomenclatureId]
            val title = nom?.let { "${it.code} · ${it.name}" } ?: "Номенклатура #${line.childNomenclatureId}"
            "$prefix$title"
        })
        put("Узел (куда входит)", lines.map { line ->
            val nom = nomById[line.parentNomenclatureId]
            nom?.let { "${it.code} · ${it.name}" } ?: "Изделие #${line.parentNomenclatureId}"
        })
        put("Кол-во", lines.map { ErpFormat.qty(it.quantity) })
        put("Ед.", lines.map { it.unit })
        put("Обяз.", lines.map { if (it.mandatory) "Да" else "Нет" })
        if (hasSubstitutes) put("Замена", lines.map { it.substituteOfLineId?.toString() ?: "—" })
        put("Потери %", lines.map { ErpFormat.percent(it.lossPct) })
    }
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "bom-lines",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                lines.getOrNull(idx)?.let(onClick)
            }
        }
    )
}

@Composable
fun BomExplodeLinesGrid(
    lines: List<BomExplodeLineDto>,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Ур." to lines.map { it.level.toString() },
        "Код" to lines.map { it.code },
        "Название" to lines.map { "${"  ".repeat((it.level - 1).coerceAtLeast(0))}${it.name}" },
        "Ед." to lines.map { it.unit },
        "На ед." to lines.map { ErpFormat.qty(it.quantityPerParent) },
        "Требуется" to lines.map { ErpFormat.qty(it.requiredQuantity) }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "bom-explode",
        config = gridConfig,
        onClick = { }
    )
}

@Composable
fun BomMaterialSummaryGrid(
    summary: List<BomMaterialSummaryDto>,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Код" to summary.map { it.code },
        "Название" to summary.map { it.name },
        "Ед." to summary.map { it.unit },
        "Требуется" to summary.map { ErpFormat.qty(it.totalRequired) },
        "Доступно" to summary.map { ErpFormat.qty(it.stockAvailable) },
        "Резерв" to summary.map { ErpFormat.qty(it.stockReserved) },
        "Ожидается" to summary.map { ErpFormat.qty(it.expectedReceipt) },
        "Дефицит" to summary.map { ErpFormat.qty(it.deficit) },
        "Закупка" to summary.map { ErpFormat.qty(it.recommendedPurchase) }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "bom-material-summary",
        config = gridConfig,
        onClick = { }
    )
}
