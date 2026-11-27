package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.generated.jooq.tables.ConversationMessages.CONVERSATION_MESSAGES
import org.example.project.generated.jooq.tables.ConversationParticipants.CONVERSATION_PARTICIPANTS
import org.example.project.generated.jooq.tables.ConversationPinnedMessages.CONVERSATION_PINNED_MESSAGES
import org.example.project.generated.jooq.tables.ConversationPollOptions.CONVERSATION_POLL_OPTIONS
import org.example.project.generated.jooq.tables.ConversationPollVotes.CONVERSATION_POLL_VOTES
import org.example.project.generated.jooq.tables.ConversationPolls.CONVERSATION_POLLS
import org.example.project.generated.jooq.tables.Conversations.CONVERSATIONS
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.model.CurrentUser
import org.example.project.realtime.ConversationEventBus
import org.example.project.repository.ConversationRepository
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Расширенные возможности мессенджера уровня Telegram / Битрикс24:
 * Избранное, управление группами/каналами, закреплённые сообщения, опросы,
 * треды (ветки комментариев), отложенные/тихие сообщения, статусы присутствия и ИИ-сводка.
 */
class ConversationMessengerService(
    private val conversationRepository: ConversationRepository,
    private val conversationService: ConversationService,
    private val auditService: AuditService,
    private val dsl: DSLContext,
    private val conversationEventBus: ConversationEventBus
) {
    fun getOrCreateSavedMessages(currentUser: CurrentUser): ConversationDto {
        val directKey = "saved:${currentUser.userId}"
        val existing = conversationRepository.findByDirectKey(directKey)
        if (existing != null) {
            return conversationService.get(existing.id, currentUser)
        }
        val id = dsl.transactionResult(org.jooq.TransactionalCallable {
            val createdId = dsl.insertInto(CONVERSATIONS)
                .set(CONVERSATIONS.KIND, "DIRECT")
                .set(CONVERSATIONS.TITLE, "Избранное")
                .set(CONVERSATIONS.DIRECT_KEY, directKey)
                .set(CONVERSATIONS.IS_SAVED_MESSAGES, true)
                .set(CONVERSATIONS.CREATED_BY, currentUser.userId)
                .returning(CONVERSATIONS.ID)
                .fetchOne()!!.get(CONVERSATIONS.ID)!!
            dsl.insertInto(CONVERSATION_PARTICIPANTS)
                .set(CONVERSATION_PARTICIPANTS.CONVERSATION_ID, createdId)
                .set(CONVERSATION_PARTICIPANTS.USER_ID, currentUser.userId)
                .set(CONVERSATION_PARTICIPANTS.ROLE, "ADMIN")
                .onConflictDoNothing()
                .execute()
            auditService.log(
                currentUser.userId,
                "CONVERSATION_SAVED_CREATE",
                "CONVERSATION",
                createdId,
                emptyMap()
            )
            createdId
        })
        return conversationService.get(id, currentUser)
    }

    fun updateConversation(
        conversationId: Long,
        request: UpdateConversationRequest,
        currentUser: CurrentUser
    ): ConversationDto {
        val row = requireAccessible(conversationId, currentUser)
        if (row.kind != "GROUP" && row.kind != "CHANNEL") {
            throw ValidationException("Only groups and channels can be updated", mapOf("kind" to "unsupported"))
        }
        requireGroupOrChannelManage(conversationId, row, currentUser)
        val title = request.title?.trim()
        if (title != null && title.isEmpty()) {
            throw ValidationException("Title cannot be blank", mapOf("title" to "required"))
        }
        val desc = request.description?.trim()?.take(1000)
        if (title != null) {
            dsl.update(CONVERSATIONS)
                .set(CONVERSATIONS.TITLE, title)
                .where(CONVERSATIONS.ID.eq(conversationId))
                .execute()
        }
        if (desc != null) {
            dsl.update(CONVERSATIONS)
                .set(CONVERSATIONS.DESCRIPTION, desc)
                .where(CONVERSATIONS.ID.eq(conversationId))
                .execute()
        }
        return conversationService.get(conversationId, currentUser)
    }

    fun updateMembers(
        conversationId: Long,
        request: UpdateConversationMembersRequest,
        currentUser: CurrentUser
    ): ConversationDto {
        val row = requireAccessible(conversationId, currentUser)
        if (row.kind != "GROUP" && row.kind != "CHANNEL") {
            throw ValidationException("Members can only be managed in groups and channels", mapOf("kind" to "unsupported"))
        }
        requireGroupOrChannelManage(conversationId, row, currentUser)
        val toAdd = request.addMemberIds.distinct().filter { conversationRepository.isActiveUser(it) }
        val toRemove = request.removeMemberIds.distinct().filter { it != currentUser.userId }
        dsl.transaction { cfg ->
            val tx = DSL.using(cfg)
            toAdd.forEach { uid ->
                tx.insertInto(CONVERSATION_PARTICIPANTS)
                    .set(CONVERSATION_PARTICIPANTS.CONVERSATION_ID, conversationId)
                    .set(CONVERSATION_PARTICIPANTS.USER_ID, uid)
                    .set(CONVERSATION_PARTICIPANTS.ROLE, "MEMBER")
                    .onConflictDoNothing()
                    .execute()
            }
            if (toRemove.isNotEmpty()) {
                tx.deleteFrom(CONVERSATION_PARTICIPANTS)
                    .where(CONVERSATION_PARTICIPANTS.CONVERSATION_ID.eq(conversationId))
                    .and(CONVERSATION_PARTICIPANTS.USER_ID.`in`(toRemove))
                    .execute()
            }
        }
        return conversationService.get(conversationId, currentUser)
    }

    fun leaveConversation(conversationId: Long, currentUser: CurrentUser) {
        val row = requireAccessible(conversationId, currentUser)
        if (row.kind != "GROUP" && row.kind != "CHANNEL") {
            throw ValidationException("You can only leave groups or channels", mapOf("kind" to "unsupported"))
        }
        dsl.deleteFrom(CONVERSATION_PARTICIPANTS)
            .where(CONVERSATION_PARTICIPANTS.CONVERSATION_ID.eq(conversationId))
            .and(CONVERSATION_PARTICIPANTS.USER_ID.eq(currentUser.userId))
            .execute()
    }

    suspend fun pinMessage(conversationId: Long, messageId: Long, currentUser: CurrentUser): List<PinnedMessageDto> {
        val row = requireAccessible(conversationId, currentUser)
        requireCanWrite(row, currentUser)
        val msg = conversationRepository.findMessageRowById(messageId)
            ?.takeIf { it.conversationId == conversationId && it.revokedAt == null }
            ?: throw NotFoundException("message", messageId)
        dsl.insertInto(CONVERSATION_PINNED_MESSAGES)
            .set(CONVERSATION_PINNED_MESSAGES.CONVERSATION_ID, conversationId)
            .set(CONVERSATION_PINNED_MESSAGES.MESSAGE_ID, msg.id)
            .set(CONVERSATION_PINNED_MESSAGES.PINNED_BY, currentUser.userId)
            .onConflictDoNothing()
            .execute()
        val eventId = conversationRepository.appendEvent(conversationId, "EDITED", msg.id, currentUser.userId)
        conversationEventBus.publishEvent(
            ConversationEventBus.Event(
                eventId = eventId,
                category = "CONVERSATION_MESSAGE",
                type = "EDITED",
                conversationId = conversationId,
                messageId = msg.id,
                actorUserId = currentUser.userId
            )
        )
        return listPinnedMessages(conversationId)
    }

    suspend fun unpinMessage(conversationId: Long, messageId: Long, currentUser: CurrentUser): List<PinnedMessageDto> {
        val row = requireAccessible(conversationId, currentUser)
        requireCanWrite(row, currentUser)
        dsl.deleteFrom(CONVERSATION_PINNED_MESSAGES)
            .where(CONVERSATION_PINNED_MESSAGES.CONVERSATION_ID.eq(conversationId))
            .and(CONVERSATION_PINNED_MESSAGES.MESSAGE_ID.eq(messageId))
            .execute()
        val eventId = conversationRepository.appendEvent(conversationId, "EDITED", messageId, currentUser.userId)
        conversationEventBus.publishEvent(
            ConversationEventBus.Event(
                eventId = eventId,
                category = "CONVERSATION_MESSAGE",
                type = "EDITED",
                conversationId = conversationId,
                messageId = messageId,
                actorUserId = currentUser.userId
            )
        )
        return listPinnedMessages(conversationId)
    }

    fun listPinnedMessages(conversationId: Long): List<PinnedMessageDto> =
        dsl.select(
            CONVERSATION_PINNED_MESSAGES.MESSAGE_ID,
            CONVERSATION_PINNED_MESSAGES.PINNED_AT,
            CONVERSATION_MESSAGES.CONTENT,
            USERS.FULL_NAME
        )
            .from(CONVERSATION_PINNED_MESSAGES)
            .join(CONVERSATION_MESSAGES).on(CONVERSATION_MESSAGES.ID.eq(CONVERSATION_PINNED_MESSAGES.MESSAGE_ID))
            .leftJoin(USERS).on(USERS.ID.eq(CONVERSATION_MESSAGES.AUTHOR_ID))
            .where(CONVERSATION_PINNED_MESSAGES.CONVERSATION_ID.eq(conversationId))
            .and(CONVERSATION_MESSAGES.REVOKED_AT.isNull)
            .orderBy(CONVERSATION_PINNED_MESSAGES.PINNED_AT.desc())
            .fetch {
                PinnedMessageDto(
                    messageId = it.get(CONVERSATION_PINNED_MESSAGES.MESSAGE_ID)!!,
                    contentPreview = (it.get(CONVERSATION_MESSAGES.CONTENT) ?: "").take(140),
                    authorName = it.get(USERS.FULL_NAME),
                    pinnedAt = it.get(CONVERSATION_PINNED_MESSAGES.PINNED_AT)!!.toString()
                )
            }

    suspend fun createPoll(
        conversationId: Long,
        request: CreatePollRequest,
        currentUser: CurrentUser
    ): ConversationMessageDto {
        val row = requireAccessible(conversationId, currentUser)
        requireCanWrite(row, currentUser)
        val question = request.question.trim()
        val options = request.options.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (question.isEmpty()) {
            throw ValidationException("Poll question is required", mapOf("question" to "required"))
        }
        if (options.size < 2 || options.size > 10) {
            throw ValidationException("Poll requires 2 to 10 options", mapOf("options" to "size"))
        }
        val (messageId, eventId) = dsl.transactionResult(org.jooq.TransactionalCallable {
            val msgId = dsl.insertInto(CONVERSATION_MESSAGES)
                .set(CONVERSATION_MESSAGES.CONVERSATION_ID, conversationId)
                .set(CONVERSATION_MESSAGES.AUTHOR_ID, currentUser.userId)
                .set(CONVERSATION_MESSAGES.CONTENT, "Опрос: $question")
                .set(CONVERSATION_MESSAGES.IDEMPOTENCY_KEY, "poll-${UUID.randomUUID()}")
                .set(CONVERSATION_MESSAGES.DELIVERED, true)
                .returning(CONVERSATION_MESSAGES.ID)
                .fetchOne()!!.get(CONVERSATION_MESSAGES.ID)!!
            val pollId = dsl.insertInto(CONVERSATION_POLLS)
                .set(CONVERSATION_POLLS.CONVERSATION_ID, conversationId)
                .set(CONVERSATION_POLLS.MESSAGE_ID, msgId)
                .set(CONVERSATION_POLLS.QUESTION, question)
                .set(CONVERSATION_POLLS.IS_ANONYMOUS, request.isAnonymous)
                .set(CONVERSATION_POLLS.IS_MULTIPLE, request.isMultiple)
                .set(CONVERSATION_POLLS.CREATED_BY, currentUser.userId)
                .returning(CONVERSATION_POLLS.ID)
                .fetchOne()!!.get(CONVERSATION_POLLS.ID)!!
            options.forEachIndexed { idx, label ->
                dsl.insertInto(CONVERSATION_POLL_OPTIONS)
                    .set(CONVERSATION_POLL_OPTIONS.POLL_ID, pollId)
                    .set(CONVERSATION_POLL_OPTIONS.LABEL, label)
                    .set(CONVERSATION_POLL_OPTIONS.SORT_ORDER, idx)
                    .execute()
            }
            val evId = conversationRepository.appendEvent(conversationId, "MESSAGE", msgId, currentUser.userId)
            msgId to evId
        })
        conversationEventBus.publishEvent(
            ConversationEventBus.Event(
                eventId = eventId,
                category = "CONVERSATION_MESSAGE",
                type = "MESSAGE",
                conversationId = conversationId,
                messageId = messageId,
                actorUserId = currentUser.userId
            )
        )
        return conversationService.messageForEvent(conversationId, messageId, currentUser.userId)
            ?: throw NotFoundException("message", messageId)
    }

    suspend fun votePoll(
        conversationId: Long,
        pollId: Long,
        request: VotePollRequest,
        currentUser: CurrentUser
    ): ConversationMessageDto {
        requireAccessible(conversationId, currentUser)
        val poll = dsl.selectFrom(CONVERSATION_POLLS)
            .where(CONVERSATION_POLLS.ID.eq(pollId))
            .and(CONVERSATION_POLLS.CONVERSATION_ID.eq(conversationId))
            .fetchOne() ?: throw NotFoundException("poll", pollId)
        if (poll.closedAt != null) {
            throw ValidationException("Poll is closed", mapOf("poll" to "closed"))
        }
        val validOptions = dsl.select(CONVERSATION_POLL_OPTIONS.ID)
            .from(CONVERSATION_POLL_OPTIONS)
            .where(CONVERSATION_POLL_OPTIONS.POLL_ID.eq(pollId))
            .fetch(CONVERSATION_POLL_OPTIONS.ID)
            .filterNotNull()
            .toSet()
        val chosen = request.optionIds.distinct().filter { it in validOptions }
        if (chosen.isEmpty()) {
            throw ValidationException("Select at least one option", mapOf("optionIds" to "required"))
        }
        val effective = if (poll.isMultiple == true) chosen else listOf(chosen.first())
        dsl.transaction { cfg ->
            val tx = DSL.using(cfg)
            tx.deleteFrom(CONVERSATION_POLL_VOTES)
                .where(CONVERSATION_POLL_VOTES.POLL_ID.eq(pollId))
                .and(CONVERSATION_POLL_VOTES.USER_ID.eq(currentUser.userId))
                .execute()
            effective.forEach { optId ->
                tx.insertInto(CONVERSATION_POLL_VOTES)
                    .set(CONVERSATION_POLL_VOTES.POLL_ID, pollId)
                    .set(CONVERSATION_POLL_VOTES.OPTION_ID, optId)
                    .set(CONVERSATION_POLL_VOTES.USER_ID, currentUser.userId)
                    .onConflictDoNothing()
                    .execute()
            }
        }
        val messageId = poll.messageId!!
        val eventId = conversationRepository.appendEvent(conversationId, "EDITED", messageId, currentUser.userId)
        conversationEventBus.publishEvent(
            ConversationEventBus.Event(
                eventId = eventId,
                category = "CONVERSATION_MESSAGE",
                type = "EDITED",
                conversationId = conversationId,
                messageId = messageId,
                actorUserId = currentUser.userId
            )
        )
        return conversationService.messageForEvent(conversationId, messageId, currentUser.userId)!!
    }

    fun listThreadMessages(conversationId: Long, rootMessageId: Long, currentUser: CurrentUser): List<ConversationMessageDto> {
        requireAccessible(conversationId, currentUser)
        val root = conversationRepository.findMessageRowById(rootMessageId)
            ?.takeIf { it.conversationId == conversationId }
            ?: throw NotFoundException("message", rootMessageId)
        val replies = dsl.select(CONVERSATION_MESSAGES.fields().toList() + USERS.FULL_NAME)
            .from(CONVERSATION_MESSAGES)
            .leftJoin(USERS).on(USERS.ID.eq(CONVERSATION_MESSAGES.AUTHOR_ID))
            .where(CONVERSATION_MESSAGES.CONVERSATION_ID.eq(conversationId))
            .and(CONVERSATION_MESSAGES.THREAD_ROOT_ID.eq(rootMessageId))
            .and(CONVERSATION_MESSAGES.DELIVERED.eq(true))
            .orderBy(CONVERSATION_MESSAGES.ID.asc())
            .fetch { mapThreadRow(it) }
        return conversationService.hydrateMessagesForViewer(listOf(root) + replies, currentUser.userId)
    }

    suspend fun sendThreadReply(
        conversationId: Long,
        rootMessageId: Long,
        request: SendChatMessageRequest,
        currentUser: CurrentUser
    ): ConversationMessageDto {
        requireAccessible(conversationId, currentUser)
        val root = conversationRepository.findMessageRowById(rootMessageId)
            ?.takeIf { it.conversationId == conversationId && it.revokedAt == null }
            ?: throw NotFoundException("message", rootMessageId)
        val content = request.content.trim()
        if (content.isEmpty()) {
            throw ValidationException("Reply content is required", mapOf("content" to "required"))
        }
        val id = dsl.insertInto(CONVERSATION_MESSAGES)
            .set(CONVERSATION_MESSAGES.CONVERSATION_ID, conversationId)
            .set(CONVERSATION_MESSAGES.AUTHOR_ID, currentUser.userId)
            .set(CONVERSATION_MESSAGES.CONTENT, content)
            .set(CONVERSATION_MESSAGES.THREAD_ROOT_ID, root.id)
            .set(CONVERSATION_MESSAGES.IDEMPOTENCY_KEY, request.idempotencyKey.ifBlank { "thr-${UUID.randomUUID()}" })
            .set(CONVERSATION_MESSAGES.DELIVERED, true)
            .returning(CONVERSATION_MESSAGES.ID)
            .fetchOne()!!.get(CONVERSATION_MESSAGES.ID)!!
        val eventId = conversationRepository.appendEvent(conversationId, "EDITED", root.id, currentUser.userId)
        conversationEventBus.publishEvent(
            ConversationEventBus.Event(
                eventId = eventId,
                category = "CONVERSATION_MESSAGE",
                type = "EDITED",
                conversationId = conversationId,
                messageId = root.id,
                actorUserId = currentUser.userId
            )
        )
        return conversationService.messageForEvent(conversationId, id, currentUser.userId)!!
    }

    suspend fun scheduleOrSendSilent(
        conversationId: Long,
        request: ScheduledMessageRequest,
        currentUser: CurrentUser
    ): ConversationMessageDto {
        val row = requireAccessible(conversationId, currentUser)
        requireCanWrite(row, currentUser)
        val content = request.content.trim()
        if (content.isEmpty()) {
            throw ValidationException("Message content is required", mapOf("content" to "required"))
        }
        val scheduledAt = request.scheduledAt?.takeIf { it.isNotBlank() }?.let {
            runCatching { OffsetDateTime.parse(it) }.getOrElse {
                throw ValidationException("Invalid scheduledAt ISO-8601 timestamp", mapOf("scheduledAt" to "invalid"))
            }
        }
        val isImmediate = scheduledAt == null || !scheduledAt.isAfter(OffsetDateTime.now())
        val (msgId, eventId) = dsl.transactionResult(org.jooq.TransactionalCallable {
            val insertedId = dsl.insertInto(CONVERSATION_MESSAGES)
                .set(CONVERSATION_MESSAGES.CONVERSATION_ID, conversationId)
                .set(CONVERSATION_MESSAGES.AUTHOR_ID, currentUser.userId)
                .set(CONVERSATION_MESSAGES.CONTENT, content)
                .set(CONVERSATION_MESSAGES.REPLY_TO_ID, request.replyToMessageId)
                .set(CONVERSATION_MESSAGES.THREAD_ROOT_ID, request.threadRootId)
                .set(CONVERSATION_MESSAGES.IS_SILENT, request.isSilent)
                .set(CONVERSATION_MESSAGES.SCHEDULED_AT, scheduledAt)
                .set(CONVERSATION_MESSAGES.DELIVERED, isImmediate)
                .set(CONVERSATION_MESSAGES.IDEMPOTENCY_KEY, request.idempotencyKey.ifBlank { "sch-${UUID.randomUUID()}" })
                .returning(CONVERSATION_MESSAGES.ID)
                .fetchOne()!!.get(CONVERSATION_MESSAGES.ID)!!
            val evId = if (isImmediate) {
                conversationRepository.appendEvent(conversationId, "MESSAGE", insertedId, currentUser.userId)
            } else 0L
            insertedId to evId
        })
        if (isImmediate) {
            conversationEventBus.publishEvent(
                ConversationEventBus.Event(
                    eventId = eventId,
                    category = "CONVERSATION_MESSAGE",
                    type = "MESSAGE",
                    conversationId = conversationId,
                    messageId = msgId,
                    actorUserId = currentUser.userId
                )
            )
        }
        return conversationService.messageForEvent(conversationId, msgId, currentUser.userId)!!
    }

    fun listScheduledMessages(conversationId: Long, currentUser: CurrentUser): List<ConversationMessageDto> {
        requireAccessible(conversationId, currentUser)
        val rows = dsl.select(CONVERSATION_MESSAGES.fields().toList() + USERS.FULL_NAME)
            .from(CONVERSATION_MESSAGES)
            .leftJoin(USERS).on(USERS.ID.eq(CONVERSATION_MESSAGES.AUTHOR_ID))
            .where(CONVERSATION_MESSAGES.CONVERSATION_ID.eq(conversationId))
            .and(CONVERSATION_MESSAGES.AUTHOR_ID.eq(currentUser.userId))
            .and(CONVERSATION_MESSAGES.DELIVERED.eq(false))
            .and(CONVERSATION_MESSAGES.REVOKED_AT.isNull)
            .orderBy(CONVERSATION_MESSAGES.SCHEDULED_AT.asc())
            .fetch { mapThreadRow(it) }
        return conversationService.hydrateMessagesForViewer(rows, currentUser.userId)
    }

    suspend fun deliverDueScheduledMessages(): Int {
        val now = OffsetDateTime.now()
        val due = dsl.select(CONVERSATION_MESSAGES.ID, CONVERSATION_MESSAGES.CONVERSATION_ID, CONVERSATION_MESSAGES.AUTHOR_ID)
            .from(CONVERSATION_MESSAGES)
            .where(CONVERSATION_MESSAGES.DELIVERED.eq(false))
            .and(CONVERSATION_MESSAGES.SCHEDULED_AT.le(now))
            .and(CONVERSATION_MESSAGES.REVOKED_AT.isNull)
            .limit(50)
            .fetch()
        if (due.isEmpty()) return 0
        var count = 0
        due.forEach { rec ->
            val msgId = rec.get(CONVERSATION_MESSAGES.ID)!!
            val convId = rec.get(CONVERSATION_MESSAGES.CONVERSATION_ID)!!
            val authorId = rec.get(CONVERSATION_MESSAGES.AUTHOR_ID) ?: return@forEach
            val updated = dsl.update(CONVERSATION_MESSAGES)
                .set(CONVERSATION_MESSAGES.DELIVERED, true)
                .set(CONVERSATION_MESSAGES.CREATED_AT, OffsetDateTime.now())
                .where(CONVERSATION_MESSAGES.ID.eq(msgId))
                .and(CONVERSATION_MESSAGES.DELIVERED.eq(false))
                .execute()
            if (updated > 0) {
                count++
                val eventId = conversationRepository.appendEvent(convId, "MESSAGE", msgId, authorId)
                conversationEventBus.publishEvent(
                    ConversationEventBus.Event(
                        eventId = eventId,
                        category = "CONVERSATION_MESSAGE",
                        type = "MESSAGE",
                        conversationId = convId,
                        messageId = msgId,
                        actorUserId = authorId
                    )
                )
            }
        }
        return count
    }

    fun listPresence(): List<UserPresenceDto> =
        dsl.select(USERS.ID, USERS.PRESENCE_STATUS, USERS.LAST_SEEN_AT)
            .from(USERS)
            .where(USERS.ACTIVE.eq(true))
            .fetch {
                val statusStr = it.get(USERS.PRESENCE_STATUS) ?: "ONLINE"
                val status = runCatching { UserPresenceStatus.valueOf(statusStr) }.getOrDefault(UserPresenceStatus.ONLINE)
                UserPresenceDto(
                    userId = it.get(USERS.ID)!!,
                    status = status,
                    lastSeenAt = it.get(USERS.LAST_SEEN_AT)?.toString()
                )
            }

    fun updatePresence(status: UserPresenceStatus, currentUser: CurrentUser): UserPresenceDto {
        val now = OffsetDateTime.now()
        dsl.update(USERS)
            .set(USERS.PRESENCE_STATUS, status.name)
            .set(USERS.LAST_SEEN_AT, now)
            .where(USERS.ID.eq(currentUser.userId))
            .execute()
        return UserPresenceDto(userId = currentUser.userId, status = status, lastSeenAt = now.toString())
    }

    fun summarizeConversation(conversationId: Long, currentUser: CurrentUser): ConversationAiSummaryDto {
        requireAccessible(conversationId, currentUser)
        val rows = dsl.select(CONVERSATION_MESSAGES.CONTENT, USERS.FULL_NAME, CONVERSATION_MESSAGES.CREATED_AT)
            .from(CONVERSATION_MESSAGES)
            .leftJoin(USERS).on(USERS.ID.eq(CONVERSATION_MESSAGES.AUTHOR_ID))
            .where(CONVERSATION_MESSAGES.CONVERSATION_ID.eq(conversationId))
            .and(CONVERSATION_MESSAGES.DELIVERED.eq(true))
            .and(CONVERSATION_MESSAGES.REVOKED_AT.isNull)
            .orderBy(CONVERSATION_MESSAGES.ID.desc())
            .limit(40)
            .fetch()
            .reversed()
        if (rows.isEmpty()) {
            return ConversationAiSummaryDto(
                summary = "В этом обсуждении пока нет сообщений для анализа.",
                actionItems = emptyList(),
                messageCount = 0
            )
        }
        val authors = rows.mapNotNull { it.get(USERS.FULL_NAME) }.distinct()
        val lines = rows.map { rec ->
            val author = rec.get(USERS.FULL_NAME) ?: "Система"
            val text = (rec.get(CONVERSATION_MESSAGES.CONTENT) ?: "").replace('\n', ' ').trim()
            author to text
        }.filter { it.second.isNotBlank() }
        val actionKeywords = listOf("нужно", "сделать", "подготов", "провер", "соглас", "срок", "до ", "оплат", "отгруз", "заказ", "задач")
        val actions = lines
            .filter { (_, text) -> actionKeywords.any { kw -> text.contains(kw, ignoreCase = true) } }
            .takeLast(5)
            .map { (author, text) -> "$author: ${text.take(120)}" }
        val summary = buildString {
            append("Проанализировано сообщений: ${lines.size}. Участники: ${authors.joinToString(", ")}.")
            val lastTopic = lines.lastOrNull()?.second?.take(160)
            if (!lastTopic.isNullOrBlank()) {
                append("\nПоследний контекст: «$lastTopic».")
            }
        }
        return ConversationAiSummaryDto(
            summary = summary,
            actionItems = actions,
            messageCount = lines.size
        )
    }

    private fun requireAccessible(conversationId: Long, currentUser: CurrentUser): ConversationRepository.ConversationRow {
        val row = conversationRepository.findById(conversationId) ?: throw NotFoundException("conversation", conversationId)
        if (!conversationService.canAccess(row, currentUser.userId)) {
            throw ForbiddenException("Access to conversation denied")
        }
        return row
    }

    private fun requireCanWrite(row: ConversationRepository.ConversationRow, currentUser: CurrentUser) {
        if (!conversationService.canWrite(row, currentUser.userId)) {
            throw ForbiddenException("Only channel admins can post here")
        }
    }

    private fun requireGroupOrChannelManage(
        conversationId: Long,
        row: ConversationRepository.ConversationRow,
        currentUser: CurrentUser
    ) {
        val role = dsl.select(CONVERSATION_PARTICIPANTS.ROLE)
            .from(CONVERSATION_PARTICIPANTS)
            .where(CONVERSATION_PARTICIPANTS.CONVERSATION_ID.eq(conversationId))
            .and(CONVERSATION_PARTICIPANTS.USER_ID.eq(currentUser.userId))
            .fetchOne(CONVERSATION_PARTICIPANTS.ROLE)
        val isAdmin = currentUser.hasPermission("users", "manage") || role == "ADMIN" || row.kind == "GROUP"
        if (!isAdmin) {
            throw ForbiddenException("Insufficient permissions to manage this conversation")
        }
    }

    private fun mapThreadRow(record: org.jooq.Record): ConversationRepository.MessageRow =
        ConversationRepository.MessageRow(
            id = record.get(CONVERSATION_MESSAGES.ID)!!,
            conversationId = record.get(CONVERSATION_MESSAGES.CONVERSATION_ID)!!,
            authorId = record.get(CONVERSATION_MESSAGES.AUTHOR_ID),
            authorFullName = record.get(USERS.FULL_NAME),
            content = record.get(CONVERSATION_MESSAGES.CONTENT)!!,
            createdAt = record.get(CONVERSATION_MESSAGES.CREATED_AT)!!,
            replyToId = record.get(CONVERSATION_MESSAGES.REPLY_TO_ID),
            editedAt = record.get(CONVERSATION_MESSAGES.EDITED_AT),
            revokedAt = record.get(CONVERSATION_MESSAGES.REVOKED_AT),
            sourceKind = record.get(CONVERSATION_MESSAGES.SOURCE_KIND),
            sourceMessageId = record.get(CONVERSATION_MESSAGES.SOURCE_MESSAGE_ID),
            sourceAuthorId = record.get(CONVERSATION_MESSAGES.SOURCE_AUTHOR_ID),
            sourceCreatedAt = record.get(CONVERSATION_MESSAGES.SOURCE_CREATED_AT),
            version = record.get(CONVERSATION_MESSAGES.VERSION) ?: 0,
            threadRootId = record.get(CONVERSATION_MESSAGES.THREAD_ROOT_ID),
            isSilent = record.get(CONVERSATION_MESSAGES.IS_SILENT) ?: false,
            scheduledAt = record.get(CONVERSATION_MESSAGES.SCHEDULED_AT),
            delivered = record.get(CONVERSATION_MESSAGES.DELIVERED) ?: true
        )
}
