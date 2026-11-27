package org.example.project.ui.supplier

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.example.project.ui.tables.PersistentDataGrid
import org.example.project.ui.theme.ErpFormat
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.PurchaseDeliveryDto
import org.example.project.dto.PurchaseOrderDto
import org.example.project.dto.PurchaseOrderHistoryDto

private val ordersGridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun PurchaseOrdersGrid(
    orders: List<PurchaseOrderDto>,
    onClick: (PurchaseOrderDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Номер" to orders.map { it.number },
        "Поставщик" to orders.map { it.supplierName ?: "Поставщик #${it.supplierId}" },
        "Статус" to orders.map { it.status.localizedName() },
        "Сумма" to orders.map { it.totals?.grossTotal.asMoney(it.currency) },
        "Поставлено" to orders.map {
            "${ErpFormat.qty(it.totals?.deliveredQuantity)} / ${ErpFormat.qty(it.totals?.orderedQuantity)}"
        }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "purchase-orders-v3",
        config = ordersGridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                orders.getOrNull(idx)?.let(onClick)
            }
        }
    )
}

@Composable
fun PurchaseDeliveriesGrid(
    deliveries: List<PurchaseDeliveryDto>,
    statuses: Map<Long, org.example.project.dto.PurchaseOrderStatus>,
    onClick: (PurchaseDeliveryDto) -> Unit,
    modifier: Modifier = Modifier,
    orders: List<PurchaseOrderDto> = emptyList()
) {
    val ordersById = orders.associateBy { it.id }
    val data = linkedMapOf<String, List<String>>(
        "Поставка" to deliveries.map { it.number },
        "Заказ" to deliveries.map { it.orderNumber ?: "№${it.orderId}" },
        "Поставщик" to deliveries.map { ordersById[it.orderId]?.supplierName ?: "—" },
        "Дата" to deliveries.map { ErpFormat.dateRu(it.deliveredAt) },
        "Состав" to deliveries.map { delivery ->
            val order = ordersById[delivery.orderId]
            delivery.lines.joinToString("; ") { line ->
                val orderLine = order?.lines?.firstOrNull { it.id == line.orderLineId }
                val name = line.nomenclatureName ?: orderLine?.nomenclatureName ?: "Позиция"
                "$name — ${ErpFormat.qty(line.quantity)} ${orderLine?.unit.orEmpty()}".trim()
            }.ifEmpty { "—" }
        },
        "Статус" to deliveries.map { it.status.localizedName() }
    )
    // Колонка «Основание» нужна, только если документ-основание указан хотя бы в одной поставке.
    if (deliveries.any { !it.sourceDocument.isNullOrBlank() }) {
        data["Основание"] = deliveries.map { it.sourceDocument ?: "—" }
    }
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "purchase-deliveries-v3",
        config = ordersGridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                deliveries.getOrNull(idx)?.let(onClick)
            }
        }
    )
}

@Composable
fun PurchaseDeliveryLinesGrid(
    delivery: PurchaseDeliveryDto,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Наименование" to delivery.lines.map { it.nomenclatureName ?: "—" },
        "Количество" to delivery.lines.map { ErpFormat.qty(it.quantity) },
        "Примечание" to delivery.lines.map { it.note ?: "—" }
    )
    PersistentDataGrid(data = data, modifier = modifier, tableKey = "purchase-delivery-lines", config = ordersGridConfig, onClick = { })
}
