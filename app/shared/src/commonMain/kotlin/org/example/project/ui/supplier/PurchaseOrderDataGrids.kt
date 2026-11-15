package org.example.project.ui.supplier

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.unchil.un7datagrid.Un7KCMPDataGrid
import com.unchil.un7datagrid.Un7KCMPDataGridConfig
import org.example.project.dto.PurchaseDeliveryDto
import org.example.project.dto.PurchaseOrderDto
import org.example.project.dto.PurchaseOrderHistoryDto

private val ordersGridConfig = Un7KCMPDataGridConfig(
    isUsableHaptic = false,
    pageSizeList = listOf("10", "20", "50", "100"),
    defaultPageSizeListIndex = 0
)

@Composable
fun PurchaseOrdersGrid(
    orders: List<PurchaseOrderDto>,
    onClick: (PurchaseOrderDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to orders.map { it.id.toString() },
        "Номер" to orders.map { it.number },
        "Поставщик" to orders.map { it.supplierName ?: it.supplierId.toString() },
        "Статус" to orders.map { it.status.localizedName() },
        "Сумма с НДС" to orders.map { it.totals?.grossTotal.asMoney(it.currency) },
        "Заказано" to orders.map { it.totals?.orderedQuantity ?: "—" },
        "Поставлено" to orders.map { it.totals?.deliveredQuantity ?: "—" },
        "Ожидается" to orders.map { it.expectedAt.asDate() }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = ordersGridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            orders.firstOrNull { it.id.toString() == id }?.let(onClick)
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
        "Код" to lines.map { it.nomenclatureCode ?: it.nomenclatureId.toString() },
        "Наименование" to lines.map { it.nomenclatureName ?: "—" },
        "Заказано" to lines.map { "${it.quantity} ${it.unit}" },
        "Поставлено" to lines.map { "${it.deliveredQuantity} ${it.unit}" },
        "Осталось" to lines.map { "${it.remainingQuantity} ${it.unit}" },
        "Цена" to lines.map { it.unitPrice.asMoney(order.currency) },
        "НДС" to lines.map { "${it.vatRate.trimEnd('0').trimEnd('.')}%" },
        "Сумма" to lines.map { it.lineTotal.asMoney(order.currency) }
    )
    Un7KCMPDataGrid(data = data, modifier = modifier, config = ordersGridConfig, onClick = { })
}

@Composable
fun PurchaseDeliveriesGrid(
    deliveries: List<PurchaseDeliveryDto>,
    statuses: Map<Long, org.example.project.dto.PurchaseOrderStatus>,
    onClick: (PurchaseDeliveryDto) -> Unit,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "ID" to deliveries.map { it.id.toString() },
        "Номер" to deliveries.map { it.number },
        "Заказ" to deliveries.map { it.orderNumber ?: it.orderId.toString() },
        "Статус заказа" to deliveries.map { statuses[it.orderId]?.localizedName() ?: "—" },
        "Дата" to deliveries.map { it.deliveredAt.asDate() },
        "Статус поставки" to deliveries.map { it.status.localizedName() },
        "Позиций" to deliveries.map { it.lines.size.toString() },
        "Основание" to deliveries.map { it.sourceDocument ?: "—" }
    )
    Un7KCMPDataGrid(
        data = data,
        modifier = modifier,
        config = ordersGridConfig,
        onClick = { selected ->
            val id = selected.firstOrNull()?.second?.firstOrNull() as? String
            deliveries.firstOrNull { it.id.toString() == id }?.let(onClick)
        }
    )
}

@Composable
fun PurchaseDeliveryLinesGrid(
    delivery: PurchaseDeliveryDto,
    modifier: Modifier = Modifier
) {
    val data = mapOf(
        "Позиция заказа" to delivery.lines.map { it.orderLineId.toString() },
        "Наименование" to delivery.lines.map { it.nomenclatureName ?: "—" },
        "Количество" to delivery.lines.map { it.quantity },
        "Примечание" to delivery.lines.map { it.note ?: "—" }
    )
    Un7KCMPDataGrid(data = data, modifier = modifier, config = ordersGridConfig, onClick = { })
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
        "Когда" to history.map { it.changedAt?.replace('T', ' ')?.take(19) ?: "—" }
    )
    Un7KCMPDataGrid(data = data, modifier = modifier, config = ordersGridConfig, onClick = { })
}

private fun statusTitle(raw: String): String {
    val status = org.example.project.dto.PurchaseOrderStatus.entries.firstOrNull { it.name == raw }
    return status?.localizedName() ?: raw
}
