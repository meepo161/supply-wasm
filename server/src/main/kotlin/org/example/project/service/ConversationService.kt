package org.example.project.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.model.CurrentUser
import org.example.project.realtime.ConversationEventBus
import org.example.project.realtime.NotificationEventBus
import org.example.project.repository.ConversationRepository
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException

class ConversationService(
    private val conversationRepository: ConversationRepository,
    private val notificationService: NotificationService,
    private val auditService: AuditService,
    private val dsl: DSLContext,
    private val conversationEventBus: ConversationEventBus = ConversationEventBus(),
    private val notificationEventBus: NotificationEventBus = NotificationEventBus(),
    private val attachmentService: AttachmentService? = null
) {
    private val conversationEventMutexes = Array(EVENT_LOCK_STRIPES) { Mutex() }

    fun list(currentUser: CurrentUser): List<ConversationDto> =
        conversationRepository.listForUser(currentUser.userId).map { toDto(it) }

    fun create(request: CreateConversationRequest, currentUser: CurrentUser): Pair<ConversationDto, Boolean> =
        when (request.kind) {
            ConversationKind.DIRECT -> createDirect(request, currentUser)
            ConversationKind.GROUP -> createGroup(request, currentUser)
            ConversationKind.PROJECT, ConversationKind.TASK -> throw ValidationException(
                "Project and task chats are managed via their own endpoints",
                mapOf("kind" to "unsupported")
            )
        }

    private fun createDirect(request: CreateConversationRequest, currentUser: CurrentUser): Pair<ConversationDto, Boolean> {
        val others = request.memberIds.distinct().filter { it != currentUser.userId }
        if (others.size != 1) {
            throw ValidationException(
                "Direct conversation requires exactly one other member",
                mapOf("memberIds" to "exactly_one_other")
            )
        }
        val otherId = others.single()
        if (!conversationRepository.isActiveUser(otherId)) {
            throw ValidationException("Member not found or inactive", mapOf("memberIds" to "invalid"))
        }
        val directKey = listOf(currentUser.userId, otherId).sorted().joinToString(":")
        conversationRepository.findByDirectKey(directKey)?.let { return toDto(it) to false }
        val row = try {
            dsl.transactionResult(org.jooq.TransactionalCallable {
                val id = conversationRepository.insertConversation(
                    kind = "DIRECT",
                    title = null,
                    projectId = null,
                    directKey = directKey,
                    createdBy = currentUser.userId
                )
                conversationRepository.insertParticipant(id, currentUser.userId)
                conversationRepository.insertParticipant(id, otherId)
                auditService.log(
                    currentUser.userId,
                    "CONVERSATION_CREATE",
                    "CONVERSATION",
                    id,
                    mapOf("kind" to "DIRECT", "memberIds" to listOf(currentUser.userId, otherId))
                )
                conversationRepository.findById(id)!!
            })
        } catch (e: DataAccessException) {
            if (e.sqlState() != "23505") throw e
            return (conversationRepository.findByDirectKey(directKey)?.let { toDto(it) } ?: throw e) to false
        }
        return toDto(row) to true
    }

    private fun createGroup(request: CreateConversationRequest, currentUser: CurrentUser): Pair<ConversationDto, Boolean> {
        val title = request.title?.trim()
        if (title.isNullOrEmpty()) {
            throw ValidationException("Group title is required", mapOf("title" to "required"))
        }
        val others = request.memberIds.distinct().filter { it != currentUser.userId }
        if (others.isEmpty()) {
            throw ValidationException(
                "Group conversation requires at least one other member",
                mapOf("memberIds" to "at_least_one_other")
            )
        }
        if (others.any { !conversationRepository.isActiveUser(it) }) {
            throw ValidationException("Member not found or inactive", mapOf("memberIds" to "invalid"))
        }
        val row = dsl.transactionResult(org.jooq.TransactionalCallable {
            val id = conversationRepository.insertConversation(
                kind = "GROUP",
                title = title,
                projectId = null,
                directKey = null,
                createdBy = currentUser.userId
            )
            conversationRepository.insertParticipant(id, currentUser.userId)
            others.forEach { conversationRepository.insertParticipant(id, it) }
            auditService.log(
                currentUser.userId,
                "CONVERSATION_CREATE",
                "CONVERSATION",
                id,
                mapOf("kind" to "GROUP", "memberIds" to (listOf(currentUser.userId) + others))
            )
            conversationRepository.findById(id)!!
        })
        return toDto(row) to true
    }

    fun get(id: Long, currentUser: CurrentUser): ConversationDto {
        val row = conversationRepository.findById(id) ?: throw NotFoundException("conversation", id)
        requireAccess(row, currentUser)
        return toDto(row)
    }

    fun listMessages(
        id: Long,
        after: Long?,
        before: Long?,
        limit: Int,
        currentUser: CurrentUser
    ): ConversationMessagePageDto {
        val row = conversationRepository.findById(id) ?: throw NotFoundException("conversation", id)
        requireAccess(row, currentUser)
        val effectiveLimit = limit.coerceIn(1, 200)
        val rows = conversationRepository.listMessages(id, after, before, effectiveLimit)
        val hasMore = rows.size > effectiveLimit
        val messages = buildMessageDtos(rows.take(effectiveLimit), currentUser.userId)
        return if (before != null) {
            ConversationMessagePageDto(
                messages = messages,
                nextBefore = if (hasMore) messages.last().id else null,
                hasMore = hasMore
            )
        } else {
            ConversationMessagePageDto(
                messages = messages,
                nextAfter = if (hasMore) messages.last().id else null,
                hasMore = hasMore
            )
        }
    }

    fun searchMessages(
        id: Long,
        query: String?,
        after: Long?,
        limit: Int,
        currentUser: CurrentUser
    ): ConversationMessagePageDto {
        val row = conversationRepository.findById(id) ?: throw NotFoundException("conversation", id)
        requireAccess(row, currentUser)
        val cleanQuery = query?.trim().orEmpty()
        if (cleanQuery.isEmpty()) {
            throw ValidationException("Search query is required", mapOf("q" to "required"))
        }
        val effectiveLimit = limit.coerceIn(1, 100)
        val rows = conversationRepository.searchMessages(id, cleanQuery, after, effectiveLimit)
        val messages = buildMessageDtos(rows.take(effectiveLimit), currentUser.userId)
        return ConversationMessagePageDto(
            messages = messages,
            nextAfter = if (rows.size > effectiveLimit) messages.last().id else null,
            hasMore = rows.size > effectiveLimit
        )
    }

    fun validateMultipartMessage(
        id: Long,
        request: SendChatMessageRequest,
        currentUser: CurrentUser
    ): ConversationMessageDto? {
        val row = conversationRepository.findById(id) ?: throw NotFoundException("conversation", id)
        requireAccess(row, currentUser)
        if (request.idempotencyKey.isBlank()) {
            throw ValidationException("Idempotency key is required", mapOf("idempotencyKey" to "required"))
        }
        return conversationRepository.findMessageByIdempotencyKey(id, currentUser.userId, request.idempotencyKey)
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
        id: Long,
        request: SendChatMessageRequest,
        currentUser: CurrentUser,
        files: List<PreparedMessageFile> = emptyList()
    ): ConversationMessageDto {
        var preparedAttached = false
        try {
            val row = conversationRepository.findById(id) ?: throw NotFoundException("conversation", id)
            requireAccess(row, currentUser)
            if (request.content.isBlank() && files.isEmpty()) {
                throw ValidationException("Message content is required", mapOf("content" to "required"))
            }
            if (request.idempotencyKey.isBlank()) {
                throw ValidationException("Idempotency key is required", mapOf("idempotencyKey" to "required"))
            }
            return orderedConversationEvent(id) {
                accessibleConversation(id, currentUser)
                val result = try {
            dsl.transactionResult(org.jooq.TransactionalCallable {
                val currentRow = accessibleConversation(id, currentUser)
                val existing = conversationRepository.findMessageByIdempotencyKey(
                    id, currentUser.userId, request.idempotencyKey
                )
                if (existing != null) {
                    return@TransactionalCallable SendOutcome(
                        buildMessageDtos(listOf(existing), currentUser.userId).single(),
                        created = false,
                        notifications = emptyList()
                    )
                }
                val replyToMessageId = request.replyToMessageId
                if (replyToMessageId != null) {
                    val reply = conversationRepository.findMessageRowById(replyToMessageId)
                    if (reply == null || reply.conversationId != id) {
                        throw ValidationException(
                            "Reply target not found in this conversation",
                            mapOf("replyToMessageId" to "invalid")
                        )
                    }
                }
                val content = request.content.trim()
                val mentionedUserIds = resolveMentions(currentRow, content, currentUser)
                val record = conversationRepository.insertMessage(
                    conversationId = id,
                    authorId = currentUser.userId,
                    content = content,
                    idempotencyKey = request.idempotencyKey,
                    replyToId = request.replyToMessageId
                )
                conversationRepository.insertMentions(record.id!!, mentionedUserIds)
                files.forEach { prepared ->
                    val attachmentId = attachmentService!!.attachPreparedFile(
                        prepared, "CONVERSATION_MESSAGE", record.id!!, currentUser.userId
                    )
                    auditService.log(
                        currentUser.userId,
                        "ATTACHMENT_UPLOAD",
                        "ATTACHMENT",
                        attachmentId,
                        mapOf(
                            "entityType" to "CONVERSATION_MESSAGE",
                            "entityId" to record.id!!,
                            "conversationId" to id,
                            "fileName" to prepared.fileName,
                            "sizeBytes" to prepared.sizeBytes,
                            "sha256" to (prepared.sha256 ?: "")
                        )
                    )
                }
                val notifications = notificationService.createForNewConversationMessage(
                    id, recipientIds(currentRow, currentUser.userId)
                )
                auditService.log(
                    currentUser.userId,
                    "CONVERSATION_MESSAGE_SEND",
                    "CONVERSATION",
                    id,
                    mapOf("messageId" to record.id!!, "idempotencyKey" to request.idempotencyKey)
                )
                val eventId = conversationRepository.appendEvent(id, "MESSAGE", record.id!!, currentUser.userId)
                val messageRow = conversationRepository.findMessageRowById(record.id!!)!!
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
                conversationRepository.findMessageByIdempotencyKey(id, currentUser.userId, request.idempotencyKey)
                    ?.let { buildMessageDtos(listOf(it), currentUser.userId).single() }
                    ?: throw e,
                created = false,
                notifications = emptyList()
            )
        }
        preparedAttached = result.created && files.isNotEmpty()
        if (result.created) {
            conversationEventBus.publish(result.message, result.eventId)
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

    suspend fun toggleReaction(
        conversationId: Long,
        messageId: Long,
        emoji: String,
        currentUser: CurrentUser
    ): List<ReactionSummaryDto> {
        val conversation = accessibleConversation(conversationId, currentUser)
        val cleanEmoji = emoji.trim()
        if (cleanEmoji !in ALLOWED_EMOJI) {
            throw ValidationException("Invalid emoji", mapOf("emoji" to "not_allowed"))
        }
        val message = messageInConversation(conversationId, messageId)
        if (message.revokedAt != null) throw ConflictException("Revoked message cannot be reacted to")
        return orderedConversationEvent(conversationId) {
            accessibleConversation(conversationId, currentUser)
            val (_, eventId) = dsl.transactionResult(org.jooq.TransactionalCallable {
                accessibleConversation(conversationId, currentUser)
                val currentMessage = messageInConversation(conversationId, messageId)
                if (currentMessage.revokedAt != null) throw ConflictException("Revoked message cannot be reacted to")
                val changed = conversationRepository.toggleReaction(messageId, currentUser.userId, cleanEmoji)
            auditService.log(
                currentUser.userId,
                "CONVERSATION_MESSAGE_REACTION",
                "CONVERSATION_MESSAGE",
                messageId,
                mapOf("conversationId" to conversationId, "emoji" to cleanEmoji, "added" to changed)
            )
            changed to conversationRepository.appendEvent(conversationId, "REACTION", messageId, currentUser.userId)
        })
        val reactions = conversationRepository.reactionsForMessages(listOf(messageId), currentUser.userId)[messageId]
            ?: emptyList()
        conversationEventBus.publishEvent(
            ConversationEventBus.Event(
                eventId = eventId,
                category = EVENT_REACTION,
                type = "REACTION",
                conversationId = conversation.id,
                messageId = messageId,
                actorUserId = currentUser.userId
            )
        )
            reactions
        }
    }

    suspend fun editMessage(
        conversationId: Long,
        messageId: Long,
        request: EditMessageRequest,
        currentUser: CurrentUser
    ): ConversationMessageDto {
        accessibleConversation(conversationId, currentUser)
        val content = request.content.trim()
        if (content.isEmpty()) {
            throw ValidationException("Message content is required", mapOf("content" to "required"))
        }
        val expectedVersion = request.version
            ?: throw ValidationException("Message version is required", mapOf("version" to "required"))
        return orderedConversationEvent(conversationId) {
            accessibleConversation(conversationId, currentUser)
            val (updated, eventId) = dsl.transactionResult(org.jooq.TransactionalCallable {
                accessibleConversation(conversationId, currentUser)
                val message = messageInConversation(conversationId, messageId)
            if (message.authorId != currentUser.userId) throw ForbiddenException("Only message author can edit it")
            if (message.revokedAt != null) throw ConflictException("Revoked message cannot be edited")
            if (expectedVersion != message.version) {
                throw ConflictException("Message was modified concurrently")
            }
            conversationRepository.insertRevision(messageId, message.content, currentUser.userId)
            if (!conversationRepository.updateMessageContent(messageId, message.version, content)) {
                throw ConflictException("Message was modified concurrently")
            }
            auditService.log(
                currentUser.userId,
                "CONVERSATION_MESSAGE_EDIT",
                "CONVERSATION_MESSAGE",
                messageId,
                mapOf("conversationId" to conversationId, "version" to message.version)
            )
            val dto = buildMessageDtos(
                listOf(conversationRepository.findMessageRowById(messageId)!!), currentUser.userId
            ).single()
            dto to conversationRepository.appendEvent(conversationId, "EDITED", messageId, currentUser.userId)
        })
            conversationEventBus.publish(updated, eventId, "EDITED")
            updated
        }
    }

    suspend fun revokeMessage(conversationId: Long, messageId: Long, currentUser: CurrentUser): ConversationMessageDto {
        accessibleConversation(conversationId, currentUser)
        return orderedConversationEvent(conversationId) {
            accessibleConversation(conversationId, currentUser)
            val (revoked, eventId) = dsl.transactionResult(org.jooq.TransactionalCallable {
                accessibleConversation(conversationId, currentUser)
                val message = messageInConversation(conversationId, messageId)
            if (message.authorId != currentUser.userId) throw ForbiddenException("Only message author can revoke it")
            if (message.revokedAt != null) throw ConflictException("Message is already revoked")
            conversationRepository.insertRevision(messageId, message.content, currentUser.userId)
            if (!conversationRepository.revokeMessage(messageId, message.version)) {
                throw ConflictException("Message was modified concurrently")
            }
            auditService.log(
                currentUser.userId,
                "CONVERSATION_MESSAGE_REVOKE",
                "CONVERSATION_MESSAGE",
                messageId,
                mapOf("conversationId" to conversationId, "version" to message.version)
            )
            val dto = buildMessageDtos(
                listOf(conversationRepository.findMessageRowById(messageId)!!), currentUser.userId
            ).single()
            dto to conversationRepository.appendEvent(conversationId, "REVOKED", messageId, currentUser.userId)
        })
            conversationEventBus.publish(revoked, eventId, "REVOKED")
            revoked
        }
    }

    suspend fun forwardMessage(
        sourceConversationId: Long,
        messageId: Long,
        request: ForwardMessageRequest,
        currentUser: CurrentUser
    ): ConversationMessageDto {
        if (request.targetKind == ConversationKind.TASK) {
            throw ValidationException("Task conversations are not supported", mapOf("targetKind" to "unsupported"))
        }
        if (request.idempotencyKey.isBlank()) {
            throw ValidationException("Idempotency key is required", mapOf("idempotencyKey" to "required"))
        }
        val sourceConversation = accessibleConversation(sourceConversationId, currentUser)
        val source = messageInConversation(sourceConversation.id, messageId)
        if (source.revokedAt != null) throw ConflictException("Revoked message cannot be forwarded")
        val target = conversationRepository.findById(request.targetId)
            ?: throw NotFoundException("conversation", request.targetId)
        requireAccess(target, currentUser)
        if (target.kind != request.targetKind.name) {
            throw ValidationException("Target kind does not match conversation", mapOf("targetId" to "invalid"))
        }
        return orderedConversationEvent(target.id) {
            accessibleConversation(sourceConversationId, currentUser)
            accessibleConversation(target.id, currentUser)
            val result = try {
                dsl.transactionResult(org.jooq.TransactionalCallable {
                    accessibleConversation(sourceConversationId, currentUser)
                    val currentTarget = accessibleConversation(target.id, currentUser)
                    val currentSource = messageInConversation(sourceConversationId, messageId)
                    if (currentSource.revokedAt != null) throw ConflictException("Revoked message cannot be forwarded")
                    val existing = conversationRepository.findMessageByIdempotencyKey(
                        target.id, currentUser.userId, request.idempotencyKey
                    )
                if (existing != null) {
                    return@TransactionalCallable SendOutcome(
                        buildMessageDtos(listOf(existing), currentUser.userId).single(), false, emptyList()
                    )
                }
                val record = conversationRepository.insertMessage(
                    target.id,
                    currentUser.userId,
                    currentSource.content,
                    request.idempotencyKey,
                    source = currentSource
                )
                val notifications = notificationService.createForNewConversationMessage(
                    target.id, recipientIds(currentTarget, currentUser.userId)
                )
                auditService.log(
                    currentUser.userId,
                    "CONVERSATION_MESSAGE_FORWARD",
                    "CONVERSATION_MESSAGE",
                    messageId,
                    mapOf(
                        "sourceConversationId" to sourceConversationId,
                        "targetConversationId" to target.id,
                        "forwardedMessageId" to record.id!!,
                        "idempotencyKey" to request.idempotencyKey
                    )
                )
                val eventId = conversationRepository.appendEvent(target.id, "MESSAGE", record.id!!, currentUser.userId)
                SendOutcome(
                    buildMessageDtos(
                        listOf(conversationRepository.findMessageRowById(record.id!!)!!), currentUser.userId
                    ).single(),
                    true,
                    notifications,
                    eventId
                )
            })
        } catch (e: DataAccessException) {
            if (e.sqlState() != "23505") throw e
            SendOutcome(
                conversationRepository.findMessageByIdempotencyKey(target.id, currentUser.userId, request.idempotencyKey)
                    ?.let { buildMessageDtos(listOf(it), currentUser.userId).single() } ?: throw e,
                false,
                emptyList()
            )
        }
        if (result.created) {
            conversationEventBus.publish(result.message, result.eventId)
            notificationEventBus.publishAll(result.notifications)
        }
            result.message
        }
    }

    suspend fun markRead(conversationId: Long, request: MarkChatReadRequest, currentUser: CurrentUser): Long {
        accessibleConversation(conversationId, currentUser)
        if (request.lastReadMessageId < 0) {
            throw ValidationException("Invalid read cursor", mapOf("lastReadMessageId" to "invalid"))
        }
        if (request.lastReadMessageId > 0) {
            val target = conversationRepository.findMessageRowById(request.lastReadMessageId)
            if (target == null || target.conversationId != conversationId) {
                throw ValidationException("Invalid read cursor", mapOf("lastReadMessageId" to "invalid"))
            }
        }
        return orderedConversationEvent(conversationId) {
            accessibleConversation(conversationId, currentUser)
            val (effective, eventId) = dsl.transactionResult(org.jooq.TransactionalCallable {
                accessibleConversation(conversationId, currentUser)
                val cursor = conversationRepository.raiseReadCursor(
                conversationId, currentUser.userId, request.lastReadMessageId
            )
            auditService.log(
                currentUser.userId,
                "CONVERSATION_READ",
                "CONVERSATION",
                conversationId,
                mapOf("lastReadMessageId" to cursor)
            )
            cursor to conversationRepository.appendEvent(conversationId, "READ", null, currentUser.userId)
        })
        conversationEventBus.publishEvent(
            ConversationEventBus.Event(
                eventId = eventId,
                category = EVENT_READ,
                type = "READ",
                conversationId = conversationId,
                actorUserId = currentUser.userId,
                lastReadMessageId = effective
            )
        )
            effective
        }
    }

    fun ensureProjectChat(projectId: Long, currentUser: CurrentUser): ConversationDto {
        if (!conversationRepository.projectExists(projectId)) {
            throw NotFoundException("project", projectId)
        }
        if (!conversationRepository.isProjectMemberOrOwner(projectId, currentUser.userId)) {
            throw ForbiddenException("Access to project chat denied")
        }
        conversationRepository.findByProjectId(projectId)?.let { return toDto(it) }
        val row = try {
            dsl.transactionResult(org.jooq.TransactionalCallable {
                val id = conversationRepository.insertConversation(
                    kind = "PROJECT",
                    title = null,
                    projectId = projectId,
                    directKey = null,
                    createdBy = currentUser.userId
                )
                auditService.log(
                    currentUser.userId,
                    "CONVERSATION_PROJECT_ENSURE",
                    "CONVERSATION",
                    id,
                    mapOf("projectId" to projectId)
                )
                conversationRepository.findById(id)!!
            })
        } catch (e: DataAccessException) {
            if (e.sqlState() != "23505") throw e
            conversationRepository.findByProjectId(projectId) ?: throw e
        }
        return toDto(row)
    }

    fun canAccess(row: ConversationRepository.ConversationRow, userId: Long): Boolean = when (row.kind) {
        "PROJECT" -> row.projectId != null && conversationRepository.isProjectMemberOrOwner(row.projectId, userId)
        "TASK" -> false
        else -> conversationRepository.isParticipant(row.id, userId)
    }

    private fun requireAccess(row: ConversationRepository.ConversationRow, currentUser: CurrentUser) {
        if (!canAccess(row, currentUser.userId)) {
            throw ForbiddenException("Access to conversation denied")
        }
    }

    private fun accessibleConversation(id: Long, currentUser: CurrentUser): ConversationRepository.ConversationRow {
        val row = conversationRepository.findById(id) ?: throw NotFoundException("conversation", id)
        requireAccess(row, currentUser)
        return row
    }

    private fun messageInConversation(conversationId: Long, messageId: Long): ConversationRepository.MessageRow {
        val message = conversationRepository.findMessageRowById(messageId)
        if (message == null || message.conversationId != conversationId) {
            throw NotFoundException("message", messageId)
        }
        return message
    }

    private fun recipientIds(row: ConversationRepository.ConversationRow, authorId: Long): List<Long> = when (row.kind) {
        "PROJECT" -> conversationRepository.listProjectRecipientIds(row.projectId!!, authorId)
        else -> conversationRepository.listParticipantIds(row.id, authorId)
    }

    private fun resolveMentions(
        row: ConversationRepository.ConversationRow,
        content: String,
        currentUser: CurrentUser
    ): List<Long> {
        if (!content.contains('@')) return emptyList()
        val candidates = conversationRepository.listMentionCandidates(row)
        val mentioned = mutableSetOf<Long>()
        for (match in LOGIN_MENTION.findAll(content)) {
            val login = match.groupValues[1]
            candidates.singleOrNull { it.login.equals(login, ignoreCase = true) }?.let { mentioned += it.userId }
        }
        candidates.groupBy { it.fullName }.forEach { (fullName, users) ->
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

    private fun toDto(row: ConversationRepository.ConversationRow): ConversationDto {
        val members = when (row.kind) {
            "PROJECT" -> conversationRepository.listProjectMembersWithOwner(row.projectId!!)
            else -> conversationRepository.listParticipantMembers(row.id)
        }
        return ConversationDto(
            id = row.id,
            kind = ConversationKind.valueOf(row.kind),
            title = row.title,
            projectId = row.projectId,
            members = members.map { ConversationMemberDto(it.first, it.second) }
        )
    }

    private fun buildMessageDtos(
        rows: List<ConversationRepository.MessageRow>,
        viewerId: Long
    ): List<ConversationMessageDto> {
        if (rows.isEmpty()) return emptyList()
        val ids = rows.map { it.id }
        val attachmentMap = attachmentsFor(ids)
        val reactionsMap = conversationRepository.reactionsForMessages(ids, viewerId)
        val mentionsMap = conversationRepository.mentionedUserIdsForMessages(ids)
        val cursors = conversationRepository.readCursors(rows.first().conversationId)
        val readerNames = conversationRepository.userNames(cursors.map { it.first })
        val replyMap = conversationRepository.listMessageRowsByIds(rows.mapNotNull { it.replyToId }.distinct())
            .associateBy { it.id }
        val sourceAuthorNames = conversationRepository.userNames(rows.mapNotNull { it.sourceAuthorId }.distinct())
        return rows.map { row ->
            ConversationMessageDto(
                id = row.id,
                conversationId = row.conversationId,
                author = row.authorId?.let { ChatAuthorDto(it, row.authorFullName ?: "") },
                content = if (row.revokedAt == null) row.content else ConversationRepository.REVOKED_CONTENT,
                createdAt = row.createdAt.toString(),
                attachments = attachmentMap[row.id] ?: emptyList(),
                replyTo = row.replyToId?.let(replyMap::get)?.let { reply ->
                    ReplyPreviewDto(
                        id = reply.id,
                        author = reply.authorId?.let { ChatAuthorDto(it, reply.authorFullName ?: "") },
                        contentPreview = (if (reply.revokedAt == null) reply.content else ConversationRepository.REVOKED_CONTENT)
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

    fun messageForEvent(conversationId: Long, messageId: Long, viewerId: Long): ConversationMessageDto? =
        conversationRepository.findMessageRowById(messageId)
            ?.takeIf { it.conversationId == conversationId }
            ?.let { buildMessageDtos(listOf(it), viewerId).single() }

    fun reactionsForEvent(messageId: Long, viewerId: Long): List<ReactionSummaryDto> =
        conversationRepository.reactionsForMessages(listOf(messageId), viewerId)[messageId] ?: emptyList()

    private fun attachmentsFor(messageIds: List<Long>): Map<Long, List<AttachmentDto>> =
        attachmentService?.listForMessageAttachments("CONVERSATION_MESSAGE", messageIds) ?: emptyMap()

    private suspend fun <T> orderedConversationEvent(conversationId: Long, block: suspend () -> T): T {
        val stripe = ((conversationId xor (conversationId ushr 32)) % EVENT_LOCK_STRIPES).toInt()
        return conversationEventMutexes[stripe].withLock { block() }
    }

    private data class SendOutcome(
        val message: ConversationMessageDto,
        val created: Boolean,
        val notifications: List<NotificationEventBus.Event>,
        val eventId: Long = 0
    )

    private companion object {
        const val EVENT_LOCK_STRIPES = 64
        const val EVENT_REACTION = "CONVERSATION_REACTION"
        const val EVENT_READ = "CONVERSATION_READ"
        val LOGIN_MENTION = Regex("@([A-Za-z0-9_.-]+)")
        val ALLOWED_EMOJI = setOf("👍", "👎", "❤️", "😂", "😮", "😢", "🎉", "✅")
    }
}
