package org.example.project.ui.wms

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.tables.PersistentDataGrid
import org.example.project.ui.theme.ErpFormat
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.ReservationDto
import org.example.project.dto.StockBalanceDto
import org.example.project.dto.StorageLocationDto
import org.example.project.dto.WarehouseDto
import org.example.project.dto.NomenclatureDto
import org.example.project.dto.IssueOrderDto
import org.example.project.dto.ReturnOrderDto
import org.example.project.dto.TransferDto
import org.example.project.dto.WriteOffDto
import org.example.project.dto.InventoryDto

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
        "Код" to warehouses.map { it.code },
        "Название" to warehouses.map { it.name },
        "Адрес" to warehouses.map { it.address.orEmpty() },
        "Активен" to warehouses.map { if (it.active) "Да" else "Нет" }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "wms-warehouses",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                warehouses.getOrNull(idx)?.let(onClick)
            }
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
        "Код" to locations.map { it.code },
        "Название" to locations.map { it.name },
        "Тип" to locations.map { it.kind.localizedName() },
        "Назначение" to locations.map { it.purpose.localizedName() },
        "Ёмкость" to locations.map { it.capacity.orEmpty() }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "wms-locations",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                locations.getOrNull(idx)?.let(onClick)
            }
        }
    )
}

@Composable
fun NomenclatureGrid(
    items: List<NomenclatureDto>,
    onClick: (NomenclatureDto) -> Unit,
    modifier: Modifier = Modifier,
    showSearch: Boolean = true
) {
    val data = buildMap<String, List<Any?>> {
        put("Код", items.map { it.code })
        put("Название", items.map { it.name })
        put("Тип", items.map { it.type.localizedName() })
        put("Ед.изм.", items.map { it.unit })
        // Колонка «Архив» нужна, только когда в списке есть архивные позиции.
        if (items.any { it.archived }) put("Архив", items.map { if (it.archived) "Да" else "Нет" })
    }
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "wms-nomenclature",
        config = gridConfig,
        showSearch = showSearch,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                items.getOrNull(idx)?.let(onClick)
            }
        }
    )
}

@Composable
fun StockGrid(
    stock: List<StockBalanceDto>,
    warehouses: List<WarehouseDto> = emptyList(),
    nomenclature: List<NomenclatureDto> = emptyList(),
    modifier: Modifier = Modifier,
    onClick: (StockBalanceDto) -> Unit = {}
) {
    val hasLot = stock.any { !it.lotNumber.isNullOrBlank() }
    val hasSerial = stock.any { !it.serialNumber.isNullOrBlank() }
    val data = buildMap<String, List<Any?>> {
        put("Склад", stock.map { s -> warehouses.find { it.id == s.warehouseId }?.name ?: "Склад #${s.warehouseId}" })
        put("Ячейка", stock.map { it.locationCode })
        put("Номенклатура", stock.map { s ->
            nomenclature.find { it.id == s.nomenclatureId }?.let { "${it.code} · ${it.name}" } ?: "Поз. #${s.nomenclatureId}"
        })
        put("Ед.", stock.map { s -> nomenclature.find { it.id == s.nomenclatureId }?.unit.orEmpty() })
        if (hasLot) put("Партия", stock.map { it.lotNumber.orEmpty() })
        if (hasSerial) put("Серия", stock.map { it.serialNumber.orEmpty() })
        put("Физ.", stock.map { ErpFormat.qty(it.physicalQty) })
        put("Резерв", stock.map { ErpFormat.qty(it.reservedQty) })
        put("Доступно", stock.map { ErpFormat.qty(it.availableQty) })
    }
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "wms-stock",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx -> stock.getOrNull(idx)?.let(onClick) }
        }
    )
}

@Composable
fun ReservationsGrid(
    reservations: List<ReservationDto>,
    onClick: (ReservationDto) -> Unit,
    modifier: Modifier = Modifier,
    warehouses: List<WarehouseDto> = emptyList(),
    nomenclature: List<NomenclatureDto> = emptyList()
) {
    val data = mapOf(
        "Номер" to reservations.map { it.number },
        "Номенклатура" to reservations.map { r ->
            nomenclature.find { it.id == r.nomenclatureId }?.let { "${it.code} · ${it.name}" } ?: "Поз. #${r.nomenclatureId}"
        },
        "Склад" to reservations.map { r -> warehouses.find { it.id == r.warehouseId }?.name ?: "Склад #${r.warehouseId}" },
        "Основание" to reservations.map { it.reasonType.localizedName() },
        "Кол-во" to reservations.map { ErpFormat.qty(it.quantity) },
        "Выдано" to reservations.map { ErpFormat.qty(it.issuedQuantity) },
        "Статус" to reservations.map { it.status.localizedName() }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "wms-reservations",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                reservations.getOrNull(idx)?.let(onClick)
            }
        }
    )
}

@Composable
fun TransfersGrid(
    transfers: List<TransferDto>,
    onClick: (TransferDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Номер" to transfers.map { it.number },
        "Вид" to transfers.map { it.kind.localizedName() },
        "Кол-во" to transfers.map { ErpFormat.qty(it.quantity) },
        "Принято" to transfers.map { ErpFormat.qty(it.receivedQuantity) },
        "Расхождение" to transfers.map { ErpFormat.qty(it.discrepancyQuantity) },
        "Статус" to transfers.map { it.status.localizedName() }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "wms-transfers",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                transfers.getOrNull(idx)?.let(onClick)
            }
        }
    )
}

@Composable
fun IssuesGrid(
    issues: List<IssueOrderDto>,
    onClick: (IssueOrderDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Номер" to issues.map { it.number },
        "Основание" to issues.map { it.reasonType.localizedName() },
        "Статус" to issues.map { it.status.localizedName() },
        "Выдано" to issues.map { ErpFormat.dateRu(it.issuedAt) }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "wms-issues",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                issues.getOrNull(idx)?.let(onClick)
            }
        }
    )
}

@Composable
fun ReturnsGrid(
    returns: List<ReturnOrderDto>,
    onClick: (ReturnOrderDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Номер" to returns.map { it.number },
        "Причина" to returns.map { it.reason },
        "Статус" to returns.map { it.status.localizedName() },
        "Принято" to returns.map { ErpFormat.dateRu(it.receivedAt) }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "wms-returns",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                returns.getOrNull(idx)?.let(onClick)
            }
        }
    )
}

@Composable
fun WriteOffsGrid(
    writeOffs: List<WriteOffDto>,
    onClick: (WriteOffDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Номер" to writeOffs.map { it.number },
        "Причина" to writeOffs.map { it.reasonType.localizedName() },
        "Количество" to writeOffs.map { ErpFormat.qty(it.totalQuantity) },
        "Сверх лимита" to writeOffs.map { if (it.overLimit) "Да" else "Нет" },
        "Статус" to writeOffs.map { it.status.localizedName() },
        "Проведено" to writeOffs.map { ErpFormat.dateRu(it.postedAt) }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "wms-writeoffs",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                writeOffs.getOrNull(idx)?.let(onClick)
            }
        }
    )
}

@Composable
fun InventoriesGrid(
    inventories: List<InventoryDto>,
    onClick: (InventoryDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Номер" to inventories.map { it.number },
        "Область" to inventories.map { it.scopeType.localizedName() },
        "Статус" to inventories.map { it.status.localizedName() },
        "Срез" to inventories.map { ErpFormat.dateRu(it.bookedAt) },
        "Проведено" to inventories.map { ErpFormat.dateRu(it.postedAt) }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "wms-inventories",
        config = gridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                inventories.getOrNull(idx)?.let(onClick)
            }
        }
    )
}
