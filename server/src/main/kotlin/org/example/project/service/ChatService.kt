package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.model.CurrentUser
import org.example.project.realtime.ChatEventBus
import org.example.project.realtime.NotificationEventBus
import org.example.project.repository.ChatRepository
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException

class ChatService(
    private val chatRepository: ChatRepository,
    private val notificationService: NotificationService,
    private val auditService: AuditService,
    private val dsl: DSLContext,
    private val chatEventBus: ChatEventBus = ChatEventBus(),
    private val notificationEventBus: NotificationEventBus = NotificationEventBus(),
    private val attachmentService: AttachmentService? = null
) {

    fun listMessages(
        taskId: Long,
        after: Long?,
        before: Long?,
        limit: Int,
        currentUser: CurrentUser
    ): ChatMessagePageDto {
        chatRepository.findTaskById(taskId) ?: throw NotFoundException("task", taskId)
        if (!chatRepository.canRead(taskId, currentUser.userId, currentUser.hasPermission("tasks", "read_any"))) {
            throw ForbiddenException("Access to task chat denied")
        }
        val effectiveLimit = limit.coerceIn(1, 200)
        val rows = chatRepository.listMessages(taskId, after, before, effectiveLimit)
        return toPage(rows, effectiveLimit, after != null, before != null, currentUser.userId)
    }

    suspend fun sendMessage(
        taskId: Long,
        request: SendChatMessageRequest,
        currentUser: CurrentUser,
        file: IncomingMessageFile? = null
    ): ChatMessageDto {
        chatRepository.findTaskById(taskId) ?: throw NotFoundException("task", taskId)
        if (!chatRepository.canWrite(taskId, currentUser.userId, currentUser.hasPermission("tasks", "write_any"))) {
            throw ForbiddenException("Write access to task chat denied")
        }
        if (chatRepository.isTaskClosed(taskId)) {
            throw ForbiddenException("Task is closed")
        }
        if (request.content.isBlank() && file == null) {
            throw ValidationException("Message content is required", mapOf("content" to "required"))
        }
        if (request.idempotencyKey.isBlank()) {
            throw ValidationException("Idempotency key is required", mapOf("idempotencyKey" to "required"))
        }
        if (request.replyToMessageId != null) {
            val target = chatRepository.findMessageRowById(request.replyToMessageId!!)
            if (target == null || target.taskId != taskId) {
                throw ValidationException("Reply target not found in this task", mapOf("replyToMessageId" to "invalid"))
            }
        }
        if (file != null) {
            chatRepository.findMessageByIdempotencyKey(taskId, currentUser.userId, request.idempotencyKey)
                ?.let { return buildMessageDtos(listOf(it), currentUser.userId).single() }
        }

        val prepared = file?.let {
            val service = attachmentService
                ?: throw ValidationException("File attachments are not available", mapOf("file" to "unsupported"))
            service.prepareMessageFile(it.originalFileName, it.contentType, it.channel)
        }

        val result = try {
            dsl.transactionResult(org.jooq.TransactionalCallable {
                val existing = chatRepository.findMessageByIdempotencyKey(taskId, currentUser.userId, request.idempotencyKey)
                if (existing != null) {
                    return@TransactionalCallable SendOutcome(
                        buildMessageDtos(listOf(existing), currentUser.userId).single(),
                        created = false,
                        notifications = emptyList()
                    )
                }
                val trimmedContent = request.content.trim()
                val mentionedUserIds = resolveMentions(taskId, trimmedContent, currentUser)
                val record = chatRepository.insertMessage(
                    taskId = taskId,
                    authorId = currentUser.userId,
                    type = ChatMessageType.USER,
                    content = trimmedContent,
                    idempotencyKey = request.idempotencyKey,
                    replyToId = request.replyToMessageId
                )
                if (mentionedUserIds.isNotEmpty()) {
                    chatRepository.insertMentions(record.id!!, mentionedUserIds)
                }
                if (prepared != null) {
                    val attachmentId = attachmentService!!.attachPreparedFile(
                        prepared, "TASK_CHAT_MESSAGE", record.id!!, currentUser.userId
                    )
                    auditService.log(
                        currentUser.userId,
                        "ATTACHMENT_UPLOAD",
                        "ATTACHMENT",
                        attachmentId,
                        mapOf(
                            "entityType" to "TASK_CHAT_MESSAGE",
                            "entityId" to record.id!!,
                            "taskId" to taskId,
                            "fileName" to prepared.fileName,
                            "sizeBytes" to prepared.sizeBytes,
                            "sha256" to (prepared.sha256 ?: "")
                        )
                    )
                }
                val notifications = notificationService.createForNewMessage(taskId, record.id!!, currentUser.userId)
                auditService.log(
                    currentUser.userId,
                    "CHAT_MESSAGE_SEND",
                    "TASK",
                    taskId,
                    mapOf("messageId" to record.id!!, "idempotencyKey" to request.idempotencyKey)
                )
                val messageRow = chatRepository.findMessageRowById(record.id!!)!!
                SendOutcome(
                    buildMessageDtos(listOf(messageRow), currentUser.userId).single(),
                    created = true,
                    notifications = notifications
                )
            })
        } catch (e: DataAccessException) {
            prepared?.let { attachmentService?.discardMessageFile(it) }
            if (e.sqlState() != "23505") throw e
            SendOutcome(
                chatRepository.findMessageByIdempotencyKey(taskId, currentUser.userId, request.idempotencyKey)
                    ?.let { row -> buildMessageDtos(listOf(row), currentUser.userId).single() }
                    ?: throw e,
                created = false,
                notifications = emptyList()
            )
        } catch (e: Exception) {
            prepared?.let { attachmentService?.discardMessageFile(it) }
            throw e
        }
        if (result.created) {
            chatEventBus.publish(result.message)
            notificationEventBus.publishAll(result.notifications)
        } else {
            prepared?.let { attachmentService?.discardMessageFile(it) }
        }
        return result.message
    }

    fun addSystemEvent(taskId: Long, request: AddSystemEventRequest, currentUser: CurrentUser): ChatMessageDto {
        chatRepository.findTaskById(taskId) ?: throw NotFoundException("task", taskId)
        if (!currentUser.hasPermission("tasks", "system_event")) {
            throw ForbiddenException("Missing permission tasks:system_event")
        }
        if (chatRepository.isTaskClosed(taskId)) {
            throw ForbiddenException("Task is closed")
        }
        if (request.content.isBlank()) {
            throw ValidationException("Event content is required", mapOf("content" to "required"))
        }
        val event = dsl.transactionResult(org.jooq.TransactionalCallable {
            val record = chatRepository.insertMessage(
                taskId = taskId,
                authorId = currentUser.userId,
                type = ChatMessageType.SYSTEM,
                content = request.content.trim()
            )
            auditService.log(
                currentUser.userId,
                "CHAT_SYSTEM_EVENT",
                "TASK",
                taskId,
                mapOf("messageId" to record.id!!, "type" to "SYSTEM")
            )
            chatRepository.findMessageRowById(record.id!!)!!.let {
                buildMessageDtos(listOf(it), currentUser.userId).single()
            }
        })
        chatEventBus.publish(event)
        return event
    }

    fun listChats(
        query: String?,
        filter: TaskChatListFilter,
        limit: Int,
        currentUser: CurrentUser
    ): List<TaskChatListItemDto> {
        val tasks = chatRepository.listVisibleTasks(
            currentUser.userId,
            currentUser.hasPermission("tasks", "read_any"),
            query
        )
        if (tasks.isEmpty()) return emptyList()
        val taskIds = tasks.map { it.taskId }
        val cursors = chatRepository.readCursorsForTasks(taskIds, currentUser.userId)
        val lastIds = chatRepository.lastMessageIds(taskIds)
        val lastMessages = chatRepository.listMessageRowsByIds(lastIds.values).associateBy { it.id }
        val unreadRefs = chatRepository.unreadMessageRefs(taskIds, currentUser.userId)
        val mentionRefs = chatRepository.mentionEventsForTasks(taskIds, currentUser.userId)
        val pinned = chatRepository.pinnedTaskIds(currentUser.userId, taskIds)

        val items = tasks.map { task ->
            val cursor = cursors[task.taskId] ?: 0L
            val last = lastIds[task.taskId]?.let { lastMessages[it] }
            TaskChatListItemDto(
                taskId = task.taskId,
                number = task.number,
                title = task.title,
                status = task.status,
                priority = task.priority,
                assigneeId = task.assigneeId,
                lastMessageId = last?.id,
                lastMessageSnippet = last?.content?.take(160),
                lastMessageAuthor = last?.authorFullName,
                lastMessageAt = last?.createdAt?.toString(),
                unreadCount = unreadRefs.count { it.first == task.taskId && it.second > cursor },
                mentionCount = mentionRefs.count { it.first == task.taskId && it.second > cursor },
                isPinned = pinned.contains(task.taskId)
            )
        }
        val filtered = when (filter) {
            TaskChatListFilter.ALL -> items
            TaskChatListFilter.UNREAD -> items.filter { it.unreadCount > 0 }
            TaskChatListFilter.MENTIONS -> items.filter { it.mentionCount > 0 }
            TaskChatListFilter.PINNED -> items.filter { it.isPinned }
        }
        return filtered
            .sortedWith(
                compareByDescending<TaskChatListItemDto> { it.isPinned }
                    .thenByDescending { it.lastMessageId ?: 0L }
            )
            .take(limit.coerceIn(1, 200))
    }

    fun markRead(taskId: Long, request: MarkChatReadRequest, currentUser: CurrentUser): Long {
        chatRepository.findTaskById(taskId) ?: throw NotFoundException("task", taskId)
        if (!chatRepository.canRead(taskId, currentUser.userId, currentUser.hasPermission("tasks", "read_any"))) {
            throw ForbiddenException("Access to task chat denied")
        }
        if (request.lastReadMessageId > 0) {
            val target = chatRepository.findMessageRowById(request.lastReadMessageId)
            if (target == null || target.taskId != taskId) {
                throw ValidationException(
                    "Message not found in this task",
                    mapOf("lastReadMessageId" to "invalid")
                )
            }
        }
        val effective = chatRepository.raiseReadCursor(taskId, currentUser.userId, request.lastReadMessageId)
        chatEventBus.publishEvent(
            ChatEventBus.WorkspaceEvent(
                category = EVENT_CHAT_READ,
                taskId = taskId,
                userId = currentUser.userId,
                lastReadMessageId = effective
            )
        )
        return effective
    }

    fun addReaction(taskId: Long, messageId: Long, emoji: String, currentUser: CurrentUser): List<ReactionSummaryDto> =
        changeReaction(taskId, messageId, emoji, currentUser, add = true)

    fun removeReaction(taskId: Long, messageId: Long, emoji: String, currentUser: CurrentUser): List<ReactionSummaryDto> =
        changeReaction(taskId, messageId, emoji, currentUser, add = false)

    private fun changeReaction(
        taskId: Long,
        messageId: Long,
        emoji: String,
        currentUser: CurrentUser,
        add: Boolean
    ): List<ReactionSummaryDto> {
        chatRepository.findTaskById(taskId) ?: throw NotFoundException("task", taskId)
        if (!chatRepository.canWrite(taskId, currentUser.userId, currentUser.hasPermission("tasks", "write_any"))) {
            throw ForbiddenException("Write access to task chat denied")
        }
        if (chatRepository.isTaskClosed(taskId)) {
            throw ForbiddenException("Task is closed")
        }
        val cleanEmoji = emoji.trim()
        if (cleanEmoji.isEmpty() || cleanEmoji.length > 16) {
            throw ValidationException("Invalid emoji", mapOf("emoji" to "invalid"))
        }
        val message = chatRepository.findMessageRowById(messageId)
        if (message == null || message.taskId != taskId) {
            throw NotFoundException("message", messageId)
        }
        val reactions = dsl.transactionResult(org.jooq.TransactionalCallable {
            if (add) {
                chatRepository.addReaction(messageId, currentUser.userId, cleanEmoji)
            } else {
                chatRepository.removeReaction(messageId, currentUser.userId, cleanEmoji)
            }
            auditService.log(
                currentUser.userId,
                if (add) "CHAT_REACTION_ADD" else "CHAT_REACTION_REMOVE",
                "TASK",
                taskId,
                mapOf("messageId" to messageId, "emoji" to cleanEmoji)
            )
            chatRepository.reactionsForMessages(listOf(messageId), currentUser.userId)[messageId] ?: emptyList()
        })
        chatEventBus.publishEvent(
            ChatEventBus.WorkspaceEvent(
                category = EVENT_CHAT_REACTION,
                taskId = taskId,
                messageId = messageId,
                reactions = reactions
            )
        )
        return reactions
    }

    fun setDialogPinned(taskId: Long, pinned: Boolean, currentUser: CurrentUser): Boolean {
        chatRepository.findTaskById(taskId) ?: throw NotFoundException("task", taskId)
        if (!chatRepository.canWrite(taskId, currentUser.userId, currentUser.hasPermission("tasks", "write_any"))) {
            throw ForbiddenException("Write access to task chat denied")
        }
        if (chatRepository.isTaskClosed(taskId)) {
            throw ForbiddenException("Task is closed")
        }
        dsl.transactionResult(org.jooq.TransactionalCallable {
            if (pinned) {
                chatRepository.pinDialog(taskId, currentUser.userId)
            } else {
                chatRepository.unpinDialog(taskId, currentUser.userId)
            }
            auditService.log(
                currentUser.userId,
                if (pinned) "CHAT_DIALOG_PIN" else "CHAT_DIALOG_UNPIN",
                "TASK",
                taskId,
                mapOf("pinned" to pinned)
            )
        })
        chatEventBus.publishEvent(
            ChatEventBus.WorkspaceEvent(category = EVENT_CHAT_PIN, taskId = taskId, isPinned = pinned)
        )
        return pinned
    }

    fun searchMessages(
        taskId: Long,
        query: String?,
        limit: Int,
        currentUser: CurrentUser
    ): List<ChatSearchResultDto> {
        chatRepository.findTaskById(taskId) ?: throw NotFoundException("task", taskId)
        if (!chatRepository.canRead(taskId, currentUser.userId, currentUser.hasPermission("tasks", "read_any"))) {
            throw ForbiddenException("Access to task chat denied")
        }
        val trimmed = query?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        return chatRepository.searchMessages(taskId, trimmed, limit.coerceIn(1, 50)).map { row ->
            ChatSearchResultDto(
                messageId = row.id,
                taskId = row.taskId,
                content = row.content,
                createdAt = row.createdAt.toString(),
                author = row.authorId?.let { ChatAuthorDto(it, row.authorFullName ?: "") }
            )
        }
    }

    private fun resolveMentions(taskId: Long, content: String, currentUser: CurrentUser): List<Long> {
        if (!content.contains('@')) return emptyList()
        val candidates = chatRepository.listMentionCandidates(taskId)
        if (candidates.isEmpty()) return emptyList()
        val mentioned = mutableSetOf<Long>()
        for (match in LOGIN_MENTION.findAll(content)) {
            val login = match.groupValues[1]
            val byLogin = candidates.filter { it.login.equals(login, ignoreCase = true) }
            if (byLogin.size == 1) {
                mentioned += byLogin.single().userId
            }
        }
        val byFullName = candidates.groupBy { it.fullName }
        for ((fullName, users) in byFullName) {
            if (content.contains("@$fullName")) {
                if (users.size > 1) {
                    throw ValidationException(
                        "Mention @$fullName is ambiguous",
                        mapOf("content" to "ambiguous_mention")
                    )
                }
                mentioned += users.single().userId
            }
        }
        mentioned -= currentUser.userId
        return mentioned.toList()
    }

    private data class SendOutcome(
        val message: ChatMessageDto,
        val created: Boolean,
        val notifications: List<NotificationEventBus.Event>
    )

    private fun toPage(
        rows: List<ChatRepository.MessageRow>,
        limit: Int,
        isAfter: Boolean,
        isBefore: Boolean,
        viewerId: Long
    ): ChatMessagePageDto {
        val hasMore = rows.size > limit
        val messages = buildMessageDtos(rows.take(limit), viewerId)
        return when {
            isBefore -> ChatMessagePageDto(
                messages = messages,
                nextBefore = if (hasMore) messages.last().id else null,
                hasMore = hasMore
            )
            else -> ChatMessagePageDto(
                messages = messages,
                nextAfter = if (hasMore) messages.last().id else null,
                hasMore = hasMore
            )
        }
    }

    private fun buildMessageDtos(
        rows: List<ChatRepository.MessageRow>,
        viewerId: Long
    ): List<ChatMessageDto> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.id }
        val attachmentMap = attachmentsFor(ids)
        val reactionsMap = chatRepository.reactionsForMessages(ids, viewerId)
        val mentionsMap = chatRepository.mentionedUserIdsForMessages(ids)
        val cursors = chatRepository.readCursorsForTask(rows.first().taskId)
        val replyIds = rows.mapNotNull { it.replyToId }.distinct()
        val replyMap = chatRepository.listMessageRowsByIds(replyIds).associateBy { it.id }
        return rows.map { row ->
            ChatMessageDto(
                id = row.id,
                taskId = row.taskId,
                author = row.authorId?.let { ChatAuthorDto(it, row.authorFullName ?: "") },
                type = ChatMessageType.valueOf(row.type),
                content = row.content,
                createdAt = row.createdAt.toString(),
                attachments = attachmentMap[row.id] ?: emptyList(),
                replyTo = row.replyToId?.let { replyMap[it] }?.let { reply ->
                    ReplyPreviewDto(
                        id = reply.id,
                        author = reply.authorId?.let { ChatAuthorDto(it, reply.authorFullName ?: "") },
                        contentPreview = reply.content.take(120)
                    )
                },
                reactions = reactionsMap[row.id] ?: emptyList(),
                mentionedUserIds = mentionsMap[row.id] ?: emptyList(),
                readByCount = cursors.count { it.second >= row.id }
            )
        }
    }

    private fun attachmentsFor(messageIds: List<Long>): Map<Long, List<AttachmentDto>> =
        attachmentService?.listForMessageAttachments("TASK_CHAT_MESSAGE", messageIds) ?: emptyMap()

    private companion object {
        const val EVENT_CHAT_REACTION = "TASK_CHAT_REACTION"
        const val EVENT_CHAT_READ = "TASK_CHAT_READ"
        const val EVENT_CHAT_PIN = "TASK_CHAT_PIN"
        val LOGIN_MENTION = Regex("@([A-Za-z0-9_.-]+)")
    }
}
