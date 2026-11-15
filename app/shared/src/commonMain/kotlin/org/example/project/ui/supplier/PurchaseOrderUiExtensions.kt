package org.example.project.ui.supplier

import androidx.compose.ui.graphics.Color
import org.example.project.dto.PurchaseDeliveryStatus
import org.example.project.dto.PurchaseOrderStatus
import org.example.project.ui.theme.Brand

fun PurchaseOrderStatus.localizedName(): String = when (this) {
    PurchaseOrderStatus.DRAFT -> "Черновик"
    PurchaseOrderStatus.PENDING_APPROVAL -> "На согласовании"
    PurchaseOrderStatus.APPROVED -> "Согласован"
    PurchaseOrderStatus.SENT -> "Отправлен"
    PurchaseOrderStatus.PARTIALLY_DELIVERED -> "Частично поставлен"
    PurchaseOrderStatus.DELIVERED -> "Поставлен"
    PurchaseOrderStatus.CLOSED -> "Закрыт"
    PurchaseOrderStatus.CANCELLED -> "Отменён"
}

fun PurchaseOrderStatus.accentColor(): Color = when (this) {
    PurchaseOrderStatus.DRAFT -> Brand.Slate
    PurchaseOrderStatus.PENDING_APPROVAL -> Brand.Amber
    PurchaseOrderStatus.APPROVED -> Brand.Teal
    PurchaseOrderStatus.SENT -> Brand.Blue
    PurchaseOrderStatus.PARTIALLY_DELIVERED -> Brand.Indigo
    PurchaseOrderStatus.DELIVERED -> Brand.Mint
    PurchaseOrderStatus.CLOSED -> Brand.Slate
    PurchaseOrderStatus.CANCELLED -> Brand.Rose
}

fun PurchaseDeliveryStatus.localizedName(): String = when (this) {
    PurchaseDeliveryStatus.REGISTERED -> "Зарегистрирована"
    PurchaseDeliveryStatus.CANCELLED -> "Отменена"
}

fun PurchaseDeliveryStatus.accentColor(): Color = when (this) {
    PurchaseDeliveryStatus.REGISTERED -> Brand.Mint
    PurchaseDeliveryStatus.CANCELLED -> Brand.Rose
}

/** Названия действий истории заказа — только те, что пишет сервер. */
fun String.historyActionTitle(): String = when (this) {
    "CREATED" -> "Создан"
    "UPDATED" -> "Изменён"
    "SUBMIT" -> "Отправлен на согласование"
    "APPROVE" -> "Согласован"
    "REAPPROVAL_REQUIRED" -> "Требуется повторное согласование"
    "SEND" -> "Отправлен поставщику"
    "DELIVERY_REGISTERED" -> "Зарегистрирована поставка"
    "DELIVERY_CANCELLED" -> "Поставка отменена"
    "CLOSE" -> "Закрыт"
    "CANCELLED" -> "Отменён"
    else -> this
}
