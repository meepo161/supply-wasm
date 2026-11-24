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
