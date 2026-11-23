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
        "Сумма с НДС" to orders.map { ErpFormat.money(it.totals?.grossTotal, it.currency) },
        "Заказано" to orders.map { ErpFormat.qty(it.totals?.orderedQuantity) },
        "Поставлено" to orders.map { ErpFormat.qty(it.totals?.deliveredQuantity) },
        "Ожидается" to orders.map { ErpFormat.dateRu(it.expectedAt) }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "purchase-orders",
        config = ordersGridConfig,
        onClick = { selected ->
            selected.firstOrNull()?.first?.let { idx ->
                orders.getOrNull(idx)?.let(onClick)
            }
        }
    )
}

@Composable
fun PurchaseOrderLinesGrid(
    order: PurchaseOrderDto,
    modifier: Modifier = Modifier
) {
    val lines = order.lines
    val data = mapOf(
        "№" to lines.map { it.lineNo.toString() },
        "Код" to lines.map { it.nomenclatureCode ?: "—" },
        "Наименование" to lines.map { it.nomenclatureName ?: "—" },
        "Заказано" to lines.map { "${ErpFormat.qty(it.quantity)} ${it.unit}" },
        "Поставлено" to lines.map { "${ErpFormat.qty(it.deliveredQuantity)} ${it.unit}" },
        "Осталось" to lines.map { "${ErpFormat.qty(it.remainingQuantity)} ${it.unit}" },
        "Цена" to lines.map { ErpFormat.money(it.unitPrice, order.currency) },
        "НДС" to lines.map { ErpFormat.percent(it.vatRate) },
        "Сумма" to lines.map { ErpFormat.money(it.lineTotal, order.currency) }
    )
    PersistentDataGrid(data = data, modifier = modifier, tableKey = "purchase-order-lines", config = ordersGridConfig, onClick = { })
}

@Composable
fun PurchaseDeliveriesGrid(
    deliveries: List<PurchaseDeliveryDto>,
    statuses: Map<Long, org.example.project.dto.PurchaseOrderStatus>,
    onClick: (PurchaseDeliveryDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Номер" to deliveries.map { it.number },
        "Заказ" to deliveries.map { it.orderNumber ?: "Заказ #${it.orderId}" },
        "Статус заказа" to deliveries.map { statuses[it.orderId]?.localizedName() ?: "—" },
        "Дата" to deliveries.map { ErpFormat.dateRu(it.deliveredAt) },
        "Статус поставки" to deliveries.map { it.status.localizedName() },
        "Позиций" to deliveries.map { it.lines.size.toString() },
        "Основание" to deliveries.map { it.sourceDocument ?: "—" }
    )
    PersistentDataGrid(
        data = data,
        modifier = modifier,
        tableKey = "purchase-deliveries",
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

@Composable
fun PurchaseOrderHistoryGrid(
    history: List<PurchaseOrderHistoryDto>,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Действие" to history.map { it.action.historyActionTitle() },
        "Было" to history.map { it.fromStatus?.let(::statusTitle) ?: "—" },
        "Стало" to history.map { it.toStatus?.let(::statusTitle) ?: "—" },
        "Основание" to history.map { it.reason ?: "—" },
        "Когда" to history.map { ErpFormat.dateTimeRu(it.changedAt) }
    )
    PersistentDataGrid(data = data, modifier = modifier, tableKey = "purchase-order-history", config = ordersGridConfig, onClick = { })
}

private fun statusTitle(raw: String): String {
    val status = org.example.project.dto.PurchaseOrderStatus.entries.firstOrNull { it.name == raw }
    return status?.localizedName() ?: raw
}
