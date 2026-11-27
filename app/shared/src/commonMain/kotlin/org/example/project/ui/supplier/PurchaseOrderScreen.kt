package org.example.project.ui.supplier

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import org.example.project.ui.theme.Tab
import org.example.project.ui.theme.ErpSplitRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.PurchaseOrderRepository
import org.example.project.dto.PurchaseOrderDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.DetailRow
import org.example.project.ui.theme.EmptyState
import org.example.project.ui.theme.ErpFormat
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.GradientSpinner
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PageHeader
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SectionHeader
import org.example.project.ui.theme.SoftButton
import org.koin.compose.koinInject

@Composable
fun PurchaseOrderScreen(
    viewModel: PurchaseOrderViewModel,
    modifier: Modifier = Modifier,
    invoiceViewModel: SupplierInvoiceViewModel? = null,
    embedded: Boolean = false,
    onOpenOrdersTab: () -> Unit = {},
    onOpenInvoicesTab: () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()
    val tabs = PurchaseOrderTab.entries.filter { it != PurchaseOrderTab.INVOICES || invoiceViewModel?.canRead() == true }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.refreshOnOpen()
        if (invoiceViewModel?.canRead() == true) {
            invoiceViewModel.load()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (!embedded) {
            PageHeader(
                title = "Заказы поставщикам",
                glyph = ErpIconGlyph.Documents,
                gradient = Gradients.Berry,
                subtitle = when (state.activeTab) {
                    PurchaseOrderTab.ORDERS -> "Реестр заказов из «Снабжения (Заявки)» и по выбранным предложениям"
                    PurchaseOrderTab.DELIVERIES -> "Журнал поставок: что, от кого и в каком количестве пришло по заказам"
                    PurchaseOrderTab.INVOICES -> "Счета поставщиков: сверка с заказом и передача поступления на склад"
                },
                actions = {
                    if (state.activeTab == PurchaseOrderTab.ORDERS && viewModel.canOrder()) {
                        GradientButton(
                            text = "Новый заказ",
                            onClick = { viewModel.openCreate() },
                            gradient = Gradients.Berry,
                            leadingIcon = ErpIconGlyph.Plus,
                            compact = true
                        )
                    }
                }
            )
            Spacer(Modifier.height(8.dp))
            ScrollableTabRow(selectedTabIndex = tabs.indexOf(state.activeTab).coerceAtLeast(0)) {
                tabs.forEach { tab ->
                    Tab(
                        selected = state.activeTab == tab,
                        onClick = { viewModel.selectTab(tab) },
                        text = { Text(tab.title) }
                    )
                }
            }
        }
        state.successMessage?.let { message ->
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
                PurchaseOrderSuccessNote(message, viewModel::dismissSuccess)
            }
        }
        state.operationError?.takeIf { state.activeTab != PurchaseOrderTab.INVOICES && state.activeDialog == null }?.let { error ->
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
                ErrorMessageCard(error = error)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 12.dp)
        ) {
            when (state.activeTab) {
                PurchaseOrderTab.ORDERS -> OrdersPane(state, viewModel, invoiceViewModel, onOpenInvoicesTab)
                PurchaseOrderTab.DELIVERIES -> DeliveriesPane(state, viewModel, onOpenOrdersTab)
                PurchaseOrderTab.INVOICES -> invoiceViewModel?.let { SupplierInvoicesPane(it) }
            }
        }
    }

    PurchaseOrderDialogs(state = state, viewModel = viewModel)
    invoiceViewModel?.let { SupplierInvoiceDialogs(it) }
}

@Composable
private fun OrdersPane(
    state: PurchaseOrderUiState,
    viewModel: PurchaseOrderViewModel,
    invoiceViewModel: SupplierInvoiceViewModel?,
    onOpenInvoicesTab: () -> Unit = {}
) {
    ErpSplitRow {
        GlassCard(modifier = Modifier.pane(0.5f), contentPadding = 14.dp) {
            SectionHeader(
                title = "Заказы",
                count = state.orders.size,
                accent = Brand.Violet,
                subtitle = state.statusFilter?.localizedName() ?: "Все статусы"
            )
            Spacer(Modifier.height(8.dp))
            PurchaseOrderStatusFilterDropdown(
                selected = state.statusFilter,
                onSelect = viewModel::updateStatusFilter,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            when {
                state.isOrdersLoading && state.orders.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) { GradientSpinner() }
                state.orders.isEmpty() -> EmptyState(
                    title = "Заказов пока нет",
                    text = "Заказ появляется здесь автоматически после оформления или оплаты в «Снабжение (Заявки)», либо создаётся кнопкой «Новый заказ» по выбранному предложению",
                    glyph = ErpIconGlyph.Documents,
                    accent = Brand.Violet
                )
                else -> PurchaseOrdersGrid(
                    orders = state.orders,
                    onClick = { viewModel.selectOrder(it.id) },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
            }
        }
        OrderCardPane(state, viewModel, invoiceViewModel, onOpenInvoicesTab, Modifier.pane(0.5f, narrowHeight = 640.dp))
    }
}

@Composable
private fun OrderCardPane(
    state: PurchaseOrderUiState,
    viewModel: PurchaseOrderViewModel,
    invoiceViewModel: SupplierInvoiceViewModel?,
    onOpenInvoicesTab: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val order = state.selectedOrder
    GlassCard(modifier = modifier, contentPadding = 16.dp) {
        if (order == null) {
            EmptyState(
                title = "Выберите заказ",
                text = "Карточка покажет условия, позиции «заказано/поставлено/осталось», историю и поставки",
                glyph = ErpIconGlyph.Documents,
                accent = Brand.Violet
            )
            return@GlassCard
        }
        if (state.isOrderLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { GradientSpinner() }
            return@GlassCard
        }
        val purchaseOrderRepository: PurchaseOrderRepository = koinInject()
        val payScope = rememberCoroutineScope()
        var markingPaid by remember(order.id) { mutableStateOf(false) }
        var paidError by remember(order.id) { mutableStateOf<org.example.project.dto.ApiError?>(null) }
        var paidMessage by remember(order.id) { mutableStateOf<String?>(null) }

        OrderHeader(order)
        Spacer(Modifier.height(8.dp))
        OrderActions(
            order = order,
            state = state,
            viewModel = viewModel,
            markingPaid = markingPaid,
            onMarkPaid = {
                markingPaid = true
                paidError = null
                paidMessage = null
                payScope.launch {
                    when (val res = purchaseOrderRepository.markOrderPaid(order.id)) {
                        is ApiResult.Success -> {
                            markingPaid = false
                            paidMessage = "Заказ ${res.data.number} отмечен как оплаченный"
                            viewModel.selectOrder(res.data.id)
                            viewModel.loadOrders()
                        }
                        is ApiResult.Error -> {
                            markingPaid = false
                            paidError = res.error
                        }
                    }
                }
            }
        )
        paidMessage?.let { msg ->
            Spacer(Modifier.height(6.dp))
            PurchaseOrderSuccessNote(msg) { paidMessage = null }
        }
        paidError?.let { err ->
            Spacer(Modifier.height(6.dp))
            ErrorMessageCard(error = err)
        }
        Spacer(Modifier.height(10.dp))
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            SectionHeader(title = "Условия", accent = Brand.Violet)
            DetailRow("Поставщик", order.supplierName ?: order.supplierId.toString())
            DetailRow("Предложение", order.offerNumber ?: order.offerId.toString())
            val linkedInvoiceNo = invoiceViewModel?.uiState?.value?.invoices
                ?.firstOrNull { it.purchaseOrderId == order.id }?.number
            val demandsText = when {
                order.demandNumbers.isNotEmpty() -> order.demandNumbers.joinToString(", ")
                order.demandIds.isNotEmpty() -> order.demandIds.joinToString(", ") { "№$it" }
                else -> null
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    OrderCompactFact("Ответственный", viewModel.userName(order.responsibleId), Modifier.weight(1f))
                    OrderCompactFact(
                        "Оплата",
                        if (order.paidAt != null) {
                            "Оплачено ${ErpFormat.dateTimeRu(order.paidAt)}" + (order.paidByName?.let { " ($it)" } ?: "")
                        } else {
                            "Не оплачен"
                        },
                        Modifier.weight(1f),
                        valueColor = if (order.paidAt != null) Brand.Teal else Brand.Amber
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    OrderCompactFact("Дата заказа", order.orderedAt.asDate(), Modifier.weight(1f))
                    OrderCompactFact("Ожидаемая поставка", order.expectedAt.asDate(), Modifier.weight(1f))
                }
                val invoiceText = order.invoiceNo ?: linkedInvoiceNo
                if (invoiceText != null || demandsText != null) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        OrderCompactFact("Счёт", invoiceText ?: "—", Modifier.weight(1f))
                        OrderCompactFact("Потребности", demandsText ?: "—", Modifier.weight(1f))
                    }
                }
                if (order.approvedBy != null || order.approvedAt != null) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        OrderCompactFact("Согласовал", order.approvedBy?.let(viewModel::userName) ?: "—", Modifier.weight(1f))
                        OrderCompactFact("Согласовано", order.approvedAt.asDate(), Modifier.weight(1f))
                    }
                }
                order.deliveryAddress?.takeIf { it.isNotBlank() }?.let { DetailRow("Адрес доставки", it) }
                order.paymentTerms?.takeIf { it.isNotBlank() }?.let { DetailRow("Условия оплаты", it) }
                order.contractNo?.takeIf { it.isNotBlank() }?.let { DetailRow("Договор", it) }
                if (order.reapprovalRequired) {
                    DetailRow(
                        "Требуется согласование",
                        order.reapprovalReason ?: "существенные условия изменены",
                        valueColor = Brand.Amber
                    )
                }
                order.note?.takeIf { it.isNotBlank() }?.let { DetailRow("Примечание", it) }
            }

            Spacer(Modifier.height(12.dp))
            SectionHeader(
                title = "Позиции",
                count = order.lines.size,
                accent = Brand.Blue,
                subtitle = "Заказано, поставлено и осталось"
            )
            if (order.lines.isEmpty()) {
                Text(
                    "Позиций нет",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OrderLinesList(order)
                Spacer(Modifier.height(8.dp))
                TotalsRow(order)
            }

            invoiceViewModel?.let { invoices ->
                Spacer(Modifier.height(12.dp))
                OrderInvoicesSection(order, invoices, onOpenInvoices = {
                    onOpenInvoicesTab()
                    viewModel.selectTab(PurchaseOrderTab.INVOICES)
                })
            }

            Spacer(Modifier.height(12.dp))
            SectionHeader(
                title = "Поставки по заказу",
                count = state.deliveries.size,
                accent = Brand.Mint,
                action = {
                    if (viewModel.canReceive() && order.status in deliveryAllowed) {
                        SoftButton(text = "Принять", onClick = viewModel::openDelivery, tint = Brand.Mint)
                    }
                }
            )
            if (state.deliveries.isEmpty()) {
                Text(
                    "Поставок ещё не было",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                state.deliveries.forEach { delivery ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${delivery.number} · ${delivery.deliveredAt.asDate()}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = deliveryItemsText(delivery, order) +
                                    (delivery.sourceDocument?.let { " · основание: $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        PillBadge(text = delivery.status.localizedName(), accent = delivery.status.accentColor())
                        if (delivery.status == org.example.project.dto.PurchaseDeliveryStatus.REGISTERED &&
                            viewModel.canReceive()
                        ) {
                            Spacer(Modifier.width(6.dp))
                            SoftButton(
                                text = "Отменить",
                                onClick = { viewModel.openCancelDelivery(delivery.id) },
                                tint = Brand.Rose
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            SectionHeader(title = "История", count = state.history.size, accent = Brand.Amber)
            if (state.history.isEmpty()) {
                Text(
                    "История пуста",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OrderHistoryList(state.history, viewModel::userName)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

private val deliveryAllowed = setOf(
    org.example.project.dto.PurchaseOrderStatus.SENT,
    org.example.project.dto.PurchaseOrderStatus.PARTIALLY_DELIVERED
)

@Composable
private fun OrderHeader(order: PurchaseOrderDto) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = order.number,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = listOfNotNull(
                    order.supplierName ?: "поставщик #${order.supplierId}",
                    order.totals?.grossTotal?.asMoney(order.currency) ?: org.example.project.ui.theme.ErpFormat.currencySymbol(order.currency)
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (order.paidAt != null) {
            PillBadge(
                text = "Оплачено ${ErpFormat.dateRu(order.paidAt)}",
                accent = Brand.Mint,
                filled = true
            )
            Spacer(Modifier.width(6.dp))
        } else if (order.status != org.example.project.dto.PurchaseOrderStatus.CANCELLED) {
            PillBadge(
                text = "Не оплачен",
                accent = Brand.Amber
            )
            Spacer(Modifier.width(6.dp))
        }
        PillBadge(
            text = order.status.localizedName(),
            accent = order.status.accentColor(),
            filled = order.status == org.example.project.dto.PurchaseOrderStatus.SENT
        )
        if (order.reapprovalRequired) {
            Spacer(Modifier.width(6.dp))
            PillBadge(text = "Нужно согласование", accent = Brand.Amber)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderActions(
    order: PurchaseOrderDto,
    state: PurchaseOrderUiState,
    viewModel: PurchaseOrderViewModel,
    markingPaid: Boolean = false,
    onMarkPaid: () -> Unit = {}
) {
    val editable = order.status in setOf(
        org.example.project.dto.PurchaseOrderStatus.DRAFT,
        org.example.project.dto.PurchaseOrderStatus.PENDING_APPROVAL,
        org.example.project.dto.PurchaseOrderStatus.APPROVED,
        org.example.project.dto.PurchaseOrderStatus.SENT
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (viewModel.canOrder() &&
            order.paidAt == null &&
            order.status != org.example.project.dto.PurchaseOrderStatus.CANCELLED
        ) {
            GradientButton(
                text = if (markingPaid) "Сохранение…" else "✓ Отметить «Оплачено»",
                onClick = onMarkPaid,
                gradient = Gradients.Emerald,
                compact = true,
                enabled = !markingPaid && !state.isActionRunning
            )
        }
        if (viewModel.canOrder() && editable) {
            SoftButton(text = "Править", onClick = viewModel::openEdit)
        }
        if (viewModel.canOrder() &&
            order.status in setOf(
                org.example.project.dto.PurchaseOrderStatus.DRAFT,
                org.example.project.dto.PurchaseOrderStatus.APPROVED
            )
        ) {
            SoftButton(
                text = "На согласование",
                onClick = viewModel::submit,
                tint = Brand.Amber,
                enabled = !state.isActionRunning
            )
        }
        if (viewModel.canApprove() && order.status == org.example.project.dto.PurchaseOrderStatus.PENDING_APPROVAL) {
            SoftButton(
                text = "Согласовать",
                onClick = viewModel::approve,
                tint = Brand.Teal,
                enabled = !state.isActionRunning
            )
        }
        if (viewModel.canOrder() && order.status == org.example.project.dto.PurchaseOrderStatus.APPROVED) {
            SoftButton(
                text = "Отправить поставщику",
                onClick = viewModel::send,
                tint = Brand.Blue,
                enabled = !state.isActionRunning && !order.reapprovalRequired
            )
        }
        if (viewModel.canOrder() &&
            order.status in setOf(
                org.example.project.dto.PurchaseOrderStatus.DELIVERED,
                org.example.project.dto.PurchaseOrderStatus.PARTIALLY_DELIVERED
            )
        ) {
            SoftButton(text = "Закрыть заказ", onClick = viewModel::openCloseOrder, tint = Brand.Slate)
        }
        if (viewModel.canOrder() && order.status in setOf(
                org.example.project.dto.PurchaseOrderStatus.DRAFT,
                org.example.project.dto.PurchaseOrderStatus.PENDING_APPROVAL,
                org.example.project.dto.PurchaseOrderStatus.APPROVED,
                org.example.project.dto.PurchaseOrderStatus.SENT
            )
        ) {
            SoftButton(text = "Отменить", onClick = viewModel::openCancelOrder, tint = Brand.Rose)
        }
    }
}

@Composable
private fun TotalsRow(order: PurchaseOrderDto) {
    val totals = order.totals ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PurchaseOrderTotalChip("Без НДС", totals.netTotal.asMoney(order.currency), Brand.Blue, Modifier.weight(1f))
        PurchaseOrderTotalChip("НДС", totals.vatTotal.asMoney(order.currency), Brand.Amber, Modifier.weight(1f))
        PurchaseOrderTotalChip("С НДС", totals.grossTotal.asMoney(order.currency), Brand.Mint, Modifier.weight(1f))
    }
}

@Composable
private fun PurchaseOrderTotalChip(label: String, value: String, accent: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = accent
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PurchaseOrderSuccessNote(text: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Brand.Mint.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = Brand.Teal,
            modifier = Modifier.weight(1f)
        )
        SoftButton(text = "Ок", onClick = onDismiss)
    }
}

@Composable
private fun DeliveriesPane(
    state: PurchaseOrderUiState,
    viewModel: PurchaseOrderViewModel,
    onOpenOrdersTab: () -> Unit = {}
) {
    var filterMode by remember { mutableStateOf(0) } // 0 = все, 1 = в пути, 2 = поставлены
    val orderById = remember(state.orders) { state.orders.associateBy { it.id } }
    val filteredDeliveries = remember(state.deliveries, orderById, filterMode) {
        state.deliveries.filter { d ->
            val st = orderById[d.orderId]?.status
            when (filterMode) {
                1 -> st == org.example.project.dto.PurchaseOrderStatus.SENT ||
                    st == org.example.project.dto.PurchaseOrderStatus.PARTIALLY_DELIVERED
                2 -> st == org.example.project.dto.PurchaseOrderStatus.DELIVERED ||
                    st == org.example.project.dto.PurchaseOrderStatus.CLOSED
                else -> true
            }
        }
    }
    GlassCard(modifier = Modifier.fillMaxSize(), contentPadding = 16.dp) {
        SectionHeader(
            title = "Все поставки",
            count = filteredDeliveries.size,
            accent = Brand.Mint,
            subtitle = "Поставки по всем загруженным заказам"
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "Все (${state.deliveries.size})", 1 to "В пути", 2 to "Поставлены").forEach { (idx, label) ->
                org.example.project.ui.theme.ErpFilterChip(
                    selected = filterMode == idx,
                    onClick = { filterMode = idx },
                    label = { Text(label) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            state.isOrdersLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { GradientSpinner() }
            filteredDeliveries.isEmpty() -> EmptyState(
                title = "Поставок нет",
                text = "Поставка регистрируется по отправленному заказу и может быть частичной",
                glyph = ErpIconGlyph.Warehouse,
                accent = Brand.Mint
            )
            else -> Column {
                PurchaseDeliveriesGrid(
                    deliveries = filteredDeliveries,
                    orders = state.orders,
                    statuses = state.orders.associate { it.id to it.status },
                    onClick = { delivery ->
                        onOpenOrdersTab()
                        viewModel.selectTab(PurchaseOrderTab.ORDERS)
                        viewModel.selectOrder(delivery.orderId)
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Строк поставок: " + state.deliveries.sumOf { it.lines.size } +
                        ". Нажмите на поставку, чтобы открыть заказ на вкладке «Заказы».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Позиции заказа списком: названия и числа переносятся, а не обрезаются, высота по содержимому. */
@Composable
private fun OrderLinesList(order: PurchaseOrderDto) {
    order.lines.forEach { line ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = "${line.lineNo}. " + listOfNotNull(line.nomenclatureCode, line.nomenclatureName)
                    .joinToString(" — ").ifEmpty { "Позиция ${line.lineNo}" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                LineFact("Заказано", "${org.example.project.ui.theme.ErpFormat.qty(line.quantity)} ${line.unit}")
                LineFact("Поставлено", "${org.example.project.ui.theme.ErpFormat.qty(line.deliveredQuantity)} ${line.unit}")
                LineFact("Осталось", "${org.example.project.ui.theme.ErpFormat.qty(line.remainingQuantity)} ${line.unit}")
            }
            Text(
                text = "Цена ${line.unitPrice.asMoney(order.currency)} · НДС " +
                    "${org.example.project.ui.theme.ErpFormat.percent(line.vatRate)} · сумма ${line.lineTotal.asMoney(order.currency)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LineFact(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

/** История заказа: когда, кто и что сделал; причина — отдельной строкой. */
@Composable
private fun OrderHistoryList(
    history: List<org.example.project.dto.PurchaseOrderHistoryDto>,
    userName: (Long) -> String
) {
    history.forEach { entry ->
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            val from = entry.fromStatus
            val to = entry.toStatus
            Text(
                text = entry.action.historyActionTitle() +
                    (if (from != null && to != null) ": ${from.statusTitle()} → ${to.statusTitle()}" else ""),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${org.example.project.ui.theme.ErpFormat.dateTimeRu(entry.changedAt)} · ${userName(entry.changedBy)}" +
                    (entry.reason?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun String.statusTitle(): String =
    org.example.project.dto.PurchaseOrderStatus.entries.firstOrNull { it.name == this }?.localizedName() ?: this

/** «Болт М8 — 10 шт; Гайка — 5 шт» по строкам поставки. */
private fun deliveryItemsText(
    delivery: org.example.project.dto.PurchaseDeliveryDto,
    order: PurchaseOrderDto
): String = delivery.lines.joinToString("; ") { line ->
    val orderLine = order.lines.firstOrNull { it.id == line.orderLineId }
    val name = line.nomenclatureName ?: orderLine?.nomenclatureName ?: "Позиция"
    "$name — ${org.example.project.ui.theme.ErpFormat.qty(line.quantity)} ${orderLine?.unit.orEmpty()}".trim()
}

@Composable
private fun OrderCompactFact(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = valueColor
        )
    }
}
