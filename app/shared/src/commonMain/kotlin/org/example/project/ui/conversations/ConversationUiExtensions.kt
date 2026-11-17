package org.example.project.ui.conversations

import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind

fun ConversationKind.localizedName(): String = when (this) {
    ConversationKind.DIRECT -> "Личный"
    ConversationKind.GROUP -> "Групповой"
    ConversationKind.PROJECT -> "Проектный"
    ConversationKind.TASK -> "Задача"
}

/** Отображаемое название: для личного чата без title — имя собеседника. */
fun ConversationDto.displayTitle(currentUserId: Long): String {
    val explicit = title?.takeIf { it.isNotBlank() }
    if (explicit != null) return explicit
    return when (kind) {
        ConversationKind.DIRECT ->
            members.firstOrNull { it.userId != currentUserId }?.fullName ?: "Личный чат"
        ConversationKind.PROJECT -> "Обсуждение проекта"
        ConversationKind.GROUP -> "Групповой чат"
        ConversationKind.TASK -> "Обсуждение задачи"
    }
}
