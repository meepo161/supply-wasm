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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import org.example.project.ui.theme.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.dto.PurchaseOrderDto
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.DetailRow
import org.example.project.ui.theme.EmptyState
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.GradientSpinner
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PageHeader
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SectionHeader
import org.example.project.ui.theme.SoftButton

@Composable
fun PurchaseOrderScreen(
    viewModel: PurchaseOrderViewModel,
    modifier: Modifier = Modifier,
    invoiceViewModel: SupplierInvoiceViewModel? = null
) {
    val state by viewModel.uiState.collectAsState()
    val tabs = PurchaseOrderTab.entries.filter { it != PurchaseOrderTab.INVOICES || invoiceViewModel?.canRead() == true }

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = "Заказы поставщикам",
            glyph = ErpIconGlyph.Documents,
            gradient = Gradients.Berry,
            subtitle = "Заказ из выбранного предложения, согласование и частичные поставки",
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
        state.successMessage?.let { message ->
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
                PurchaseOrderSuccessNote(message, viewModel::dismissSuccess)
            }
        }
        state.operationError?.takeIf { state.activeTab != PurchaseOrderTab.INVOICES }?.let { error ->
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
                PurchaseOrderTab.ORDERS -> OrdersPane(state, viewModel, invoiceViewModel)
                PurchaseOrderTab.DELIVERIES -> DeliveriesPane(state, viewModel)
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
    invoiceViewModel: SupplierInvoiceViewModel?
) {
    Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassCard(modifier = Modifier.weight(0.42f).fillMaxHeight(), contentPadding = 14.dp) {
            SectionHeader(
                title = "Заказы",
                count = state.orders.size,
                accent = Brand.Violet,
                subtitle = state.statusFilter?.localizedName() ?: "Все статусы",
                action = { SoftButton(text = "Обновить", onClick = viewModel::loadOrders) }
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
                    title = "Заказов нет",
                    text = "Заказ оформляется по предложению, выбранному инициатором",
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
        OrderCardPane(state, viewModel, invoiceViewModel, Modifier.weight(0.58f).fillMaxHeight())
    }
}

@Composable
private fun OrderCardPane(
    state: PurchaseOrderUiState,
    viewModel: PurchaseOrderViewModel,
    invoiceViewModel: SupplierInvoiceViewModel?,
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
        OrderHeader(order)
        Spacer(Modifier.height(8.dp))
        OrderActions(order, state, viewModel)
        Spacer(Modifier.height(10.dp))
        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            SectionHeader(title = "Условия", accent = Brand.Violet)
            DetailRow("Поставщик", order.supplierName ?: order.supplierId.toString())
            DetailRow("Предложение", order.offerNumber ?: order.offerId.toString())
            DetailRow("Ответственный", viewModel.userName(order.responsibleId))
            DetailRow("Дата заказа", order.orderedAt.asDate())
            DetailRow("Ожидаемая поставка", order.expectedAt.asDate())
            DetailRow("Адрес доставки", order.deliveryAddress ?: "—")
            DetailRow("Условия оплаты", order.paymentTerms ?: "—")
            DetailRow("Договор", order.contractNo ?: "—")
            DetailRow("Счёт", order.invoiceNo ?: "—")
            DetailRow("Согласовал", order.approvedBy?.let(viewModel::userName) ?: "—")
            DetailRow("Согласовано", order.approvedAt.asDate())
            if (order.reapprovalRequired) {
                DetailRow(
                    "Требуется согласование",
                    order.reapprovalReason ?: "существенные условия изменены",
                    valueColor = Brand.Amber
                )
            }
            order.note?.takeIf { it.isNotBlank() }?.let { DetailRow("Примечание", it) }
            DetailRow("Потребности", if (order.demandIds.isEmpty()) "—" else order.demandIds.joinToString(", ") { "#$it" })

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
                PurchaseOrderLinesGrid(order = order, modifier = Modifier.fillMaxWidth().height(180.dp))
                Spacer(Modifier.height(8.dp))
                TotalsRow(order)
            }

            invoiceViewModel?.let { invoices ->
                Spacer(Modifier.height(12.dp))
                OrderInvoicesSection(order, invoices, onOpenInvoices = { viewModel.selectTab(PurchaseOrderTab.INVOICES) })
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
                                text = delivery.lines.joinToString(", ") { "${it.quantity}" } +
                                    (delivery.sourceDocument?.let { " · $it" } ?: ""),
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
                PurchaseOrderHistoryGrid(
                    history = state.history,
                    modifier = Modifier.fillMaxWidth().height(180.dp)
                )
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
                text = "${order.supplierName ?: "поставщик #${order.supplierId}"} · ${org.example.project.ui.theme.ErpFormat.currencySymbol(order.currency)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

@Composable
private fun OrderActions(
    order: PurchaseOrderDto,
    state: PurchaseOrderUiState,
    viewModel: PurchaseOrderViewModel
) {
    val editable = order.status in setOf(
        org.example.project.dto.PurchaseOrderStatus.DRAFT,
        org.example.project.dto.PurchaseOrderStatus.PENDING_APPROVAL,
        org.example.project.dto.PurchaseOrderStatus.APPROVED,
        org.example.project.dto.PurchaseOrderStatus.SENT
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
            SoftButton(text = "Закрыть", onClick = viewModel::close, tint = Brand.Slate)
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
private fun DeliveriesPane(state: PurchaseOrderUiState, viewModel: PurchaseOrderViewModel) {
    GlassCard(modifier = Modifier.fillMaxSize(), contentPadding = 16.dp) {
        SectionHeader(
            title = "Все поставки",
            count = state.deliveries.size,
            accent = Brand.Mint,
            subtitle = "Поставки по всем загруженным заказам",
            action = { SoftButton(text = "Обновить", onClick = viewModel::loadDeliveries) }
        )
        Spacer(Modifier.height(8.dp))
        when {
            state.isOrdersLoading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { GradientSpinner() }
            state.deliveries.isEmpty() -> EmptyState(
                title = "Поставок нет",
                text = "Поставка регистрируется по отправленному заказу и может быть частичной",
                glyph = ErpIconGlyph.Warehouse,
                accent = Brand.Mint
            )
            else -> Column {
                PurchaseDeliveriesGrid(
                    deliveries = state.deliveries,
                    statuses = state.orders.associate { it.id to it.status },
                    onClick = { delivery -> viewModel.selectOrder(delivery.orderId) },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Всего позиций в показанных поставках: " +
                        state.deliveries.sumOf { it.lines.size },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
