package org.example.project.ui.demand

import org.example.project.dto.DemandLineStatus
import org.example.project.dto.DemandPriority
import org.example.project.dto.DemandSourceType
import org.example.project.dto.DemandStatus

fun DemandSourceType.localizedName(): String = when (this) {
    DemandSourceType.PROJECT -> "Проект"
    DemandSourceType.STOCKPILE -> "Задел"
    DemandSourceType.MANUAL -> "Ручная"
}

fun DemandStatus.localizedName(): String = when (this) {
    DemandStatus.DRAFT -> "Черновик"
    DemandStatus.PENDING_APPROVAL -> "На согласовании"
    DemandStatus.APPROVED -> "Согласована"
    DemandStatus.IN_PROCUREMENT -> "В закупке"
    DemandStatus.PARTIALLY_COVERED -> "Частично покрыта"
    DemandStatus.COVERED -> "Покрыта"
    DemandStatus.CANCELLED -> "Отменена"
}

fun DemandPriority.localizedName(): String = when (this) {
    DemandPriority.LOW -> "Низкий"
    DemandPriority.NORMAL -> "Обычный"
    DemandPriority.HIGH -> "Высокий"
    DemandPriority.CRITICAL -> "Критичный"
}

fun DemandLineStatus.localizedName(): String = when (this) {
    DemandLineStatus.OPEN -> "Открыта"
    DemandLineStatus.PARTIALLY_COVERED -> "Частично покрыта"
    DemandLineStatus.COVERED -> "Покрыта"
    DemandLineStatus.CANCELLED -> "Отменена"
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
