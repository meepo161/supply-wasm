package org.example.project.ui.supplier

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.example.project.dto.UserDto
import org.example.project.dto.hasPermission
import org.example.project.ui.demand.DemandPage
import org.example.project.ui.demand.DemandScreen
import org.example.project.ui.demand.DemandViewModel
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.PageHeader
import org.example.project.ui.theme.Tab

enum class SupplyTab(val title: String) {
    DEMANDS("Заявки"),
    ORDERS("Заказы"),
    DELIVERIES("Поставки"),
    INVOICES("Счета"),
    SUPPLIERS("Поставщики")
}

/**
 * Единый раздел «Снабжение»: объединяет Заявки (потребности), Заказы поставщикам,
 * Поставки, Счета и справочник Поставщиков (с рекламациями) в одном верхнем меню.
 */
@Composable
fun SupplyHubScreen(
    activeTab: SupplyTab,
    onSelectTab: (SupplyTab) -> Unit,
    currentUser: UserDto,
    demandViewModel: DemandViewModel?,
    purchaseOrderViewModel: PurchaseOrderViewModel?,
    supplierInvoiceViewModel: SupplierInvoiceViewModel?,
    supplierViewModel: SupplierViewModel?,
    onOpenPurchaseOrder: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val canReadSuppliers = currentUser.hasPermission("suppliers", "read")
    val tabs = buildList {
        if (demandViewModel != null) add(SupplyTab.DEMANDS)
        if (purchaseOrderViewModel != null && canReadSuppliers) {
            add(SupplyTab.ORDERS)
            add(SupplyTab.DELIVERIES)
        }
        if (supplierInvoiceViewModel?.canRead() == true) add(SupplyTab.INVOICES)
        if (supplierViewModel != null && canReadSuppliers) add(SupplyTab.SUPPLIERS)
    }
    val effectiveTab = if (activeTab in tabs) activeTab else (tabs.firstOrNull() ?: SupplyTab.DEMANDS)

    val demandState = demandViewModel?.uiState?.collectAsState()?.value
    val supplierState = supplierViewModel?.uiState?.collectAsState()?.value
    val isDemandDetail = effectiveTab == SupplyTab.DEMANDS &&
        demandState?.currentPage == DemandPage.DETAIL &&
        demandState.selectedDemand != null

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = if (isDemandDetail) {
                "Снабжение · Заявка ${demandState?.selectedDemand?.header?.number.orEmpty()}"
            } else {
                "Снабжение"
            },
            glyph = ErpIconGlyph.Cart,
            gradient = Gradients.Sunset,
            subtitle = when (effectiveTab) {
                SupplyTab.DEMANDS -> if (isDemandDetail) {
                    "Единый пульт заявки: позиции, предложения со счетами, выбор, оплата и склад"
                } else {
                    "Заявки на закупку, предложения поставщиков, оплата и статус склада"
                }
                SupplyTab.ORDERS -> "Реестр заказов из «Заявок» и по выбранным предложениям"
                SupplyTab.DELIVERIES -> "Журнал поставок: что, от кого и в каком количестве пришло по заказам"
                SupplyTab.INVOICES -> "Счета поставщиков: сверка с заказом и передача поступления на склад"
                SupplyTab.SUPPLIERS -> "Справочник контрагентов, реквизиты и рекламации"
            },
            onBack = if (isDemandDetail) ({ demandViewModel?.goBack() }) else null,
            actions = {
                when (effectiveTab) {
                    SupplyTab.ORDERS -> if (purchaseOrderViewModel?.canOrder() == true) {
                        GradientButton(
                            text = "Новый заказ",
                            onClick = { purchaseOrderViewModel.openCreate() },
                            gradient = Gradients.Berry,
                            leadingIcon = ErpIconGlyph.Plus,
                            compact = true
                        )
                    }
                    SupplyTab.SUPPLIERS -> {
                        val subTab = supplierState?.selectedTab ?: SupplierTab.SUPPLIERS
                        if (subTab == SupplierTab.SUPPLIERS && supplierViewModel?.canManage() == true) {
                            GradientButton(
                                text = "Новый поставщик",
                                onClick = { supplierViewModel.showDialog(SupplierDialog.CreateSupplier) },
                                gradient = Gradients.Sunset,
                                leadingIcon = ErpIconGlyph.Plus,
                                compact = true
                            )
                        } else if (subTab == SupplierTab.CLAIMS && supplierViewModel?.canManage() == true) {
                            GradientButton(
                                text = "Новая рекламация",
                                onClick = { supplierViewModel.showDialog(SupplierDialog.CreateClaim) },
                                gradient = Gradients.Sunset,
                                leadingIcon = ErpIconGlyph.Plus,
                                compact = true
                            )
                        }
                    }
                    else -> Unit
                }
            }
        )
        Spacer(Modifier.height(8.dp))
        if (tabs.size > 1) {
            ScrollableTabRow(selectedTabIndex = tabs.indexOf(effectiveTab).coerceAtLeast(0)) {
                tabs.forEach { tab ->
                    Tab(
                        selected = effectiveTab == tab,
                        onClick = {
                            onSelectTab(tab)
                            when (tab) {
                                SupplyTab.ORDERS -> purchaseOrderViewModel?.selectTab(PurchaseOrderTab.ORDERS)
                                SupplyTab.DELIVERIES -> purchaseOrderViewModel?.selectTab(PurchaseOrderTab.DELIVERIES)
                                SupplyTab.INVOICES -> purchaseOrderViewModel?.selectTab(PurchaseOrderTab.INVOICES)
                                else -> Unit
                            }
                        },
                        text = { Text(tab.title) }
                    )
                }
            }
        }
        when (effectiveTab) {
            SupplyTab.DEMANDS -> demandViewModel?.let {
                DemandScreen(
                    viewModel = it,
                    currentUser = currentUser,
                    onOpenPurchaseOrder = { orderId ->
                        onSelectTab(SupplyTab.ORDERS)
                        purchaseOrderViewModel?.selectTab(PurchaseOrderTab.ORDERS)
                        purchaseOrderViewModel?.selectOrder(orderId)
                    },
                    onOpenInvoice = { invoiceId ->
                        onSelectTab(SupplyTab.INVOICES)
                        purchaseOrderViewModel?.selectTab(PurchaseOrderTab.INVOICES)
                        supplierInvoiceViewModel?.select(invoiceId)
                    },
                    invoiceViewModel = supplierInvoiceViewModel,
                    embedded = true
                )
            }
            SupplyTab.ORDERS, SupplyTab.DELIVERIES, SupplyTab.INVOICES -> purchaseOrderViewModel?.let {
                PurchaseOrderScreen(
                    viewModel = it,
                    invoiceViewModel = supplierInvoiceViewModel,
                    embedded = true,
                    onOpenOrdersTab = { onSelectTab(SupplyTab.ORDERS) },
                    onOpenInvoicesTab = { onSelectTab(SupplyTab.INVOICES) }
                )
            }
            SupplyTab.SUPPLIERS -> supplierViewModel?.let {
                SupplierScreen(
                    viewModel = it,
                    embedded = true
                )
            }
        }
    }
}
