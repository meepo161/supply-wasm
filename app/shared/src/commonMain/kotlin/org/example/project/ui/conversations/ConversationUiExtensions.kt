package org.example.project.ui.conversations

import org.example.project.data.AttachmentFileData
import org.example.project.dto.ChatAuthorDto
import org.example.project.dto.ConversationDto
import org.example.project.dto.ConversationKind
import org.example.project.dto.ConversationListFilter
import org.example.project.dto.ConversationListItemDto
import org.example.project.dto.UserOptionDto

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

fun showConversationList(mode: ConversationWorkspaceMode, hasSelection: Boolean): Boolean =
    mode == ConversationWorkspaceMode.SPLIT || !hasSelection

fun ConversationKind.localizedName(): String = when (this) {
    ConversationKind.DIRECT -> "Личные"
    ConversationKind.GROUP -> "Групповые"
    ConversationKind.PROJECT -> "Проекты"
    ConversationKind.TASK -> "Задачи"
    ConversationKind.CHANNEL -> "Каналы"
}

fun ConversationListFilter.localizedName(): String = when (this) {
    ConversationListFilter.ALL -> "Все"
    ConversationListFilter.UNREAD -> "Непрочитанные"
    ConversationListFilter.MENTIONS -> "Упоминания"
    ConversationListFilter.ARCHIVED -> "Архив"
}

fun ConversationListItemDto.previewText(currentUserId: Long): String {
    val preview = lastMessagePreview?.takeIf { it.isNotBlank() } ?: return "Сообщений пока нет"
    val author = lastMessageAuthor ?: return preview
    return "${if (author.userId == currentUserId) "Вы" else author.fullName}: $preview"
}

fun conversationTimeLabel(value: String?): String {
    if (value.isNullOrBlank()) return ""
    val date = value.substringBefore("T").split("-")
    val dayMonth = if (date.size == 3) "${date[2]}.${date[1]}" else date.joinToString("-")
    val time = value.substringAfter("T", "").take(5)
    return if (time.isBlank()) dayMonth else "$dayMonth $time"
}

fun unreadBadgeLabel(count: Int): String = "Непрочитанные: $count"
fun mentionBadgeLabel(count: Int): String = "Упоминания: $count"

enum class ConversationEmptyState { NO_CHATS, NO_MATCHES }

fun conversationEmptyState(
    query: String,
    kind: ConversationKind?,
    filter: ConversationListFilter
): ConversationEmptyState = if (
    query.isBlank() && kind == null && filter == ConversationListFilter.ALL
) ConversationEmptyState.NO_CHATS else ConversationEmptyState.NO_MATCHES

fun filterEmployeeOptions(users: List<UserOptionDto>, query: String): List<UserOptionDto> {
    val normalized = query.trim().lowercase().replace('ё', 'е')
    if (normalized.isEmpty()) return users
    return users.filter { it.fullName.lowercase().replace('ё', 'е').contains(normalized) }
}

fun applyConversationDialogState(
    items: List<ConversationListItemDto>,
    updated: ConversationListItemDto
): List<ConversationListItemDto> = items.map { item ->
    if (item.kind == updated.kind && item.id == updated.id) updated else item
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
        ConversationKind.CHANNEL -> "Канал"
    }
}

fun ConversationListItemDto.toConversationDto(): ConversationDto = ConversationDto(
    id = if (kind == ConversationKind.TASK) -id else id,
    kind = kind,
    title = title,
    projectId = projectId ?: if (kind == ConversationKind.PROJECT) id else null,
    taskId = taskId ?: if (kind == ConversationKind.TASK) id else null,
    canWrite = canWrite,
    isSavedMessages = isSavedMessages,
    members = members
)

fun mergeWorkspaceItems(
    current: List<ConversationListItemDto>,
    incoming: List<ConversationListItemDto>
): List<ConversationListItemDto> {
    val result = current.associateByTo(linkedMapOf()) { it.kind to it.id }
    incoming.forEach { result[it.kind to it.id] = it }
    return result.values.toList()
}

data class WorkspacePreview(
    val text: String?,
    val revoked: Boolean = false,
    val author: ChatAuthorDto? = null,
    val createdAt: String? = null
)

fun updateWorkspacePreview(
    items: List<ConversationListItemDto>,
    kind: ConversationKind,
    id: Long,
    preview: WorkspacePreview
): List<ConversationListItemDto> = items.map { item ->
    if (item.kind == kind && item.id == id) {
        item.copy(
            lastMessagePreview = if (preview.revoked) "Сообщение удалено" else preview.text,
            lastMessageAuthor = preview.author,
            lastMessageAt = preview.createdAt ?: item.lastMessageAt
        )
    } else {
        item
    }
}

data class ConversationFileSignature(
    val fileName: String,
    val mimeType: String?,
    val contentHash: Long
)

data class ConversationCommandSignature(
    val content: String,
    val replyToMessageId: Long?,
    val files: List<ConversationFileSignature>
)

fun conversationCommandSignature(
    content: String,
    replyToMessageId: Long?,
    files: List<AttachmentFileData>
): ConversationCommandSignature = ConversationCommandSignature(
    content = content.trim(),
    replyToMessageId = replyToMessageId,
    files = files.map { file ->
        var hash = -3750763034362895579L
        file.bytes.forEach { byte ->
            hash = (hash xor byte.toLong()) * 1099511628211L
        }
        ConversationFileSignature(file.fileName, file.mimeType, hash)
    }
)

private data class PendingConversationCommand(
    val signature: ConversationCommandSignature?,
    val key: String
)

class ConversationLocalStateStore {
    private val drafts = mutableMapOf<String, String>()
    private val pendingFiles = mutableMapOf<String, List<AttachmentFileData>>()
    private val replies = mutableMapOf<String, Long>()
    private val scrollPositions = mutableMapOf<String, Int>()
    private val commandKeys = mutableMapOf<String, PendingConversationCommand>()

    fun draft(key: String): String = drafts[key].orEmpty()
    fun setDraft(key: String, value: String) {
        if (value.isEmpty()) drafts -= key else drafts[key] = value
    }

    fun files(key: String): List<AttachmentFileData> = pendingFiles[key].orEmpty()
    fun setFiles(key: String, value: List<AttachmentFileData>) {
        if (value.isEmpty()) pendingFiles -= key else pendingFiles[key] = value.toList()
    }

    fun replyTo(key: String): Long? = replies[key]
    fun setReplyTo(key: String, value: Long?) {
        if (value == null) replies -= key else replies[key] = value
    }

    fun signature(key: String): ConversationCommandSignature =
        conversationCommandSignature(draft(key), replyTo(key), files(key))

    fun scrollPosition(key: String): Int = scrollPositions[key] ?: 0
    fun setScrollPosition(key: String, value: Int) {
        scrollPositions[key] = value.coerceAtLeast(0)
    }

    fun commandKey(key: String, create: () -> String): String =
        commandKeys.getOrPut(key) { PendingConversationCommand(null, create()) }.key

    fun commandKey(key: String, signature: ConversationCommandSignature, create: () -> String): String {
        val current = commandKeys[key]
        if (current == null || current.signature != signature) {
            commandKeys[key] = PendingConversationCommand(signature, create())
        }
        return commandKeys.getValue(key).key
    }

    fun commandKeyOrNull(key: String): String? = commandKeys[key]?.key

    fun completeCommand(key: String) {
        commandKeys -= key
    }

    fun clearComposerAfterSuccess(key: String) {
        drafts -= key
        pendingFiles -= key
        replies -= key
        completeCommand(key)
    }

    fun clearComposerAfterSuccess(key: String, signature: ConversationCommandSignature) {
        if (commandKeys[key]?.signature == signature) commandKeys -= key
        if (this.signature(key) == signature) {
            drafts -= key
            pendingFiles -= key
            replies -= key
        }
    }

    fun clearSensitive(key: String) {
        drafts -= key
        pendingFiles -= key
        replies -= key
        scrollPositions -= key
        commandKeys -= key
    }

    fun clear() {
        drafts.clear()
        pendingFiles.clear()
        replies.clear()
        scrollPositions.clear()
        commandKeys.clear()
    }
}


class ConversationNavigationGate {
    private var current = 0L

    fun next(): Long = ++current
    fun invalidate() {
        ++current
    }
    fun isCurrent(token: Long): Boolean = token == current
}
