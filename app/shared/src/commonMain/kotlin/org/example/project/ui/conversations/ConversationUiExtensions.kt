package org.example.project.ui.conversations

import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind

/** Режим рабочей области Chat: на широком экране список остаётся под рукой. */
enum class ConversationWorkspaceMode {
    SINGLE_PANE,
    SPLIT
}

fun conversationWorkspaceMode(widthDp: Int): ConversationWorkspaceMode =
    // Ширина приходит уже без глобальной навигации ERP. При 840 dp помещаются
    // список (344 dp) и полноценная область переписки, поэтому не ждём ширины
    // всего окна в 1000 dp.
    if (widthDp >= 840) ConversationWorkspaceMode.SPLIT else ConversationWorkspaceMode.SINGLE_PANE

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
