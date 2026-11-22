package org.example.project.ui.supplier

import org.example.project.dto.ApiError
import org.example.project.dto.PurchaseDeliveryDto
import org.example.project.dto.PurchaseOrderDto
import org.example.project.dto.PurchaseOrderHistoryDto
import org.example.project.dto.PurchaseOrderStatus
import org.example.project.dto.SupplierOfferDto

/**
 * Состояние экрана заказов поставщику и частичных поставок (SUP-15, SUP-16).
 *
 * Заказ создаётся только из выбранного предложения, поэтому список предложений
 * в состоянии нужен для выбора основания заказа — он не выдумывается в UI.
 */
data class PurchaseOrderUiState(
    val orders: List<PurchaseOrderDto> = emptyList(),
    val selectedOrder: PurchaseOrderDto? = null,
    val history: List<PurchaseOrderHistoryDto> = emptyList(),
    val deliveries: List<PurchaseDeliveryDto> = emptyList(),
    /** Выбранные (SELECTED) предложения, из которых можно создать заказ. */
    val selectedOffers: List<SupplierOfferDto> = emptyList(),
    val users: List<org.example.project.dto.UserDto> = emptyList(),
    val statusFilter: PurchaseOrderStatus? = null,
    val isOrdersLoading: Boolean = false,
    val isOrderLoading: Boolean = false,
    val isActionRunning: Boolean = false,
    val operationError: ApiError? = null,
    val successMessage: String? = null,
    val activeTab: PurchaseOrderTab = PurchaseOrderTab.ORDERS,
    val activeDialog: PurchaseOrderDialog? = null,
    val createForm: CreatePurchaseOrderForm = CreatePurchaseOrderForm(),
    val editForm: EditPurchaseOrderForm? = null,
    val deliveryForm: CreateDeliveryForm? = null,
    val cancelForm: CancelOrderForm? = null
)

enum class PurchaseOrderTab(val title: String) {
    ORDERS("Заказы"),
    DELIVERIES("Поставки"),
    INVOICES("Счета")
}

sealed class PurchaseOrderDialog {
    data object Create : PurchaseOrderDialog()
    data object Edit : PurchaseOrderDialog()
    data object RegisterDelivery : PurchaseOrderDialog()
    data class CancelOrder(val number: String) : PurchaseOrderDialog()
    data class CancelDelivery(val id: Long, val number: String) : PurchaseOrderDialog()
}

data class CreatePurchaseOrderForm(
    val offerId: Long? = null,
    val currency: String = "RUB",
    val deliveryAddress: String = "",
    val paymentTerms: String = "",
    val contractNo: String = "",
    val invoiceNo: String = "",
    val expectedAt: String = "",
    val note: String = "",
    val responsibleId: Long? = null,
    /** Количество по позициям: только уменьшение относительно предложения. */
    val lines: List<OrderLineForm> = emptyList(),
    /** Потребности, которые закрывает заказ; по умолчанию — потребность предложения. */
    val demandIds: List<Long> = emptyList()
)

data class OrderLineForm(
    val offerLineId: Long,
    val label: String,
    val unit: String,
    val unitPrice: String,
    val offeredQuantity: String,
    val quantity: String
)

data class EditPurchaseOrderForm(
    val id: Long,
    val version: Long,
    val deliveryAddress: String = "",
    val paymentTerms: String = "",
    val contractNo: String = "",
    val invoiceNo: String = "",
    val expectedAt: String = "",
    val note: String = "",
    val changeReason: String = "",
    val lines: List<OrderLineEditForm> = emptyList()
)

/** В изменении заказа идентификатор — это id строки заказа, а не предложения. */
data class OrderLineEditForm(
    val orderLineId: Long,
    val label: String,
    val unit: String,
    val orderedQuantity: String,
    val deliveredQuantity: String,
    val quantity: String
)

data class CreateDeliveryForm(
    val deliveredAt: String,
    val deliveryNote: String = "",
    val sourceDocument: String = "",
    val note: String = "",
    val lines: List<DeliveryLineForm> = emptyList()
)

data class DeliveryLineForm(
    val orderLineId: Long,
    val label: String,
    val unit: String,
    val remaining: String,
    val quantity: String
)

data class CancelOrderForm(
    val reason: String = ""
)
