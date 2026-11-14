package org.example.project.ui.wms

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.unchil.un7datagrid.Un7KCMPDataGrid
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.ReservationDto
import org.example.project.dto.StockBalanceDto
import org.example.project.dto.StorageLocationDto
import org.example.project.dto.WarehouseDto
import org.example.project.dto.NomenclatureDto

private val gridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("10", "20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun WarehousesGrid(
    warehouses: List<WarehouseDto>,
    onClick: (WarehouseDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to warehouses.map { it.id.toString() },
        "Код" to warehouses.map { it.code },
        "Название" to warehouses.map { it.name },
        "Адрес" to warehouses.map { it.address ?: "—" },
        "Активен" to warehouses.map { if (it.active) "Да" else "Нет" }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            warehouses.firstOrNull { it.id.toString() == id }?.let(onClick)
        }
    )
}

@Composable
fun LocationsGrid(
    locations: List<StorageLocationDto>,
    onClick: (StorageLocationDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to locations.map { it.id.toString() },
        "Код" to locations.map { it.code },
        "Название" to locations.map { it.name },
        "Тип" to locations.map { it.kind.localizedName() },
        "Назначение" to locations.map { it.purpose.localizedName() },
        "Ёмкость" to locations.map { it.capacity ?: "—" }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            locations.firstOrNull { it.id.toString() == id }?.let(onClick)
        }
    )
}

@Composable
fun NomenclatureGrid(
    items: List<NomenclatureDto>,
    onClick: (NomenclatureDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to items.map { it.id.toString() },
        "Код" to items.map { it.code },
        "Название" to items.map { it.name },
        "Тип" to items.map { it.type.localizedName() },
        "Ед.изм." to items.map { it.unit },
        "Архив" to items.map { if (it.archived) "Да" else "Нет" }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            items.firstOrNull { it.id.toString() == id }?.let(onClick)
        }
    )
}

@Composable
fun StockGrid(
    stock: List<StockBalanceDto>,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to stock.map { it.id.toString() },
        "Склад ID" to stock.map { it.warehouseId.toString() },
        "Ячейка" to stock.map { it.locationCode },
        "Номенклатура ID" to stock.map { it.nomenclatureId.toString() },
        "Партия" to stock.map { it.lotNumber ?: "—" },
        "Серия" to stock.map { it.serialNumber ?: "—" },
        "Физ." to stock.map { it.physicalQty },
        "Резерв" to stock.map { it.reservedQty },
        "Доступно" to stock.map { it.availableQty }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { }
    )
}

@Composable
fun ReservationsGrid(
    reservations: List<ReservationDto>,
    onClick: (ReservationDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to reservations.map { it.id.toString() },
        "Номер" to reservations.map { it.number },
        "Основание" to reservations.map { it.reasonType.localizedName() },
        "Кол-во" to reservations.map { it.quantity },
        "Списано" to reservations.map { it.issuedQuantity },
        "Статус" to reservations.map { it.status.localizedName() }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = gridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            reservations.firstOrNull { it.id.toString() == id }?.let(onClick)
        }
    )
}
