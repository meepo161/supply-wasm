package org.example.project.ui.supplier

import androidx.compose.ui.graphics.Color
import org.example.project.dto.SupplierOfferCompliance
import org.example.project.dto.SupplierOfferStatus
import org.example.project.dto.SupplierVerificationStatus
import org.example.project.ui.theme.Brand

fun SupplierVerificationStatus.localizedName(): String = when (this) {
    SupplierVerificationStatus.NOT_VERIFIED -> "Не проверен"
    SupplierVerificationStatus.IN_REVIEW -> "На проверке"
    SupplierVerificationStatus.VERIFIED -> "Проверен"
    SupplierVerificationStatus.REJECTED -> "Отклонён"
}

fun SupplierVerificationStatus.accentColor(): Color = when (this) {
    SupplierVerificationStatus.NOT_VERIFIED -> Brand.Slate
    SupplierVerificationStatus.IN_REVIEW -> Brand.Amber
    SupplierVerificationStatus.VERIFIED -> Brand.Mint
    SupplierVerificationStatus.REJECTED -> Brand.Rose
}

fun SupplierOfferStatus.localizedName(): String = when (this) {
    SupplierOfferStatus.RECEIVED -> "Получено"
    SupplierOfferStatus.SHORTLISTED -> "В шорт-листе"
    SupplierOfferStatus.SELECTED -> "Выбрано"
    SupplierOfferStatus.REJECTED -> "Отклонено"
    SupplierOfferStatus.WITHDRAWN -> "Отозвано"
    SupplierOfferStatus.EXPIRED -> "Истекло"
}

fun SupplierOfferStatus.accentColor(): Color = when (this) {
    SupplierOfferStatus.RECEIVED -> Brand.Blue
    SupplierOfferStatus.SHORTLISTED -> Brand.Indigo
    SupplierOfferStatus.SELECTED -> Brand.Mint
    SupplierOfferStatus.REJECTED -> Brand.Rose
    SupplierOfferStatus.WITHDRAWN -> Brand.Slate
    SupplierOfferStatus.EXPIRED -> Brand.Amber
}

fun SupplierOfferCompliance.localizedName(): String = when (this) {
    SupplierOfferCompliance.UNKNOWN -> "Не проверено"
    SupplierOfferCompliance.FULL -> "Полное"
    SupplierOfferCompliance.PARTIAL -> "Частичное"
    SupplierOfferCompliance.DEVIATION -> "Отклонение"
}

fun SupplierOfferCompliance.accentColor(): Color = when (this) {
    SupplierOfferCompliance.UNKNOWN -> Brand.Amber
    SupplierOfferCompliance.FULL -> Brand.Mint
    SupplierOfferCompliance.PARTIAL -> Brand.Amber
    SupplierOfferCompliance.DEVIATION -> Brand.Rose
}

/** Деньги показываем как есть из сервера, только с разделителем разрядов. */
fun String?.asMoney(currency: String = "RUB"): String {
    if (this.isNullOrBlank()) return "—"
    val value = this.trim()
    val intPart = value.substringBefore('.')
    val frac = value.substringAfter('.', "").trimEnd('0')
    val grouped = intPart.reversed().chunked(3).joinToString(" ").reversed()
    val number = if (frac.isEmpty()) grouped else "$grouped,$frac"
    return "$number $currency"
}

fun String?.asDate(): String {
    if (this.isNullOrBlank()) return "—"
    val date = substringBefore('T')
    val parts = date.split('-')
    return if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else this
}
