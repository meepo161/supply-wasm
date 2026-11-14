package org.example.project.ui.bom

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.unchil.un7datagrid.Un7KCMPDataGrid
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.BomExplodeLineDto
import org.example.project.dto.BomLineDto
import org.example.project.dto.BomMaterialSummaryDto
import org.example.project.dto.BomRevisionSummaryDto
import org.example.project.dto.ProductModelDto

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("10", "20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun ProductModelsGrid(
    models: List<ProductModelDto>,
    onClick: (ProductModelDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to models.map { it.id.toString() },
        "Код" to models.map { it.code },
        "Название" to models.map { it.name },
        "Статус" to models.map { it.status.localizedName() },
        "Версия" to models.map { it.version.toString() }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            models.firstOrNull { it.id.toString() == id }?.let(onClick)
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
        "ID" to revisions.map { it.id.toString() },
        "Номер" to revisions.map { it.revisionNumber },
        "Статус" to revisions.map { it.status.localizedName() },
        "Утвердил" to revisions.map { it.approvedBy?.toString() ?: "—" },
        "Версия" to revisions.map { it.version.toString() }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            revisions.firstOrNull { it.id.toString() == id }?.let(onClick)
        }
    )
}

@Composable
fun BomLinesGrid(
    lines: List<BomLineDto>,
    onClick: (BomLineDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to lines.map { it.id.toString() },
        "Родитель" to lines.map { it.parentLineId?.toString() ?: "—" },
        "Род.ном." to lines.map { it.parentNomenclatureId.toString() },
        "Доч.ном." to lines.map { it.childNomenclatureId.toString() },
        "Кол-во" to lines.map { it.quantity },
        "Ед." to lines.map { it.unit },
        "Поз." to lines.map { it.position ?: "—" },
        "Ур." to lines.map { it.level.toString() },
        "Обяз." to lines.map { if (it.mandatory) "Да" else "Нет" },
        "Замена" to lines.map { it.substituteOfLineId?.toString() ?: "—" },
        "Расход" to lines.map { it.consumptionRate ?: "—" },
        "Потери %" to lines.map { it.lossPct }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            lines.firstOrNull { it.id.toString() == id }?.let(onClick)
        }
    )
}

@Composable
fun BomExplodeLinesGrid(
    lines: List<BomExplodeLineDto>,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to lines.map { it.id.toString() },
        "Строка" to lines.map { it.lineId.toString() },
        "Родитель" to lines.map { it.parentLineId?.toString() ?: "—" },
        "Ур." to lines.map { it.level.toString() },
        "Ном." to lines.map { it.nomenclatureId.toString() },
        "Код" to lines.map { it.code },
        "Название" to lines.map { it.name },
        "Ед." to lines.map { it.unit },
        "На ед." to lines.map { it.quantityPerParent },
        "Требуется" to lines.map { it.requiredQuantity }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
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
        "ID" to summary.map { it.nomenclatureId.toString() },
        "Код" to summary.map { it.code },
        "Название" to summary.map { it.name },
        "Ед." to summary.map { it.unit },
        "Требуется" to summary.map { it.totalRequired },
        "Доступно" to summary.map { it.stockAvailable },
        "Резерв" to summary.map { it.stockReserved },
        "Ожидается" to summary.map { it.expectedReceipt },
        "Дефицит" to summary.map { it.deficit },
        "Закупка" to summary.map { it.recommendedPurchase }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { }
    )
}
