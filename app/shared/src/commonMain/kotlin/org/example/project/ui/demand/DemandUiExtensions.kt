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
