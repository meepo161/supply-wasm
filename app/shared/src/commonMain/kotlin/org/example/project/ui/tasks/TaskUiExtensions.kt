package org.example.project.ui.tasks

import org.example.project.dto.TaskArea
import org.example.project.dto.TaskPriority
import org.example.project.dto.TaskStatus
import org.example.project.dto.TaskWorkType

fun TaskStatus.localizedName(): String = when (this) {
    TaskStatus.NEW -> "Новая"
    TaskStatus.ASSIGNED -> "Назначена"
    TaskStatus.IN_PROGRESS -> "В работе"
    TaskStatus.BLOCKED -> "Заблокирована"
    TaskStatus.ON_REVIEW -> "На проверке"
    TaskStatus.REWORK -> "На доработке"
    TaskStatus.CLOSED -> "Закрыта"
    TaskStatus.CANCELLED -> "Отменена"
}

fun TaskArea.localizedName(): String = when (this) {
    TaskArea.PERSONAL -> "Личная"
    TaskArea.PROJECT -> "Проектная"
    TaskArea.DEPARTMENT -> "Подразделение"
}

fun TaskPriority.localizedName(): String = when (this) {
    TaskPriority.LOW -> "Низкий"
    TaskPriority.NORMAL -> "Обычный"
    TaskPriority.HIGH -> "Высокий"
    TaskPriority.CRITICAL -> "Критичный"
}

fun TaskWorkType.localizedName(): String = when (this) {
    TaskWorkType.GENERAL -> "Без приёмки"
    TaskWorkType.REVIEWED -> "С приёмкой"
}

fun String?.toShortDate(): String {
    if (this.isNullOrBlank()) return "—"
    return substringBefore("T")
}

fun String?.toShortDateTime(): String {
    if (this.isNullOrBlank()) return "—"
    val date = substringBefore("T")
    val time = substringAfter("T", "").take(5)
    return if (time.isBlank()) date else "$date $time"
}
