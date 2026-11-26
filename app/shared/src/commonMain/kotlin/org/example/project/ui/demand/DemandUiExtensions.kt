package org.example.project.ui.demand

import androidx.compose.ui.graphics.Color
import org.example.project.dto.DemandHeaderDto
import org.example.project.dto.DemandLineStatus
import org.example.project.dto.DemandPriority
import org.example.project.dto.DemandSourceType
import org.example.project.dto.DemandStatus
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpFormat
import kotlin.math.abs

fun DemandSourceType.localizedName(): String = when (this) {
    DemandSourceType.PROJECT -> "Проект"
    DemandSourceType.STOCKPILE -> "Задел"
    DemandSourceType.MANUAL -> "Разовая заявка"
}

fun DemandStatus.localizedName(): String = when (this) {
    DemandStatus.DRAFT -> "Черновик"
    DemandStatus.PENDING_APPROVAL -> "На согласовании"
    DemandStatus.APPROVED -> "Ожидает предложений"
    DemandStatus.IN_PROCUREMENT -> "В закупке"
    DemandStatus.PARTIALLY_COVERED -> "Частично на складе"
    DemandStatus.COVERED -> "На складе"
    DemandStatus.CANCELLED -> "Отменена"
}

fun DemandPriority.localizedName(): String = when (this) {
    DemandPriority.LOW -> "Низкий"
    DemandPriority.NORMAL -> "Обычный"
    DemandPriority.HIGH -> "Высокий"
    DemandPriority.CRITICAL -> "Критичный"
}

fun DemandLineStatus.localizedName(): String = when (this) {
    DemandLineStatus.OPEN -> "Ждёт поставки"
    DemandLineStatus.PARTIALLY_COVERED -> "Частично на складе"
    DemandLineStatus.COVERED -> "На складе ✓"
    DemandLineStatus.CANCELLED -> "Отменена"
}

fun demandStage(demand: DemandHeaderDto, offersCount: Int?): DemandStageFilter = when {
    demand.status == DemandStatus.CANCELLED -> DemandStageFilter.CANCELLED
    demand.status == DemandStatus.COVERED -> DemandStageFilter.CLOSED
    demand.isPaid || demand.nextActorRole == "Кладовщик" -> DemandStageFilter.IN_TRANSIT
    demand.selectedSupplierName != null -> DemandStageFilter.WAITING_PAYMENT
    offersCount != null -> if (offersCount > 0) DemandStageFilter.WAITING_SELECTION else DemandStageFilter.WAITING_OFFERS
    demand.status == DemandStatus.IN_PROCUREMENT -> DemandStageFilter.WAITING_SELECTION
    else -> DemandStageFilter.WAITING_OFFERS
}

fun demandStageLabel(demand: DemandHeaderDto, offersCount: Int?): String = when (demandStage(demand, offersCount)) {
    DemandStageFilter.WAITING_OFFERS -> when (demand.status) {
        DemandStatus.DRAFT -> "Черновик"
        DemandStatus.PENDING_APPROVAL -> "На согласовании"
        else -> "Ищет предложения"
    }
    DemandStageFilter.WAITING_SELECTION -> "На выборе у ведущего"
    DemandStageFilter.WAITING_PAYMENT -> buildString {
        append("Ждёт оформления заказа")
        val details = listOfNotNull(
            demand.selectedSupplierName,
            demand.selectedGrossTotal?.let { ErpFormat.money(it) }
        )
        if (details.isNotEmpty()) {
            append(" (").append(details.joinToString(", ")).append(")")
        }
    }
    DemandStageFilter.IN_TRANSIT -> if (demand.status == DemandStatus.PARTIALLY_COVERED) {
        "Заказан · Частично обеспечено"
    } else {
        "Заказан · В пути на склад"
    }
    DemandStageFilter.CLOSED -> "На складе ✓"
    DemandStageFilter.CANCELLED -> "Отменена"
    DemandStageFilter.ALL -> demand.status.localizedName()
}

fun demandStageAccent(demand: DemandHeaderDto, offersCount: Int?): Color = when (demandStage(demand, offersCount)) {
    DemandStageFilter.WAITING_OFFERS -> Brand.Blue
    DemandStageFilter.WAITING_SELECTION -> Brand.Amber
    DemandStageFilter.WAITING_PAYMENT -> Brand.Orange
    DemandStageFilter.IN_TRANSIT -> Brand.Teal
    DemandStageFilter.CLOSED -> Brand.Mint
    DemandStageFilter.CANCELLED -> Brand.Rose
    DemandStageFilter.ALL -> Brand.Indigo
}

fun demandRequiredDateBadge(
    requiredDate: String?,
    status: DemandStatus,
    todayIso: String = org.example.project.ui.home.currentIsoDate()
): Pair<String, Color>? {
    if (requiredDate.isNullOrBlank()) return null
    if (status == DemandStatus.COVERED || status == DemandStatus.CANCELLED) return null
    val diff = ErpFormat.daysDiff(requiredDate, todayIso) ?: return null
    return when {
        diff < 0 -> "Просрочено ${abs(diff)} дн." to Brand.Rose
        diff == 0 -> "Срок сегодня" to Brand.Rose
        diff <= 3 -> "Осталось $diff дн." to Brand.Amber
        else -> "Осталось $diff дн." to Brand.Teal
    }
}

/** Порог (в днях), после которого открытая потребность/дефицит считается «зависшей» (из supply2 PurchaseAging). */
const val DEFICIT_AT_RISK_DAYS: Long = 7L

fun isoDateToEpochDays(iso: String?): Long? {
    val datePart = iso?.trim()?.substringBefore("T") ?: return null
    val parts = datePart.split('-')
    if (parts.size != 3) return null
    val y = parts[0].toLongOrNull() ?: return null
    val m = parts[1].toLongOrNull() ?: return null
    val d = parts[2].toLongOrNull() ?: return null
    if (m !in 1..12 || d !in 1..31) return null
    // Алгоритм Howard Hinnant (civil_from_days / days_from_civil) — без платформенных зависимостей.
    val yr = if (m <= 2) y - 1 else y
    val era = (if (yr >= 0) yr else yr - 399) / 400
    val yoe = yr - era * 400
    val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

fun isoDatePlusDays(iso: String, days: Int): String {
    val epoch = isoDateToEpochDays(iso) ?: return iso
    val z = epoch + days + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
    return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

fun demandOpenDays(
    createdAt: String?,
    todayIso: String = org.example.project.ui.home.currentIsoDate()
): Long {
    val createdDays = isoDateToEpochDays(createdAt) ?: return 0L
    val todayDays = isoDateToEpochDays(todayIso) ?: return 0L
    return (todayDays - createdDays).coerceAtLeast(0L)
}

fun isDemandAtRisk(
    status: DemandStatus,
    openDays: Long,
    threshold: Long = DEFICIT_AT_RISK_DAYS
): Boolean = status != DemandStatus.COVERED &&
    status != DemandStatus.CANCELLED &&
    openDays >= threshold

internal fun String?.plainDecimalString(): String {
    val value = this?.trim().orEmpty()
    if (value.isEmpty()) return "0"
    val normalized = value.replace(" ", "").replace(',', '.')
    return if (normalized.contains('.')) normalized.trimEnd('0').trimEnd('.').ifEmpty { "0" } else normalized
}

internal fun String?.toDoubleSafe(): Double =
    this?.trim()?.replace(" ", "")?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
