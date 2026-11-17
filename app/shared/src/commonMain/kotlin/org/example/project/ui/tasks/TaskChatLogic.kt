package org.example.project.ui.tasks

import org.example.project.dto.AttachmentDto
import org.example.project.dto.ChatMessageDto
import org.example.project.dto.DocumentDto
import org.example.project.dto.DocumentLinkType
import org.example.project.dto.TaskChatListFilter
import org.example.project.dto.TaskChatListItemDto
import org.example.project.dto.UserOptionDto

/** Подписи фильтров списка чатов; enum приходит из core. */
fun TaskChatListFilter.label(): String = when (this) {
    TaskChatListFilter.ALL -> "Все"
    TaskChatListFilter.UNREAD -> "Непрочитанные"
    TaskChatListFilter.MENTIONS -> "Упоминания"
    TaskChatListFilter.PINNED -> "Закреплённые"
}

/** Правая контекстная панель свёрнута по умолчанию ниже 1440 dp. */
fun taskChatContextCollapsed(widthDp: Int): Boolean = widthDp < 1440

/** Вкладка «Чат» открывает текущую задачу либо первую в серверной сортировке. */
fun defaultTaskChatId(selectedTaskId: Long?, chats: List<TaskChatListItemDto>): Long? =
    selectedTaskId ?: chats.firstOrNull()?.taskId

/** Клиентское зеркало фильтров списка чатов: мгновенный отклик поверх серверной выдачи. */
fun filterChatList(
    items: List<TaskChatListItemDto>,
    filter: TaskChatListFilter,
    query: String
): List<TaskChatListItemDto> {
    val byFilter = when (filter) {
        TaskChatListFilter.ALL -> items
        TaskChatListFilter.UNREAD -> items.filter { it.unreadCount > 0 }
        TaskChatListFilter.MENTIONS -> items.filter { it.mentionCount > 0 }
        TaskChatListFilter.PINNED -> items.filter { it.isPinned }
    }
    val q = query.trim().lowercase()
    val byQuery = if (q.isEmpty()) {
        byFilter
    } else {
        byFilter.filter {
            it.number.lowercase().contains(q) ||
                it.title.lowercase().contains(q) ||
                it.lastMessageSnippet?.lowercase()?.contains(q) == true ||
                it.lastMessageAuthor?.lowercase()?.contains(q) == true
        }
    }
    return byQuery.sortedWith(compareByDescending<TaskChatListItemDto> { it.isPinned })
}

/** Автопрокрутка к новому сообщению: только если пользователь у низа или это его собственное сообщение. */
fun shouldAutoScrollToNew(lastVisibleIndex: Int, itemCountBeforeAppend: Int, isOwnMessage: Boolean): Boolean =
    isOwnMessage || lastVisibleIndex >= itemCountBeforeAppend - 2

enum class ChatTextKind { PLAIN, BOLD, ITALIC, CODE }

data class ChatTextSegment(val text: String, val kind: ChatTextKind)

/**
 * Ограниченное форматирование без зависимостей: **жирный**, *курсив*, `код`.
 * Маркеры без пары остаются обычным текстом. Вложенность не поддерживается.
 */
fun parseChatFormatting(text: String): List<ChatTextSegment> {
    val segments = mutableListOf<ChatTextSegment>()
    val plain = StringBuilder()
    var i = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            segments += ChatTextSegment(plain.toString(), ChatTextKind.PLAIN)
            plain.clear()
        }
    }

    fun findClosing(marker: String, from: Int): Int {
        var idx = text.indexOf(marker, from)
        while (idx >= 0 && idx == from) idx = text.indexOf(marker, idx + marker.length)
        return idx
    }

    while (i < text.length) {
        when {
            text.startsWith("**", i) -> {
                val end = findClosing("**", i + 2)
                if (end > i + 2) {
                    flushPlain()
                    segments += ChatTextSegment(text.substring(i + 2, end), ChatTextKind.BOLD)
                    i = end + 2
                } else {
                    plain.append("**")
                    i += 2
                }
            }
            text.startsWith("`", i) -> {
                val end = findClosing("`", i + 1)
                if (end > i + 1) {
                    flushPlain()
                    segments += ChatTextSegment(text.substring(i + 1, end), ChatTextKind.CODE)
                    i = end + 1
                } else {
                    plain.append("`")
                    i += 1
                }
            }
            text.startsWith("*", i) -> {
                val end = findClosing("*", i + 1)
                if (end > i + 1) {
                    flushPlain()
                    segments += ChatTextSegment(text.substring(i + 1, end), ChatTextKind.ITALIC)
                    i = end + 1
                } else {
                    plain.append("*")
                    i += 1
                }
            }
            else -> {
                plain.append(text[i])
                i += 1
            }
        }
    }
    flushPlain()
    return segments
}

/** Сниппет последнего сообщения для левого списка. */
fun chatSnippet(content: String, max: Int = 80): String {
    val oneLine = content.lineSequence().firstOrNull()?.trim().orEmpty()
    return if (oneLine.length <= max) oneLine else oneLine.take(max - 1).trimEnd() + "…"
}

/** Все вложения сообщений чата без дублей. */
fun collectChatAttachments(messages: List<ChatMessageDto>): List<AttachmentDto> =
    messages.flatMap { it.attachments }.distinctBy { it.id }

/** Документы, реально связанные с задачей через document_links. */
fun linkedTaskDocuments(documents: List<DocumentDto>, taskId: Long): List<DocumentDto> =
    documents.filter { doc ->
        doc.links.any { it.linkType == DocumentLinkType.TASK && it.targetId == taskId }
    }

/** Текущий «@-токен» в конце черновика для подсказки упоминаний; null, если ввода упоминания нет. */
fun mentionQuery(draft: String): String? {
    val at = draft.lastIndexOf('@')
    if (at < 0) return null
    if (at > 0 && !draft[at - 1].isWhitespace()) return null
    val token = draft.substring(at + 1)
    return if (token.any { it.isWhitespace() }) null else token
}

fun mentionSuggestions(participants: List<UserOptionDto>, draft: String): List<UserOptionDto> {
    val query = mentionQuery(draft) ?: return emptyList()
    return participants.filter { it.fullName.lowercase().startsWith(query.lowercase()) }
}

/** Подстановка упоминания: текущий «@токен» заменяется на «@ФИО ». */
fun insertMention(draft: String, fullName: String): String {
    val at = draft.lastIndexOf('@')
    if (at < 0) return "$draft@$fullName "
    return draft.substring(0, at) + "@$fullName "
}
