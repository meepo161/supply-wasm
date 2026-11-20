package org.example.project.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.project.dto.*
import org.example.project.exception.ConflictException
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
    private val taskEventMutexes = Array(EVENT_LOCK_STRIPES) { Mutex() }

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

    fun validateMultipartMessage(
        taskId: Long,
        request: SendChatMessageRequest,
        currentUser: CurrentUser
    ): ChatMessageDto? {
        requireWritableTask(taskId, currentUser)
        if (request.idempotencyKey.isBlank()) {
            throw ValidationException("Idempotency key is required", mapOf("idempotencyKey" to "required"))
        }
        validateReplyTarget(taskId, request.replyToMessageId)
        return chatRepository.findMessageByIdempotencyKey(taskId, currentUser.userId, request.idempotencyKey)
            ?.let { buildMessageDtos(listOf(it), currentUser.userId).single() }
    }

    suspend fun prepareMessageFile(
        originalFileName: String?,
        contentType: String?,
        channel: io.ktor.utils.io.ByteReadChannel
    ): PreparedMessageFile = (attachmentService
        ?: throw ValidationException("File attachments are not available", mapOf("file" to "unsupported")))
        .prepareMessageFile(originalFileName, contentType, channel)

    fun discardMessageFiles(files: Iterable<PreparedMessageFile>) {
        attachmentService?.discardMessageFiles(files)
    }

    suspend fun sendMessage(
        taskId: Long,
        request: SendChatMessageRequest,
        currentUser: CurrentUser,
        files: List<PreparedMessageFile> = emptyList()
    ): ChatMessageDto {
        var preparedAttached = false
        try {
            requireWritableTask(taskId, currentUser)
            if (request.content.isBlank() && files.isEmpty()) {
                throw ValidationException("Message content is required", mapOf("content" to "required"))
            }
            if (request.idempotencyKey.isBlank()) {
                throw ValidationException("Idempotency key is required", mapOf("idempotencyKey" to "required"))
            }
            validateReplyTarget(taskId, request.replyToMessageId)
            return orderedTaskEvent(taskId) {
                requireWritableTask(taskId, currentUser)
                val result = try {
                    dsl.transactionResult(org.jooq.TransactionalCallable {
                        chatRepository.lockTask(taskId) ?: throw NotFoundException("task", taskId)
                        requireWritableTask(taskId, currentUser)
                        val existing = chatRepository.findMessageByIdempotencyKey(
                            taskId, currentUser.userId, request.idempotencyKey
                        )
                        if (existing != null) {
                            return@TransactionalCallable SendOutcome(
                                buildMessageDtos(listOf(existing), currentUser.userId).single(),
                                created = false,
                                notifications = emptyList()
                            )
                        }
                        validateReplyTarget(taskId, request.replyToMessageId)
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
                        chatRepository.insertMentions(record.id!!, mentionedUserIds)
                        files.forEach { prepared ->
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
                        val notifications = notificationService.createForNewMessage(
                            taskId, record.id!!, currentUser.userId
                        )
                        auditService.log(
                            currentUser.userId,
                            "CHAT_MESSAGE_SEND",
                            "TASK",
                            taskId,
                            mapOf("messageId" to record.id!!, "idempotencyKey" to request.idempotencyKey)
                        )
                        val eventId = chatRepository.appendEvent(
                            taskId, "MESSAGE", record.id!!, currentUser.userId
                        )
                        val messageRow = chatRepository.findMessageRowById(record.id!!)!!
                        SendOutcome(
                            buildMessageDtos(listOf(messageRow), currentUser.userId).single(),
                            created = true,
                            notifications = notifications,
                            eventId = eventId
                        )
                    })
                } catch (e: DataAccessException) {
                    if (e.sqlState() != "23505") throw e
                    SendOutcome(
                        chatRepository.findMessageByIdempotencyKey(
                            taskId, currentUser.userId, request.idempotencyKey
                        )?.let { row -> buildMessageDtos(listOf(row), currentUser.userId).single() }
                            ?: throw e,
                        created = false,
                        notifications = emptyList()
                    )
                }
                preparedAttached = result.created && files.isNotEmpty()
                if (result.created) {
                    chatEventBus.publish(result.message, result.eventId)
                    notificationEventBus.publishAll(result.notifications)
                }
                result.message
            }
        } finally {
            if (!preparedAttached) {
                attachmentService?.discardMessageFiles(files)
            }
        }
    }

    suspend fun addSystemEvent(
        taskId: Long,
        request: AddSystemEventRequest,
        currentUser: CurrentUser
    ): ChatMessageDto {
        requireWritableTask(taskId, currentUser)
        if (!currentUser.hasPermission("tasks", "system_event")) {
            throw ForbiddenException("Missing permission tasks:system_event")
        }
        if (request.content.isBlank()) {
            throw ValidationException("Event content is required", mapOf("content" to "required"))
        }
        return orderedTaskEvent(taskId) {
            val (event, eventId) = dsl.transactionResult(org.jooq.TransactionalCallable {
                chatRepository.lockTask(taskId) ?: throw NotFoundException("task", taskId)
                requireWritableTask(taskId, currentUser)
                if (!currentUser.hasPermission("tasks", "system_event")) {
                    throw ForbiddenException("Missing permission tasks:system_event")
                }
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
                val dto = buildMessageDtos(
                    listOf(chatRepository.findMessageRowById(record.id!!)!!), currentUser.userId
                ).single()
                dto to chatRepository.appendEvent(taskId, "MESSAGE", record.id!!, currentUser.userId)
            })
            chatEventBus.publish(event, eventId)
            event
        }
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

    suspend fun markRead(taskId: Long, request: MarkChatReadRequest, currentUser: CurrentUser): Long {
        requireReadableTask(taskId, currentUser)
        if (request.lastReadMessageId < 0) {
            throw ValidationException("Invalid read cursor", mapOf("lastReadMessageId" to "invalid"))
        }
        validateReadTarget(taskId, request.lastReadMessageId)
        return orderedTaskEvent(taskId) {
            val (effective, eventId) = dsl.transactionResult(org.jooq.TransactionalCallable {
                chatRepository.lockTask(taskId) ?: throw NotFoundException("task", taskId)
                requireReadableTask(taskId, currentUser)
                validateReadTarget(taskId, request.lastReadMessageId)
                val previous = chatRepository.readCursor(taskId, currentUser.userId)
                val cursor = chatRepository.raiseReadCursor(
                    taskId, currentUser.userId, request.lastReadMessageId
                )
                auditService.log(
                    currentUser.userId,
                    "CHAT_READ",
                    "TASK",
                    taskId,
                    mapOf("lastReadMessageId" to cursor)
                )
                val eventId = if (cursor > previous) {
                    chatRepository.appendEvent(taskId, "READ", null, currentUser.userId)
                } else null
                cursor to eventId
            })
            if (eventId != null) {
                chatEventBus.publishEvent(
                    ChatEventBus.Event(
                        eventId = eventId,
                        category = EVENT_CHAT_READ,
                        type = "READ",
                        taskId = taskId,
                        actorUserId = currentUser.userId,
                        lastReadMessageId = effective
                    )
                )
            }
            effective
        }
    }

    suspend fun addReaction(
        taskId: Long,
        messageId: Long,
        emoji: String,
        currentUser: CurrentUser
    ): List<ReactionSummaryDto> = changeReaction(taskId, messageId, emoji, currentUser, add = true)

    suspend fun removeReaction(
        taskId: Long,
        messageId: Long,
        emoji: String,
        currentUser: CurrentUser
    ): List<ReactionSummaryDto> = changeReaction(taskId, messageId, emoji, currentUser, add = false)

    private suspend fun changeReaction(
        taskId: Long,
        messageId: Long,
        emoji: String,
        currentUser: CurrentUser,
        add: Boolean
    ): List<ReactionSummaryDto> {
        requireWritableTask(taskId, currentUser)
        val cleanEmoji = emoji.trim()
        if (cleanEmoji.isEmpty() || cleanEmoji.length > 16) {
            throw ValidationException("Invalid emoji", mapOf("emoji" to "invalid"))
        }
        messageInTask(taskId, messageId)
        return orderedTaskEvent(taskId) {
            val (reactions, eventId) = dsl.transactionResult(org.jooq.TransactionalCallable {
                chatRepository.lockTask(taskId) ?: throw NotFoundException("task", taskId)
                requireWritableTask(taskId, currentUser)
                messageInTask(taskId, messageId)
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
                val current = chatRepository.reactionsForMessages(
                    listOf(messageId), currentUser.userId
                )[messageId] ?: emptyList()
                current to chatRepository.appendEvent(taskId, "REACTION", messageId, currentUser.userId)
            })
            chatEventBus.publishEvent(
                ChatEventBus.Event(
                    eventId = eventId,
                    category = EVENT_CHAT_REACTION,
                    type = "REACTION",
                    taskId = taskId,
                    messageId = messageId,
                    actorUserId = currentUser.userId
                )
            )
            reactions
        }
    }

    suspend fun setDialogPinned(taskId: Long, pinned: Boolean, currentUser: CurrentUser): Boolean {
        requireWritableTask(taskId, currentUser)
        return orderedTaskEvent(taskId) {
            val eventId = dsl.transactionResult(org.jooq.TransactionalCallable {
                chatRepository.lockTask(taskId) ?: throw NotFoundException("task", taskId)
                requireWritableTask(taskId, currentUser)
                val changed = if (pinned) {
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
                if (changed) {
                    chatRepository.appendEvent(taskId, "PIN", null, currentUser.userId, isPinned = pinned)
                } else null
            })
            if (eventId != null) {
                chatEventBus.publishEvent(
                    ChatEventBus.Event(
                        eventId = eventId,
                        category = EVENT_CHAT_PIN,
                        type = "PIN",
                        taskId = taskId,
                        actorUserId = currentUser.userId,
                        isPinned = pinned
                    )
                )
            }
            pinned
        }
    }

    suspend fun editMessage(
        taskId: Long,
        messageId: Long,
        request: EditMessageRequest,
        currentUser: CurrentUser
    ): ChatMessageDto {
        requireWritableTask(taskId, currentUser)
        val content = request.content.trim()
        if (content.isEmpty()) {
            throw ValidationException("Message content is required", mapOf("content" to "required"))
        }
        val expectedVersion = request.version
            ?: throw ValidationException("Message version is required", mapOf("version" to "required"))
        return orderedTaskEvent(taskId) {
            val (updated, eventId) = dsl.transactionResult(org.jooq.TransactionalCallable {
                chatRepository.lockTask(taskId) ?: throw NotFoundException("task", taskId)
                requireWritableTask(taskId, currentUser)
                val message = messageInTask(taskId, messageId)
                requireUserMessage(message)
                if (message.authorId != currentUser.userId) {
                    throw ForbiddenException("Only message author can edit it")
                }
                if (message.revokedAt != null) throw ConflictException("Revoked message cannot be edited")
                if (message.version != expectedVersion) throw ConflictException("Message was modified concurrently")
                chatRepository.insertRevision(messageId, message.content, currentUser.userId)
                if (!chatRepository.updateMessageContent(messageId, expectedVersion, content)) {
                    throw ConflictException("Message was modified concurrently")
                }
                auditService.log(
                    currentUser.userId,
                    "CHAT_MESSAGE_EDIT",
                    "TASK",
                    taskId,
                    mapOf("messageId" to messageId, "version" to expectedVersion)
                )
                val dto = buildMessageDtos(
                    listOf(chatRepository.findMessageRowById(messageId)!!), currentUser.userId
                ).single()
                dto to chatRepository.appendEvent(taskId, "EDITED", messageId, currentUser.userId)
            })
            chatEventBus.publish(updated, eventId, "EDITED")
            updated
        }
    }

    suspend fun revokeMessage(
        taskId: Long,
        messageId: Long,
        request: RevokeMessageRequest,
        currentUser: CurrentUser
    ): ChatMessageDto {
        requireWritableTask(taskId, currentUser)
        val expectedVersion = request.version
            ?: throw ValidationException("Message version is required", mapOf("version" to "required"))
        return orderedTaskEvent(taskId) {
            val (revoked, eventId) = dsl.transactionResult(org.jooq.TransactionalCallable {
                chatRepository.lockTask(taskId) ?: throw NotFoundException("task", taskId)
                requireWritableTask(taskId, currentUser)
                val message = messageInTask(taskId, messageId)
                requireUserMessage(message)
                if (message.authorId != currentUser.userId) {
                    throw ForbiddenException("Only message author can revoke it")
                }
                if (message.revokedAt != null) throw ConflictException("Message is already revoked")
                if (message.version != expectedVersion) throw ConflictException("Message was modified concurrently")
                chatRepository.insertRevision(messageId, message.content, currentUser.userId)
                if (!chatRepository.revokeMessage(messageId, expectedVersion)) {
                    throw ConflictException("Message was modified concurrently")
                }
                auditService.log(
                    currentUser.userId,
                    "CHAT_MESSAGE_REVOKE",
                    "TASK",
                    taskId,
                    mapOf("messageId" to messageId, "version" to expectedVersion)
                )
                val dto = buildMessageDtos(
                    listOf(chatRepository.findMessageRowById(messageId)!!), currentUser.userId
                ).single()
                dto to chatRepository.appendEvent(taskId, "REVOKED", messageId, currentUser.userId)
            })
            chatEventBus.publish(revoked, eventId, "REVOKED")
            revoked
        }
    }

    suspend fun forwardMessage(
        sourceTaskId: Long,
        messageId: Long,
        request: ForwardMessageRequest,
        currentUser: CurrentUser
    ): ChatMessageDto {
        if (request.targetKind != ConversationKind.TASK) {
            throw ValidationException("Only task targets are supported", mapOf("targetKind" to "unsupported"))
        }
        if (request.idempotencyKey.isBlank()) {
            throw ValidationException("Idempotency key is required", mapOf("idempotencyKey" to "required"))
        }
        requireReadableTask(sourceTaskId, currentUser)
        requireForwardableMessage(messageInTask(sourceTaskId, messageId))
        requireWritableTask(request.targetId, currentUser)
        return orderedTaskEvent(request.targetId) {
            val result = try {
                dsl.transactionResult(org.jooq.TransactionalCallable {
                    listOf(sourceTaskId, request.targetId).distinct().sorted().forEach { taskId ->
                        chatRepository.lockTask(taskId) ?: throw NotFoundException("task", taskId)
                    }
                    requireReadableTask(sourceTaskId, currentUser)
                    requireWritableTask(request.targetId, currentUser)
                    val source = messageInTask(sourceTaskId, messageId)
                    requireForwardableMessage(source)
                    val existing = chatRepository.findMessageByIdempotencyKey(
                        request.targetId, currentUser.userId, request.idempotencyKey
                    )
                    if (existing != null) {
                        return@TransactionalCallable SendOutcome(
                            buildMessageDtos(listOf(existing), currentUser.userId).single(),
                            created = false,
                            notifications = emptyList()
                        )
                    }
                    val record = chatRepository.insertMessage(
                        taskId = request.targetId,
                        authorId = currentUser.userId,
                        type = ChatMessageType.USER,
                        content = source.content,
                        idempotencyKey = request.idempotencyKey,
                        source = source
                    )
                    val notifications = notificationService.createForNewMessage(
                        request.targetId, record.id!!, currentUser.userId
                    )
                    auditService.log(
                        currentUser.userId,
                        "CHAT_MESSAGE_FORWARD",
                        "TASK",
                        sourceTaskId,
                        mapOf(
                            "messageId" to messageId,
                            "sourceTaskId" to sourceTaskId,
                            "targetTaskId" to request.targetId,
                            "forwardedMessageId" to record.id!!,
                            "idempotencyKey" to request.idempotencyKey
                        )
                    )
                    val eventId = chatRepository.appendEvent(
                        request.targetId, "MESSAGE", record.id!!, currentUser.userId
                    )
                    SendOutcome(
                        buildMessageDtos(
                            listOf(chatRepository.findMessageRowById(record.id!!)!!), currentUser.userId
                        ).single(),
                        created = true,
                        notifications = notifications,
                        eventId = eventId
                    )
                })
            } catch (e: DataAccessException) {
                if (e.sqlState() != "23505") throw e
                SendOutcome(
                    chatRepository.findMessageByIdempotencyKey(
                        request.targetId, currentUser.userId, request.idempotencyKey
                    )?.let { buildMessageDtos(listOf(it), currentUser.userId).single() } ?: throw e,
                    created = false,
                    notifications = emptyList()
                )
            }
            if (result.created) {
                chatEventBus.publish(result.message, result.eventId)
                notificationEventBus.publishAll(result.notifications)
            }
            result.message
        }
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
        val notifications: List<NotificationEventBus.Event>,
        val eventId: Long = 0
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
        val readerNames = chatRepository.userNames(cursors.map { it.first })
        val replyIds = rows.mapNotNull { it.replyToId }.distinct()
        val replyMap = chatRepository.listMessageRowsByIds(replyIds).associateBy { it.id }
        val sourceAuthorNames = chatRepository.userNames(rows.mapNotNull { it.sourceAuthorId }.distinct())
        return rows.map { row ->
            ChatMessageDto(
                id = row.id,
                taskId = row.taskId,
                author = row.authorId?.let { ChatAuthorDto(it, row.authorFullName ?: "") },
                type = ChatMessageType.valueOf(row.type),
                content = if (row.revokedAt == null) row.content else ChatRepository.REVOKED_CONTENT,
                createdAt = row.createdAt.toString(),
                attachments = attachmentMap[row.id] ?: emptyList(),
                replyTo = row.replyToId?.let { replyMap[it] }?.let { reply ->
                    ReplyPreviewDto(
                        id = reply.id,
                        author = reply.authorId?.let { ChatAuthorDto(it, reply.authorFullName ?: "") },
                        contentPreview = (if (reply.revokedAt == null) reply.content else ChatRepository.REVOKED_CONTENT)
                            .take(120)
                    )
                },
                reactions = reactionsMap[row.id] ?: emptyList(),
                mentionedUserIds = mentionsMap[row.id] ?: emptyList(),
                readByCount = cursors.count { it.second >= row.id },
                readBy = cursors.filter { it.second >= row.id }.map { (userId, _) ->
                    ChatAuthorDto(userId, readerNames[userId].orEmpty())
                },
                editedAt = row.editedAt?.toString(),
                revokedAt = row.revokedAt?.toString(),
                source = row.sourceMessageId?.let { sourceId ->
                    MessageSourceDto(
                        kind = ConversationKind.valueOf(row.sourceKind!!),
                        messageId = sourceId,
                        author = row.sourceAuthorId?.let {
                            ChatAuthorDto(it, sourceAuthorNames[it] ?: "")
                        },
                        createdAt = row.sourceCreatedAt?.toString()
                    )
                },
                version = row.version
            )
        }
    }

    fun messageForEvent(taskId: Long, messageId: Long, viewerId: Long): ChatMessageDto? =
        chatRepository.findMessageRowById(messageId)
            ?.takeIf { it.taskId == taskId }
            ?.let { buildMessageDtos(listOf(it), viewerId).single() }

    fun reactionsForEvent(messageId: Long, viewerId: Long): List<ReactionSummaryDto> =
        chatRepository.reactionsForMessages(listOf(messageId), viewerId)[messageId] ?: emptyList()

    fun checkTyping(taskId: Long, currentUser: CurrentUser) {
        requireWritableTask(taskId, currentUser)
    }

    private fun requireReadableTask(taskId: Long, currentUser: CurrentUser) {
        chatRepository.findTaskById(taskId) ?: throw NotFoundException("task", taskId)
        if (!chatRepository.canRead(taskId, currentUser.userId, currentUser.hasPermission("tasks", "read_any"))) {
            throw ForbiddenException("Access to task chat denied")
        }
    }

    private fun requireWritableTask(taskId: Long, currentUser: CurrentUser) {
        chatRepository.findTaskById(taskId) ?: throw NotFoundException("task", taskId)
        if (!chatRepository.canWrite(taskId, currentUser.userId, currentUser.hasPermission("tasks", "write_any"))) {
            throw ForbiddenException("Write access to task chat denied")
        }
        if (chatRepository.isTaskClosed(taskId)) throw ForbiddenException("Task is closed")
    }

    private fun messageInTask(taskId: Long, messageId: Long): ChatRepository.MessageRow {
        val message = chatRepository.findMessageRowById(messageId)
        if (message == null || message.taskId != taskId) throw NotFoundException("message", messageId)
        return message
    }

    private fun requireUserMessage(message: ChatRepository.MessageRow) {
        if (message.type != ChatMessageType.USER.name) {
            throw ForbiddenException("Only user messages support this operation")
        }
    }

    private fun requireForwardableMessage(message: ChatRepository.MessageRow) {
        requireUserMessage(message)
        if (message.revokedAt != null) throw ConflictException("Revoked message cannot be forwarded")
    }

    private fun validateReplyTarget(taskId: Long, replyToMessageId: Long?) {
        if (replyToMessageId == null) return
        val target = chatRepository.findMessageRowById(replyToMessageId)
        if (target == null || target.taskId != taskId) {
            throw ValidationException("Reply target not found in this task", mapOf("replyToMessageId" to "invalid"))
        }
    }

    private fun validateReadTarget(taskId: Long, lastReadMessageId: Long) {
        if (lastReadMessageId == 0L) return
        val target = chatRepository.findMessageRowById(lastReadMessageId)
        if (target == null || target.taskId != taskId) {
            throw ValidationException("Message not found in this task", mapOf("lastReadMessageId" to "invalid"))
        }
    }

    private suspend fun <T> orderedTaskEvent(taskId: Long, block: suspend () -> T): T {
        val stripe = ((taskId xor (taskId ushr 32)) % EVENT_LOCK_STRIPES).toInt()
        return taskEventMutexes[stripe].withLock { block() }
    }

    private fun attachmentsFor(messageIds: List<Long>): Map<Long, List<AttachmentDto>> =
        attachmentService?.listForMessageAttachments("TASK_CHAT_MESSAGE", messageIds) ?: emptyMap()

    private companion object {
        const val EVENT_LOCK_STRIPES = 64
        const val EVENT_CHAT_REACTION = "TASK_CHAT_REACTION"
        const val EVENT_CHAT_READ = "TASK_CHAT_READ"
        const val EVENT_CHAT_PIN = "TASK_CHAT_PIN"
        val LOGIN_MENTION = Regex("@([A-Za-z0-9_.-]+)")
    }
}
