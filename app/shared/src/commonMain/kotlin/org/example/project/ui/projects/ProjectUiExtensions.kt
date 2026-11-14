package org.example.project.ui.projects

import org.example.project.dto.ProjectPriority
import org.example.project.dto.ProjectStatus
import org.example.project.dto.ProjectType

fun ProjectStatus.localizedName(): String = when (this) {
    ProjectStatus.DRAFT -> "Черновик"
    ProjectStatus.PLANNED -> "Запланирован"
    ProjectStatus.IN_PROGRESS -> "В работе"
    ProjectStatus.ON_HOLD -> "Приостановлен"
    ProjectStatus.COMPLETED -> "Завершён"
    ProjectStatus.CLOSED -> "Закрыт"
    ProjectStatus.CANCELLED -> "Отменён"
}

fun ProjectType.localizedName(): String = when (this) {
    ProjectType.ORDER_DEVELOPMENT -> "Разработка по заказу"
    ProjectType.REPEAT_PRODUCTION -> "Серийное производство"
    ProjectType.MODERNIZATION -> "Модернизация"
    ProjectType.INTERNAL -> "Внутренний"
}

fun ProjectPriority.localizedName(): String = when (this) {
    ProjectPriority.LOW -> "Низкий"
    ProjectPriority.NORMAL -> "Обычный"
    ProjectPriority.HIGH -> "Высокий"
    ProjectPriority.CRITICAL -> "Критичный"
}

fun String?.toShortDate(): String {
    if (this.isNullOrBlank()) return "—"
    return substringBefore("T")
}
