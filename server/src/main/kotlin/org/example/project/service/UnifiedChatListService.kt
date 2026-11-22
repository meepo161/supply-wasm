package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ForbiddenException
import org.example.project.exception.ValidationException
import org.example.project.model.CurrentUser
import org.example.project.repository.*
import org.jooq.DSLContext
import java.nio.charset.StandardCharsets
import java.util.Base64

class UnifiedChatListService(
    private val conversationRepository: ConversationRepository,
    private val chatRepository: ChatRepository,
    private val chatService: ChatService,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {
    /**
     * Traverses a live set rather than an immutable ordering snapshot. The opaque cursor contains
     * only already returned kind/id keys; each page re-sorts current candidates and excludes them
     * in the source SQL so moved items are neither repeated nor skipped.
     */
    fun list(
        query: String?,
        kind: ConversationKind?,
        filter: ConversationListFilter,
        cursor: String?,
        limit: Int,
        currentUser: CurrentUser
    ): ConversationListPageDto {
        val effectiveLimit = limit.coerceAtMost(100)
        val decodedCursor = cursor?.let(::decodeCursor)
        val archivedOnly = when (filter) {
            ConversationListFilter.ARCHIVED -> true
            else -> false
        }
        val unreadOnly = filter == ConversationListFilter.UNREAD
        val mentionsOnly = filter == ConversationListFilter.MENTIONS
        val candidates = mutableListOf<WorkspaceCandidate>()
        if (kind == null || kind != ConversationKind.TASK) {
            candidates += conversationRepository.listWorkspaceConversations(
                userId = currentUser.userId,
                query = query,
                unreadOnly = unreadOnly,
                mentionsOnly = mentionsOnly,
                archivedOnly = archivedOnly,
                kind = kind?.name,
                cursor = decodedCursor,
                limit = effectiveLimit
            )
        }
        if (kind == null || kind == ConversationKind.TASK) {
            candidates += chatRepository.listWorkspaceTasks(
                userId = currentUser.userId,
                hasReadAny = currentUser.hasPermission("tasks", "read_any"),
                hasWriteAny = currentUser.hasPermission("tasks", "write_any"),
                query = query,
                unreadOnly = unreadOnly,
                mentionsOnly = mentionsOnly,
                archivedOnly = archivedOnly,
                cursor = decodedCursor,
                limit = effectiveLimit
            )
        }
        val ordered = candidates.sortedWith(workspaceOrder)
        val pageCandidates = ordered.take(effectiveLimit)
        val hasMore = ordered.size > effectiveLimit
        val nextSeen = decodedCursor?.seen.orEmpty() + pageCandidates.map {
            WorkspaceKey(it.kind, it.id)
        }.toSet()
        if (hasMore && nextSeen.size > MAX_SEEN) {
            throw ValidationException("Cursor traversal limit reached", mapOf("cursor" to "too_many_seen"))
        }
        return ConversationListPageDto(
            items = hydrate(pageCandidates),
            nextCursor = if (hasMore) encodeCursor(nextSeen) else null,
            hasMore = hasMore
        )
    }

    suspend fun changeState(
        kind: ConversationKind,
        id: Long,
        request: ConversationDialogStateRequest,
        currentUser: CurrentUser
    ): ConversationListItemDto {
        item(kind, id, currentUser)
        if (request.pinned == null && request.muted == null && request.archived == null) {
            return item(kind, id, currentUser)
        }
        if (kind == ConversationKind.TASK) {
            if (request.pinned != null && (request.muted != null || request.archived != null)) {
                throw ValidationException(
                    "Task pin must be updated separately",
                    mapOf("state" to "composite_not_supported")
                )
            }
            val previous = chatRepository.taskDialogState(id, currentUser.userId)
            val updated = DialogState(
                pinned = request.pinned ?: previous.pinned,
                muted = request.muted ?: previous.muted,
                archived = request.archived ?: previous.archived
            )
            if (request.pinned != null && updated.pinned != previous.pinned) {
                chatService.setDialogPinned(id, updated.pinned, currentUser)
            }
            if (updated.muted != previous.muted || updated.archived != previous.archived) {
                dsl.transactionResult(org.jooq.TransactionalCallable {
                    chatRepository.updateTaskDialogState(
                        id, currentUser.userId, request.muted, request.archived
                    )
                    auditService.log(
                        currentUser.userId,
                        "CHAT_DIALOG_STATE",
                        "TASK",
                        id,
                        mapOf("muted" to updated.muted, "archived" to updated.archived)
                    )
                })
            }
        } else {
            val previous = conversationRepository.conversationDialogState(id, currentUser.userId)
            val updated = DialogState(
                pinned = request.pinned ?: previous.pinned,
                muted = request.muted ?: previous.muted,
                archived = request.archived ?: previous.archived
            )
            if (updated != previous) {
                dsl.transactionResult(org.jooq.TransactionalCallable {
                    conversationRepository.updateConversationDialogState(
                        id, currentUser.userId, request.pinned, request.muted, request.archived
                    )
                    auditService.log(
                        currentUser.userId,
                        "CONVERSATION_DIALOG_STATE",
                        "CONVERSATION",
                        id,
                        mapOf(
                            "kind" to kind.name,
                            "pinned" to updated.pinned,
                            "muted" to updated.muted,
                            "archived" to updated.archived
                        )
                    )
                })
            }
        }
        return item(kind, id, currentUser)
    }

    private fun item(kind: ConversationKind, id: Long, currentUser: CurrentUser): ConversationListItemDto {
        val candidate = if (kind == ConversationKind.TASK) {
            chatRepository.listWorkspaceTasks(
                currentUser.userId,
                currentUser.hasPermission("tasks", "read_any"),
                currentUser.hasPermission("tasks", "write_any"),
                query = null,
                unreadOnly = false,
                mentionsOnly = false,
                archivedOnly = null,
                cursor = null,
                limit = 1,
                requiredId = id
            )
        } else {
            conversationRepository.listWorkspaceConversations(
                currentUser.userId,
                query = null,
                unreadOnly = false,
                mentionsOnly = false,
                archivedOnly = null,
                kind = kind.name,
                cursor = null,
                limit = 1,
                requiredId = id
            )
        }.singleOrNull() ?: throw ForbiddenException("Access to conversation denied")
        return hydrate(listOf(candidate)).single()
    }

    private fun hydrate(candidates: List<WorkspaceCandidate>): List<ConversationListItemDto> {
        if (candidates.isEmpty()) return emptyList()
        val conversationMembers = conversationRepository.workspaceConversationMembers(
            candidates.filter { it.kind != "TASK" }.map { it.id }
        )
        val taskMembers = chatRepository.workspaceTaskMembers(candidates.filter { it.kind == "TASK" }.map { it.id })
        return candidates.map { candidate ->
            val members = if (candidate.kind == "TASK") {
                taskMembers[candidate.id].orEmpty()
            } else {
                conversationMembers[candidate.id].orEmpty()
            }
            ConversationListItemDto(
                kind = ConversationKind.valueOf(candidate.kind),
                id = candidate.id,
                title = candidate.title,
                projectId = candidate.projectId,
                taskId = candidate.taskId,
                canWrite = candidate.canWrite,
                members = members.map { ConversationMemberDto(it.first, it.second) },
                lastMessagePreview = candidate.lastMessagePreview,
                lastMessageAuthor = candidate.lastMessageAuthorId?.let {
                    ChatAuthorDto(it, candidate.lastMessageAuthor.orEmpty())
                },
                lastMessageAt = candidate.lastMessageAt?.toInstant()?.toString(),
                unreadCount = candidate.unreadCount,
                mentionCount = candidate.mentionCount,
                isPinned = candidate.isPinned,
                isMuted = candidate.isMuted,
                isArchived = candidate.isArchived
            )
        }
    }

    private fun encodeCursor(seen: Set<WorkspaceKey>): String {
        val value = seen.joinToString(",") { "${it.kind}:${it.id}" }
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
        if (encoded.length > MAX_CURSOR_LENGTH) {
            throw ValidationException("Cursor is too large", mapOf("cursor" to "too_large"))
        }
        return encoded
    }

    private fun decodeCursor(cursor: String): WorkspaceCursor = try {
        if (cursor.isEmpty() || cursor.length > MAX_CURSOR_LENGTH) throw IllegalArgumentException()
        val value = String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8)
        val keys = value.split(',').map { entry ->
            val parts = entry.split(':')
            if (parts.size != 2) throw IllegalArgumentException()
            val id = parts[1].toLong()
            if (id <= 0) throw IllegalArgumentException()
            WorkspaceKey(ConversationKind.valueOf(parts[0]).name, id)
        }
        if (keys.isEmpty() || keys.size > MAX_SEEN || keys.distinct().size != keys.size) {
            throw IllegalArgumentException()
        }
        WorkspaceCursor(keys.toSet())
    } catch (_: RuntimeException) {
        throw ValidationException("Invalid cursor", mapOf("cursor" to "invalid"))
    }

    private companion object {
        const val MAX_SEEN = 1000
        const val MAX_CURSOR_LENGTH = 32_768
        val workspaceOrder = compareByDescending<WorkspaceCandidate> { it.isPinned }
            .thenByDescending { it.activity }
            .thenByDescending { it.kind }
            .thenByDescending { it.id }
    }
}
