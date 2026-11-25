package org.example.project.ui.supplier

import androidx.compose.ui.graphics.Color
import org.example.project.dto.SupplierOfferCompliance
import org.example.project.dto.SupplierOfferStatus
import org.example.project.dto.SupplierVerificationStatus
import org.example.project.ui.theme.Brand
import org.example.project.dto.DemandStatus
import org.example.project.dto.SupplierAddressKind
import org.example.project.dto.SupplierClaimStatus
import org.example.project.dto.SupplierDocumentKind
import org.example.project.dto.SupplierKind
import org.example.project.ui.demand.localizedName

fun SupplierKind.localizedName(): String = when (this) {
    SupplierKind.SUPPLIER -> "Поставщик"
    SupplierKind.CONTRACTOR -> "Подрядчик"
    SupplierKind.MANUFACTURER -> "Производитель"
}

fun SupplierAddressKind.localizedName(): String = when (this) {
    SupplierAddressKind.LEGAL -> "Юридический"
    SupplierAddressKind.ACTUAL -> "Фактический"
    SupplierAddressKind.POSTAL -> "Почтовый"
    SupplierAddressKind.WAREHOUSE -> "Склад"
    SupplierAddressKind.DELIVERY -> "Доставка"
}

fun SupplierDocumentKind.localizedName(): String = when (this) {
    SupplierDocumentKind.CONTRACT -> "Договор"
    SupplierDocumentKind.CERTIFICATE -> "Сертификат"
    SupplierDocumentKind.LICENSE -> "Лицензия"
    SupplierDocumentKind.AGREEMENT -> "Соглашение"
    SupplierDocumentKind.OTHER -> "Прочее"
}

fun SupplierClaimStatus.localizedName(): String = when (this) {
    SupplierClaimStatus.OPEN -> "Открыта"
    SupplierClaimStatus.SENT -> "Отправлена"
    SupplierClaimStatus.IN_REVIEW -> "На рассмотрении"
    SupplierClaimStatus.RESOLVED -> "Решена"
    SupplierClaimStatus.CLOSED -> "Закрыта"
    SupplierClaimStatus.CANCELLED -> "Отменена"
}

/** Статус потребности приходит строкой (сравнение предложений) — показываем по-русски. */
fun localizedDemandStatus(raw: String): String =
    runCatching { DemandStatus.valueOf(raw) }.getOrNull()?.localizedName() ?: raw

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
    SupplierOfferStatus.NEEDS_REVISION -> "На доработке"
    SupplierOfferStatus.SELECTED -> "Выбрано"
    SupplierOfferStatus.REJECTED -> "Отклонено"
    SupplierOfferStatus.WITHDRAWN -> "Отозвано"
    SupplierOfferStatus.EXPIRED -> "Истекло"
}

fun SupplierOfferStatus.accentColor(): Color = when (this) {
    SupplierOfferStatus.RECEIVED -> Brand.Blue
    SupplierOfferStatus.SHORTLISTED -> Brand.Indigo
    SupplierOfferStatus.NEEDS_REVISION -> Brand.Amber
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

/**
 * Деньги: всегда два знака после запятой, разделитель разрядов, символ валюты
 * («1 500,00 ₽»). Сервер хранит до четырёх знаков — лишние округляются.
 */
fun String?.asMoney(currency: String = "RUB"): String {
    if (this.isNullOrBlank()) return "—"
    val value = this.replace(" ", "").replace(" ", "").replace(',', '.')
    if (value.toDoubleOrNull() == null) return this
    val negative = value.startsWith("-")
    val unsigned = value.removePrefix("-").removePrefix("+")
    var intPart = unsigned.substringBefore('.').ifEmpty { "0" }
    val fracRaw = unsigned.substringAfter('.', "").padEnd(3, '0')
    var frac = fracRaw.take(2)
    if (fracRaw[2] >= '5') {
        val rounded = incrementDigits(intPart + frac)
        intPart = rounded.dropLast(2).ifEmpty { "0" }
        frac = rounded.takeLast(2)
    }
    val grouped = intPart.reversed().chunked(3).joinToString(" ").reversed()
    return (if (negative) "-" else "") + "$grouped,$frac ${org.example.project.ui.theme.ErpFormat.currencySymbol(currency)}"
}

/** Прибавляет единицу к десятичной строке без переполнения Long. */
private fun incrementDigits(digits: String): String {
    val chars = digits.toCharArray()
    var i = chars.lastIndex
    while (i >= 0) {
        if (chars[i] == '9') { chars[i] = '0'; i-- } else { chars[i] = chars[i] + 1; return chars.concatToString() }
    }
    return "1" + chars.concatToString()
}

/** Срок поставки в днях: «5 дн.» или «—». */
fun Int?.asDeliveryDays(): String = this?.let { "$it дн." } ?: "—"

fun String?.asDate(): String {
    if (this.isNullOrBlank()) return "—"
    val date = substringBefore('T')
    val parts = date.split('-')
    return if (parts.size == 3) "${parts[2]}.${parts[1]}.${parts[0]}" else this
}
