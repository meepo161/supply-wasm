package org.example.project.repository

import org.example.project.dto.ChatAuthorDto
import org.example.project.dto.ChatMessageType
import org.example.project.dto.ReactionSummaryDto
import org.example.project.generated.jooq.tables.ProjectMembers.PROJECT_MEMBERS
import org.example.project.generated.jooq.tables.TaskChatDialogPins.TASK_CHAT_DIALOG_PINS
import org.example.project.generated.jooq.tables.TaskChatEventLog.TASK_CHAT_EVENT_LOG
import org.example.project.generated.jooq.tables.TaskChatMentions.TASK_CHAT_MENTIONS
import org.example.project.generated.jooq.tables.TaskChatMessageRevisions.TASK_CHAT_MESSAGE_REVISIONS
import org.example.project.generated.jooq.tables.TaskChatMessages.TASK_CHAT_MESSAGES
import org.example.project.generated.jooq.tables.TaskChatReactions.TASK_CHAT_REACTIONS
import org.example.project.generated.jooq.tables.TaskChatReadStates.TASK_CHAT_READ_STATES
import org.example.project.generated.jooq.tables.TaskParticipants.TASK_PARTICIPANTS
import org.example.project.generated.jooq.tables.Tasks.TASKS
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.generated.jooq.tables.records.TaskChatMessagesRecord
import org.example.project.generated.jooq.tables.records.TasksRecord
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.OffsetDateTime

class ChatRepository(private val dsl: DSLContext) {

    data class MessageRow(
        val id: Long,
        val taskId: Long,
        val authorId: Long?,
        val authorFullName: String?,
        val type: String,
        val content: String,
        val createdAt: OffsetDateTime,
        val replyToId: Long? = null,
        val editedAt: OffsetDateTime? = null,
        val revokedAt: OffsetDateTime? = null,
        val sourceKind: String? = null,
        val sourceMessageId: Long? = null,
        val sourceAuthorId: Long? = null,
        val sourceCreatedAt: OffsetDateTime? = null,
        val version: Long = 0
    )

    data class EventRow(
        val id: Long,
        val taskId: Long,
        val category: String,
        val messageId: Long?,
        val actorUserId: Long,
        val isPinned: Boolean?,
        val createdAt: OffsetDateTime
    )

    data class TaskSummaryRow(
        val taskId: Long,
        val number: String,
        val title: String,
        val status: String,
        val priority: String,
        val assigneeId: Long?
    )

    data class MentionCandidate(
        val userId: Long,
        val login: String,
        val fullName: String
    )

    fun findTaskById(taskId: Long): TasksRecord? =
        dsl.selectFrom(TASKS).where(TASKS.ID.eq(taskId)).fetchOne()

    fun lockTask(taskId: Long): TasksRecord? =
        dsl.selectFrom(TASKS).where(TASKS.ID.eq(taskId)).forUpdate().fetchOne()

    fun isTaskClosed(taskId: Long): Boolean {
        val status = dsl.select(TASKS.STATUS)
            .from(TASKS)
            .where(TASKS.ID.eq(taskId))
            .fetchOne(TASKS.STATUS)
        return status == "CLOSED" || status == "CANCELLED"
    }

    fun canRead(taskId: Long, userId: Long, hasReadAny: Boolean): Boolean {
        if (hasReadAny) return true
        return isTaskParticipant(taskId, userId)
            || isDirectTaskOwner(taskId, userId)
            || isDepartmentTaskMember(taskId, userId)
            || isProjectTaskMember(taskId, userId)
    }

    fun canWrite(taskId: Long, userId: Long, hasWriteAny: Boolean): Boolean {
        if (hasWriteAny) return true
        return hasWriterRole(taskId, userId) || isDirectTaskOwner(taskId, userId)
    }

    private fun hasWriterRole(taskId: Long, userId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(TASK_PARTICIPANTS)
                .where(TASK_PARTICIPANTS.TASK_ID.eq(taskId))
                .and(TASK_PARTICIPANTS.USER_ID.eq(userId))
                .and(TASK_PARTICIPANTS.ROLE.`in`("ASSIGNEE", "COEXECUTOR", "INITIATOR", "REVIEWER"))
        )

    private fun isDirectTaskOwner(taskId: Long, userId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(TASKS)
                .where(TASKS.ID.eq(taskId))
                .and(
                    TASKS.INITIATOR_ID.eq(userId)
                        .or(TASKS.ASSIGNEE_ID.eq(userId))
                        .or(TASKS.REVIEWER_ID.eq(userId))
                )
        )

    private fun isTaskParticipant(taskId: Long, userId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(TASK_PARTICIPANTS)
                .where(TASK_PARTICIPANTS.TASK_ID.eq(taskId))
                .and(TASK_PARTICIPANTS.USER_ID.eq(userId))
        )

    private fun isDepartmentTaskMember(taskId: Long, userId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(TASKS)
                .join(USERS).on(USERS.DEPARTMENT_ID.eq(TASKS.DEPARTMENT_ID))
                .where(TASKS.ID.eq(taskId))
                .and(TASKS.AREA.eq("DEPARTMENT"))
                .and(USERS.ID.eq(userId))
        )

    private fun isProjectTaskMember(taskId: Long, userId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(TASKS)
                .join(PROJECT_MEMBERS).on(PROJECT_MEMBERS.PROJECT_ID.eq(TASKS.PROJECT_ID))
                .where(TASKS.ID.eq(taskId))
                .and(TASKS.AREA.eq("PROJECT"))
                .and(PROJECT_MEMBERS.USER_ID.eq(userId))
        )

    fun listMessages(taskId: Long, after: Long?, before: Long?, limit: Int): List<MessageRow> {
        val effectiveLimit = limit + 1
        val base = dsl.select(
            TASK_CHAT_MESSAGES.ID,
            TASK_CHAT_MESSAGES.TASK_ID,
            TASK_CHAT_MESSAGES.AUTHOR_ID,
            USERS.FULL_NAME,
            TASK_CHAT_MESSAGES.TYPE,
            TASK_CHAT_MESSAGES.CONTENT,
            TASK_CHAT_MESSAGES.CREATED_AT,
            TASK_CHAT_MESSAGES.REPLY_TO_ID,
            TASK_CHAT_MESSAGES.EDITED_AT,
            TASK_CHAT_MESSAGES.REVOKED_AT,
            TASK_CHAT_MESSAGES.SOURCE_KIND,
            TASK_CHAT_MESSAGES.SOURCE_MESSAGE_ID,
            TASK_CHAT_MESSAGES.SOURCE_AUTHOR_ID,
            TASK_CHAT_MESSAGES.SOURCE_CREATED_AT,
            TASK_CHAT_MESSAGES.VERSION
        )
            .from(TASK_CHAT_MESSAGES)
            .leftJoin(USERS).on(USERS.ID.eq(TASK_CHAT_MESSAGES.AUTHOR_ID))
            .where(TASK_CHAT_MESSAGES.TASK_ID.eq(taskId))

        return if (before != null) {
            base.and(TASK_CHAT_MESSAGES.ID.lt(before))
                .orderBy(TASK_CHAT_MESSAGES.ID.desc())
                .limit(effectiveLimit)
                .fetch { mapMessageRow(it) }
        } else {
            val afterId = after ?: 0L
            base.and(TASK_CHAT_MESSAGES.ID.gt(afterId))
                .orderBy(TASK_CHAT_MESSAGES.ID.asc())
                .limit(effectiveLimit)
                .fetch { mapMessageRow(it) }
        }
    }

    private fun mapMessageRow(record: Record): MessageRow = MessageRow(
        id = record.get(TASK_CHAT_MESSAGES.ID)!!,
        taskId = record.get(TASK_CHAT_MESSAGES.TASK_ID)!!,
        authorId = record.get(TASK_CHAT_MESSAGES.AUTHOR_ID),
        authorFullName = record.get(USERS.FULL_NAME),
        type = record.get(TASK_CHAT_MESSAGES.TYPE)!!,
        content = record.get(TASK_CHAT_MESSAGES.CONTENT)!!,
        createdAt = record.get(TASK_CHAT_MESSAGES.CREATED_AT)!!,
        replyToId = record.get(TASK_CHAT_MESSAGES.REPLY_TO_ID),
        editedAt = record.get(TASK_CHAT_MESSAGES.EDITED_AT),
        revokedAt = record.get(TASK_CHAT_MESSAGES.REVOKED_AT),
        sourceKind = record.get(TASK_CHAT_MESSAGES.SOURCE_KIND),
        sourceMessageId = record.get(TASK_CHAT_MESSAGES.SOURCE_MESSAGE_ID),
        sourceAuthorId = record.get(TASK_CHAT_MESSAGES.SOURCE_AUTHOR_ID),
        sourceCreatedAt = record.get(TASK_CHAT_MESSAGES.SOURCE_CREATED_AT),
        version = record.get(TASK_CHAT_MESSAGES.VERSION)!!
    )

    fun insertMessage(
        taskId: Long,
        authorId: Long?,
        type: ChatMessageType,
        content: String,
        idempotencyKey: String? = null,
        replyToId: Long? = null,
        source: MessageRow? = null
    ): TaskChatMessagesRecord {
        val record = dsl.newRecord(TASK_CHAT_MESSAGES)
        record.taskId = taskId
        record.authorId = authorId
        record.type = type.name
        record.content = content
        record.idempotencyKey = idempotencyKey
        record.replyToId = replyToId
        record.sourceKind = source?.let { "TASK" }
        record.sourceMessageId = source?.id
        record.sourceAuthorId = source?.authorId
        record.sourceCreatedAt = source?.createdAt
        record.store()
        return record
    }

    fun findMessageRowById(messageId: Long): MessageRow? {
        return dsl.select(
            TASK_CHAT_MESSAGES.ID,
            TASK_CHAT_MESSAGES.TASK_ID,
            TASK_CHAT_MESSAGES.AUTHOR_ID,
            USERS.FULL_NAME,
            TASK_CHAT_MESSAGES.TYPE,
            TASK_CHAT_MESSAGES.CONTENT,
            TASK_CHAT_MESSAGES.CREATED_AT,
            TASK_CHAT_MESSAGES.REPLY_TO_ID,
            TASK_CHAT_MESSAGES.EDITED_AT,
            TASK_CHAT_MESSAGES.REVOKED_AT,
            TASK_CHAT_MESSAGES.SOURCE_KIND,
            TASK_CHAT_MESSAGES.SOURCE_MESSAGE_ID,
            TASK_CHAT_MESSAGES.SOURCE_AUTHOR_ID,
            TASK_CHAT_MESSAGES.SOURCE_CREATED_AT,
            TASK_CHAT_MESSAGES.VERSION
        )
            .from(TASK_CHAT_MESSAGES)
            .leftJoin(USERS).on(USERS.ID.eq(TASK_CHAT_MESSAGES.AUTHOR_ID))
            .where(TASK_CHAT_MESSAGES.ID.eq(messageId))
            .fetchOne { mapMessageRow(it) }
    }

    fun listMessageRowsByIds(messageIds: Collection<Long>): List<MessageRow> {
        if (messageIds.isEmpty()) return emptyList()
        return dsl.select(
            TASK_CHAT_MESSAGES.ID,
            TASK_CHAT_MESSAGES.TASK_ID,
            TASK_CHAT_MESSAGES.AUTHOR_ID,
            USERS.FULL_NAME,
            TASK_CHAT_MESSAGES.TYPE,
            TASK_CHAT_MESSAGES.CONTENT,
            TASK_CHAT_MESSAGES.CREATED_AT,
            TASK_CHAT_MESSAGES.REPLY_TO_ID,
            TASK_CHAT_MESSAGES.EDITED_AT,
            TASK_CHAT_MESSAGES.REVOKED_AT,
            TASK_CHAT_MESSAGES.SOURCE_KIND,
            TASK_CHAT_MESSAGES.SOURCE_MESSAGE_ID,
            TASK_CHAT_MESSAGES.SOURCE_AUTHOR_ID,
            TASK_CHAT_MESSAGES.SOURCE_CREATED_AT,
            TASK_CHAT_MESSAGES.VERSION
        )
            .from(TASK_CHAT_MESSAGES)
            .leftJoin(USERS).on(USERS.ID.eq(TASK_CHAT_MESSAGES.AUTHOR_ID))
            .where(TASK_CHAT_MESSAGES.ID.`in`(messageIds))
            .fetch { mapMessageRow(it) }
    }

    private fun escapeLike(query: String): String =
        query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    fun searchMessages(taskId: Long, query: String, limit: Int): List<MessageRow> {
        val escaped = escapeLike(query)
        return dsl.select(
            TASK_CHAT_MESSAGES.ID,
            TASK_CHAT_MESSAGES.TASK_ID,
            TASK_CHAT_MESSAGES.AUTHOR_ID,
            USERS.FULL_NAME,
            TASK_CHAT_MESSAGES.TYPE,
            TASK_CHAT_MESSAGES.CONTENT,
            TASK_CHAT_MESSAGES.CREATED_AT,
            TASK_CHAT_MESSAGES.REPLY_TO_ID,
            TASK_CHAT_MESSAGES.EDITED_AT,
            TASK_CHAT_MESSAGES.REVOKED_AT,
            TASK_CHAT_MESSAGES.SOURCE_KIND,
            TASK_CHAT_MESSAGES.SOURCE_MESSAGE_ID,
            TASK_CHAT_MESSAGES.SOURCE_AUTHOR_ID,
            TASK_CHAT_MESSAGES.SOURCE_CREATED_AT,
            TASK_CHAT_MESSAGES.VERSION
        )
            .from(TASK_CHAT_MESSAGES)
            .leftJoin(USERS).on(USERS.ID.eq(TASK_CHAT_MESSAGES.AUTHOR_ID))
            .where(TASK_CHAT_MESSAGES.TASK_ID.eq(taskId))
            .and(TASK_CHAT_MESSAGES.REVOKED_AT.isNull)
            .and(TASK_CHAT_MESSAGES.CONTENT.like("%" + escaped + "%", '\\'))
            .orderBy(TASK_CHAT_MESSAGES.ID.desc())
            .limit(limit)
            .fetch { mapMessageRow(it) }
    }

    fun readCursorsForTasks(taskIds: Collection<Long>, userId: Long): Map<Long, Long> {
        if (taskIds.isEmpty()) return emptyMap()
        return dsl.select(TASK_CHAT_READ_STATES.TASK_ID, TASK_CHAT_READ_STATES.LAST_READ_MESSAGE_ID)
            .from(TASK_CHAT_READ_STATES)
            .where(TASK_CHAT_READ_STATES.USER_ID.eq(userId))
            .and(TASK_CHAT_READ_STATES.TASK_ID.`in`(taskIds))
            .fetch { it.get(TASK_CHAT_READ_STATES.TASK_ID)!! to it.get(TASK_CHAT_READ_STATES.LAST_READ_MESSAGE_ID)!! }
            .toMap()
    }

    fun readCursor(taskId: Long, userId: Long): Long =
        dsl.select(TASK_CHAT_READ_STATES.LAST_READ_MESSAGE_ID)
            .from(TASK_CHAT_READ_STATES)
            .where(TASK_CHAT_READ_STATES.TASK_ID.eq(taskId))
            .and(TASK_CHAT_READ_STATES.USER_ID.eq(userId))
            .fetchOne(TASK_CHAT_READ_STATES.LAST_READ_MESSAGE_ID) ?: 0L

    fun raiseReadCursor(taskId: Long, userId: Long, lastReadMessageId: Long): Long {
        dsl.insertInto(TASK_CHAT_READ_STATES)
            .set(TASK_CHAT_READ_STATES.TASK_ID, taskId)
            .set(TASK_CHAT_READ_STATES.USER_ID, userId)
            .set(TASK_CHAT_READ_STATES.LAST_READ_MESSAGE_ID, lastReadMessageId)
            .onConflict(TASK_CHAT_READ_STATES.TASK_ID, TASK_CHAT_READ_STATES.USER_ID)
            .doUpdate()
            .set(
                TASK_CHAT_READ_STATES.LAST_READ_MESSAGE_ID,
                DSL.greatest(TASK_CHAT_READ_STATES.LAST_READ_MESSAGE_ID, DSL.`value`(lastReadMessageId))
            )
            .set(TASK_CHAT_READ_STATES.LAST_READ_AT, OffsetDateTime.now())
            .execute()
        return readCursor(taskId, userId)
    }

    fun readCursorsForTask(taskId: Long): List<Pair<Long, Long>> =
        dsl.select(TASK_CHAT_READ_STATES.USER_ID, TASK_CHAT_READ_STATES.LAST_READ_MESSAGE_ID)
            .from(TASK_CHAT_READ_STATES)
            .where(TASK_CHAT_READ_STATES.TASK_ID.eq(taskId))
            .fetch { it.get(TASK_CHAT_READ_STATES.USER_ID)!! to it.get(TASK_CHAT_READ_STATES.LAST_READ_MESSAGE_ID)!! }

    fun insertMentions(messageId: Long, userIds: Collection<Long>) {
        userIds.distinct().forEach { userId ->
            dsl.insertInto(TASK_CHAT_MENTIONS)
                .set(TASK_CHAT_MENTIONS.MESSAGE_ID, messageId)
                .set(TASK_CHAT_MENTIONS.MENTIONED_USER_ID, userId)
                .onConflictDoNothing()
                .execute()
        }
    }

    fun mentionedUserIdsForMessages(messageIds: Collection<Long>): Map<Long, List<Long>> {
        if (messageIds.isEmpty()) return emptyMap()
        return dsl.select(TASK_CHAT_MENTIONS.MESSAGE_ID, TASK_CHAT_MENTIONS.MENTIONED_USER_ID)
            .from(TASK_CHAT_MENTIONS)
            .where(TASK_CHAT_MENTIONS.MESSAGE_ID.`in`(messageIds))
            .fetchGroups(
                { it.get(TASK_CHAT_MENTIONS.MESSAGE_ID)!! },
                { it.get(TASK_CHAT_MENTIONS.MENTIONED_USER_ID)!! }
            )
    }

    fun mentionEventsForTasks(taskIds: Collection<Long>, userId: Long): List<Pair<Long, Long>> {
        if (taskIds.isEmpty()) return emptyList()
        return dsl.select(TASK_CHAT_MESSAGES.TASK_ID, TASK_CHAT_MESSAGES.ID)
            .from(TASK_CHAT_MENTIONS)
            .join(TASK_CHAT_MESSAGES).on(TASK_CHAT_MESSAGES.ID.eq(TASK_CHAT_MENTIONS.MESSAGE_ID))
            .where(TASK_CHAT_MENTIONS.MENTIONED_USER_ID.eq(userId))
            .and(TASK_CHAT_MESSAGES.TASK_ID.`in`(taskIds))
            .fetch { it.get(TASK_CHAT_MESSAGES.TASK_ID)!! to it.get(TASK_CHAT_MESSAGES.ID)!! }
    }

    fun addReaction(messageId: Long, userId: Long, emoji: String) {
        dsl.insertInto(TASK_CHAT_REACTIONS)
            .set(TASK_CHAT_REACTIONS.MESSAGE_ID, messageId)
            .set(TASK_CHAT_REACTIONS.USER_ID, userId)
            .set(TASK_CHAT_REACTIONS.EMOJI, emoji)
            .onConflictDoNothing()
            .execute()
    }

    fun removeReaction(messageId: Long, userId: Long, emoji: String) {
        dsl.deleteFrom(TASK_CHAT_REACTIONS)
            .where(TASK_CHAT_REACTIONS.MESSAGE_ID.eq(messageId))
            .and(TASK_CHAT_REACTIONS.USER_ID.eq(userId))
            .and(TASK_CHAT_REACTIONS.EMOJI.eq(emoji))
            .execute()
    }

    fun reactionsForMessages(messageIds: Collection<Long>, viewerId: Long): Map<Long, List<ReactionSummaryDto>> {
        if (messageIds.isEmpty()) return emptyMap()
        val rows = dsl.select(
            TASK_CHAT_REACTIONS.MESSAGE_ID,
            TASK_CHAT_REACTIONS.EMOJI,
            TASK_CHAT_REACTIONS.USER_ID
        )
            .from(TASK_CHAT_REACTIONS)
            .where(TASK_CHAT_REACTIONS.MESSAGE_ID.`in`(messageIds))
            .orderBy(
                TASK_CHAT_REACTIONS.MESSAGE_ID.asc(),
                TASK_CHAT_REACTIONS.EMOJI.asc(),
                TASK_CHAT_REACTIONS.USER_ID.asc()
            )
            .fetch {
                Triple(
                    it.get(TASK_CHAT_REACTIONS.MESSAGE_ID)!!,
                    it.get(TASK_CHAT_REACTIONS.EMOJI)!!,
                    it.get(TASK_CHAT_REACTIONS.USER_ID)!!
                )
            }
        val names = userNames(rows.map { it.third }.distinct())
        return rows.groupBy({ it.first }, { it.second to it.third }).mapValues { (_, reactions) ->
            reactions.groupBy({ it.first }, { it.second }).map { (emoji, userIds) ->
                ReactionSummaryDto(
                    emoji = emoji,
                    count = userIds.size,
                    reactedByMe = viewerId in userIds,
                    reactedBy = userIds.map { ChatAuthorDto(it, names[it].orEmpty()) }
                )
            }
        }
    }

    fun isDialogPinned(taskId: Long, userId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(TASK_CHAT_DIALOG_PINS)
                .where(TASK_CHAT_DIALOG_PINS.TASK_ID.eq(taskId))
                .and(TASK_CHAT_DIALOG_PINS.USER_ID.eq(userId))
        )

    fun pinDialog(taskId: Long, userId: Long): Boolean =
        dsl.insertInto(TASK_CHAT_DIALOG_PINS)
            .set(TASK_CHAT_DIALOG_PINS.TASK_ID, taskId)
            .set(TASK_CHAT_DIALOG_PINS.USER_ID, userId)
            .onConflictDoNothing()
            .execute() == 1

    fun unpinDialog(taskId: Long, userId: Long): Boolean =
        dsl.deleteFrom(TASK_CHAT_DIALOG_PINS)
            .where(TASK_CHAT_DIALOG_PINS.TASK_ID.eq(taskId))
            .and(TASK_CHAT_DIALOG_PINS.USER_ID.eq(userId))
            .execute() == 1

    fun pinnedTaskIds(userId: Long, taskIds: Collection<Long>): Set<Long> {
        if (taskIds.isEmpty()) return emptySet()
        return dsl.select(TASK_CHAT_DIALOG_PINS.TASK_ID)
            .from(TASK_CHAT_DIALOG_PINS)
            .where(TASK_CHAT_DIALOG_PINS.USER_ID.eq(userId))
            .and(TASK_CHAT_DIALOG_PINS.TASK_ID.`in`(taskIds))
            .fetch(TASK_CHAT_DIALOG_PINS.TASK_ID)
            .filterNotNull()
            .toSet()
    }

    fun lastMessageIds(taskIds: Collection<Long>): Map<Long, Long> {
        if (taskIds.isEmpty()) return emptyMap()
        return dsl.select(TASK_CHAT_MESSAGES.TASK_ID, DSL.max(TASK_CHAT_MESSAGES.ID))
            .from(TASK_CHAT_MESSAGES)
            .where(TASK_CHAT_MESSAGES.TASK_ID.`in`(taskIds))
            .groupBy(TASK_CHAT_MESSAGES.TASK_ID)
            .fetch { it.get(TASK_CHAT_MESSAGES.TASK_ID)!! to it.get(DSL.max(TASK_CHAT_MESSAGES.ID))!! }
            .toMap()
    }

    fun unreadMessageRefs(taskIds: Collection<Long>, userId: Long): List<Pair<Long, Long>> {
        if (taskIds.isEmpty()) return emptyList()
        return dsl.select(TASK_CHAT_MESSAGES.TASK_ID, TASK_CHAT_MESSAGES.ID)
            .from(TASK_CHAT_MESSAGES)
            .where(TASK_CHAT_MESSAGES.TASK_ID.`in`(taskIds))
            .and(TASK_CHAT_MESSAGES.AUTHOR_ID.ne(userId))
            .fetch { it.get(TASK_CHAT_MESSAGES.TASK_ID)!! to it.get(TASK_CHAT_MESSAGES.ID)!! }
    }

    fun listVisibleTasks(userId: Long, hasReadAny: Boolean, query: String?): List<TaskSummaryRow> {
        val base = dsl.select(
            TASKS.ID,
            TASKS.NUMBER,
            TASKS.TITLE,
            TASKS.STATUS,
            TASKS.PRIORITY,
            TASKS.ASSIGNEE_ID
        ).from(TASKS)

        var condition: org.jooq.Condition? = null
        if (!hasReadAny) {
            val participant = DSL.exists(
                dsl.selectOne()
                    .from(TASK_PARTICIPANTS)
                    .where(TASK_PARTICIPANTS.TASK_ID.eq(TASKS.ID))
                    .and(TASK_PARTICIPANTS.USER_ID.eq(userId))
            )
            val departmentMember = DSL.exists(
                dsl.selectOne()
                    .from(USERS)
                    .where(USERS.DEPARTMENT_ID.eq(TASKS.DEPARTMENT_ID))
                    .and(USERS.ID.eq(userId))
                    .and(TASKS.AREA.eq("DEPARTMENT"))
            )
            val projectMember = DSL.exists(
                dsl.selectOne()
                    .from(PROJECT_MEMBERS)
                    .where(PROJECT_MEMBERS.PROJECT_ID.eq(TASKS.PROJECT_ID))
                    .and(PROJECT_MEMBERS.USER_ID.eq(userId))
                    .and(TASKS.AREA.eq("PROJECT"))
            )
            condition = participant.or(departmentMember).or(projectMember)
        }

        if (!query.isNullOrBlank()) {
            val escaped = escapeLike(query.trim())
            val queryCondition = TASKS.NUMBER.like("%" + escaped + "%", '\\')
                .or(TASKS.TITLE.like("%" + escaped + "%", '\\'))
            condition = condition?.and(queryCondition) ?: queryCondition
        }
        if (condition != null) {
            base.where(condition)
        }

        return base.orderBy(TASKS.ID.desc()).fetch { record ->
            TaskSummaryRow(
                taskId = record.get(TASKS.ID)!!,
                number = record.get(TASKS.NUMBER)!!,
                title = record.get(TASKS.TITLE)!!,
                status = record.get(TASKS.STATUS)!!,
                priority = record.get(TASKS.PRIORITY)!!,
                assigneeId = record.get(TASKS.ASSIGNEE_ID)
            )
        }
    }

    fun listMentionCandidates(taskId: Long): List<MentionCandidate> {
        val taskUsers = dsl.select(TASK_PARTICIPANTS.USER_ID)
            .from(TASK_PARTICIPANTS)
            .where(TASK_PARTICIPANTS.TASK_ID.eq(taskId))
            .union(dsl.select(TASKS.INITIATOR_ID).from(TASKS).where(TASKS.ID.eq(taskId)))
            .union(dsl.select(TASKS.ASSIGNEE_ID).from(TASKS).where(TASKS.ID.eq(taskId)))
            .union(dsl.select(TASKS.REVIEWER_ID).from(TASKS).where(TASKS.ID.eq(taskId)))
        val otherUsers = USERS.`as`("other_users")
        val departmentUsers = dsl.select(otherUsers.ID)
            .from(otherUsers)
            .join(TASKS).on(TASKS.DEPARTMENT_ID.eq(otherUsers.DEPARTMENT_ID))
            .where(TASKS.ID.eq(taskId))
            .and(TASKS.AREA.eq("DEPARTMENT"))
        val projectUsers = dsl.select(PROJECT_MEMBERS.USER_ID)
            .from(PROJECT_MEMBERS)
            .join(TASKS).on(TASKS.PROJECT_ID.eq(PROJECT_MEMBERS.PROJECT_ID))
            .where(TASKS.ID.eq(taskId))
            .and(TASKS.AREA.eq("PROJECT"))
        return dsl.select(USERS.ID, USERS.LOGIN, USERS.FULL_NAME)
            .from(USERS)
            .where(USERS.ID.`in`(taskUsers.union(departmentUsers).union(projectUsers)))
            .fetch { record ->
                MentionCandidate(
                    userId = record.get(USERS.ID)!!,
                    login = record.get(USERS.LOGIN)!!,
                    fullName = record.get(USERS.FULL_NAME)!!
                )
            }
    }

    fun findMessageByIdempotencyKey(taskId: Long, authorId: Long, key: String): MessageRow? {
        return dsl.select(
            TASK_CHAT_MESSAGES.ID,
            TASK_CHAT_MESSAGES.TASK_ID,
            TASK_CHAT_MESSAGES.AUTHOR_ID,
            USERS.FULL_NAME,
            TASK_CHAT_MESSAGES.TYPE,
            TASK_CHAT_MESSAGES.CONTENT,
            TASK_CHAT_MESSAGES.CREATED_AT,
            TASK_CHAT_MESSAGES.REPLY_TO_ID,
            TASK_CHAT_MESSAGES.EDITED_AT,
            TASK_CHAT_MESSAGES.REVOKED_AT,
            TASK_CHAT_MESSAGES.SOURCE_KIND,
            TASK_CHAT_MESSAGES.SOURCE_MESSAGE_ID,
            TASK_CHAT_MESSAGES.SOURCE_AUTHOR_ID,
            TASK_CHAT_MESSAGES.SOURCE_CREATED_AT,
            TASK_CHAT_MESSAGES.VERSION
        )
            .from(TASK_CHAT_MESSAGES)
            .leftJoin(USERS).on(USERS.ID.eq(TASK_CHAT_MESSAGES.AUTHOR_ID))
            .where(TASK_CHAT_MESSAGES.TASK_ID.eq(taskId))
            .and(TASK_CHAT_MESSAGES.AUTHOR_ID.eq(authorId))
            .and(TASK_CHAT_MESSAGES.IDEMPOTENCY_KEY.eq(key))
            .fetchOne { mapMessageRow(it) }
    }

    fun insertRevision(messageId: Long, previousContent: String, editedBy: Long) {
        dsl.insertInto(TASK_CHAT_MESSAGE_REVISIONS)
            .set(TASK_CHAT_MESSAGE_REVISIONS.MESSAGE_ID, messageId)
            .set(TASK_CHAT_MESSAGE_REVISIONS.PREVIOUS_CONTENT, previousContent)
            .set(TASK_CHAT_MESSAGE_REVISIONS.EDITED_BY, editedBy)
            .execute()
    }

    fun updateMessageContent(messageId: Long, expectedVersion: Long, content: String): Boolean =
        dsl.update(TASK_CHAT_MESSAGES)
            .set(TASK_CHAT_MESSAGES.CONTENT, content)
            .set(TASK_CHAT_MESSAGES.EDITED_AT, OffsetDateTime.now())
            .set(TASK_CHAT_MESSAGES.VERSION, TASK_CHAT_MESSAGES.VERSION.plus(1))
            .where(TASK_CHAT_MESSAGES.ID.eq(messageId))
            .and(TASK_CHAT_MESSAGES.VERSION.eq(expectedVersion))
            .and(TASK_CHAT_MESSAGES.REVOKED_AT.isNull)
            .execute() == 1

    fun revokeMessage(messageId: Long, expectedVersion: Long): Boolean =
        dsl.update(TASK_CHAT_MESSAGES)
            .set(TASK_CHAT_MESSAGES.CONTENT, REVOKED_CONTENT)
            .set(TASK_CHAT_MESSAGES.REVOKED_AT, OffsetDateTime.now())
            .set(TASK_CHAT_MESSAGES.VERSION, TASK_CHAT_MESSAGES.VERSION.plus(1))
            .where(TASK_CHAT_MESSAGES.ID.eq(messageId))
            .and(TASK_CHAT_MESSAGES.VERSION.eq(expectedVersion))
            .and(TASK_CHAT_MESSAGES.REVOKED_AT.isNull)
            .execute() == 1

    fun appendEvent(
        taskId: Long,
        category: String,
        messageId: Long?,
        actorUserId: Long,
        isPinned: Boolean? = null
    ): Long =
        dsl.insertInto(TASK_CHAT_EVENT_LOG)
            .set(TASK_CHAT_EVENT_LOG.TASK_ID, taskId)
            .set(TASK_CHAT_EVENT_LOG.CATEGORY, category)
            .set(TASK_CHAT_EVENT_LOG.MESSAGE_ID, messageId)
            .set(TASK_CHAT_EVENT_LOG.ACTOR_USER_ID, actorUserId)
            .set(TASK_CHAT_EVENT_LOG.IS_PINNED, isPinned)
            .returning(TASK_CHAT_EVENT_LOG.ID)
            .fetchOne()!!.get(TASK_CHAT_EVENT_LOG.ID)!!

    fun listEvents(taskId: Long, afterEvent: Long, limit: Int): List<EventRow> =
        dsl.selectFrom(TASK_CHAT_EVENT_LOG)
            .where(TASK_CHAT_EVENT_LOG.TASK_ID.eq(taskId))
            .and(TASK_CHAT_EVENT_LOG.ID.gt(afterEvent))
            .orderBy(TASK_CHAT_EVENT_LOG.ID.asc())
            .limit(limit)
            .fetch {
                EventRow(
                    id = it.id!!,
                    taskId = it.taskId!!,
                    category = it.category!!,
                    messageId = it.messageId,
                    actorUserId = it.actorUserId!!,
                    isPinned = it.isPinned,
                    createdAt = it.createdAt!!
                )
            }

    fun latestEventId(taskId: Long): Long =
        dsl.select(DSL.max(TASK_CHAT_EVENT_LOG.ID))
            .from(TASK_CHAT_EVENT_LOG)
            .where(TASK_CHAT_EVENT_LOG.TASK_ID.eq(taskId))
            .fetchOne(DSL.max(TASK_CHAT_EVENT_LOG.ID)) ?: 0L

    fun userNames(userIds: Collection<Long>): Map<Long, String> {
        if (userIds.isEmpty()) return emptyMap()
        return dsl.select(USERS.ID, USERS.FULL_NAME)
            .from(USERS)
            .where(USERS.ID.`in`(userIds))
            .fetch { it.get(USERS.ID)!! to it.get(USERS.FULL_NAME)!! }
            .toMap()
    }

    fun listWorkspaceTasks(
        userId: Long,
        hasReadAny: Boolean,
        hasWriteAny: Boolean,
        query: String?,
        unreadOnly: Boolean,
        mentionsOnly: Boolean,
        archivedOnly: Boolean?,
        cursor: WorkspaceCursor?,
        limit: Int,
        requiredId: Long? = null
    ): List<WorkspaceCandidate> {
        val conditions = mutableListOf<String>()
        val parameters = mutableListOf<Any?>(
            hasWriteAny, userId, userId, userId, userId,
            userId, userId, userId, userId, userId
        )
        conditions += """(
            ? OR EXISTS (
                SELECT 1 FROM task_participants access_tp
                WHERE access_tp.task_id = t.id AND access_tp.user_id = ?
            ) OR (t.area = 'DEPARTMENT' AND EXISTS (
                SELECT 1 FROM users access_u WHERE access_u.id = ? AND access_u.department_id = t.department_id
            )) OR (t.area = 'PROJECT' AND EXISTS (
                SELECT 1 FROM project_members access_pm
                WHERE access_pm.project_id = t.project_id AND access_pm.user_id = ?
            ))
        )"""
        parameters.addAll(listOf(hasReadAny, userId, userId, userId))
        if (requiredId != null) {
            conditions += "t.id = ?"
            parameters += requiredId
        }
        when (archivedOnly) {
            true -> conditions += "base.archived"
            false -> conditions += "NOT base.archived"
            null -> Unit
        }
        if (unreadOnly) conditions += "counters.unread_count > 0"
        if (mentionsOnly) conditions += "counters.mention_count > 0"
        if (!query.isNullOrBlank()) {
            conditions += """(
                base.title ILIKE ? ESCAPE '\' OR lm.search_content ILIKE ? ESCAPE '\' OR EXISTS (
                    SELECT 1 FROM users search_u WHERE search_u.full_name ILIKE ? ESCAPE '\' AND (
                        search_u.id IN (t.initiator_id, t.assignee_id, t.reviewer_id)
                        OR EXISTS (
                            SELECT 1 FROM task_participants search_tp
                            WHERE search_tp.task_id = t.id AND search_tp.user_id = search_u.id
                        ) OR (t.area = 'DEPARTMENT' AND search_u.department_id = t.department_id)
                        OR (t.area = 'PROJECT' AND (
                            EXISTS (
                                SELECT 1 FROM project_members search_pm
                                WHERE search_pm.project_id = t.project_id AND search_pm.user_id = search_u.id
                            ) OR EXISTS (
                                SELECT 1 FROM projects search_p
                                WHERE search_p.id = t.project_id AND search_p.owner_id = search_u.id
                            )
                        ))
                    )
                )
            )"""
            val pattern = "%${ConversationRepository.escapeLike(query.trim())}%"
            parameters.addAll(listOf(pattern, pattern, pattern))
        }
        cursor?.seen.orEmpty().filter { it.kind == "TASK" }.forEach { key ->
            conditions += "t.id <> ?"
            parameters += key.id
        }
        parameters += limit + 1
        val sql = """
            WITH base AS (
                SELECT t.id, 'TASK' AS kind, t.number || ' — ' || t.title AS title, t.project_id,
                    NOT (t.status IN ('CLOSED', 'CANCELLED')) AND (
                        ? OR EXISTS (
                            SELECT 1 FROM task_participants write_tp
                            WHERE write_tp.task_id = t.id AND write_tp.user_id = ?
                            AND write_tp.role IN ('ASSIGNEE', 'COEXECUTOR', 'INITIATOR', 'REVIEWER')
                        ) OR ? IN (t.initiator_id, t.assignee_id, t.reviewer_id)
                    ) AS can_write,
                    EXISTS (
                        SELECT 1 FROM task_chat_dialog_pins pin WHERE pin.task_id = t.id AND pin.user_id = ?
                    ) AS pinned,
                    COALESCE(s.muted, FALSE) AS muted, COALESCE(s.archived, FALSE) AS archived,
                    COALESCE(lm.created_at, t.created_at) AS activity
                FROM tasks t
                LEFT JOIN task_chat_dialog_states s ON s.task_id = t.id AND s.user_id = ?
                LEFT JOIN LATERAL (
                    SELECT m.created_at FROM task_chat_messages m WHERE m.task_id = t.id ORDER BY m.id DESC LIMIT 1
                ) lm ON TRUE
            ), counters AS (
                SELECT t.id,
                    (SELECT COUNT(*) FROM task_chat_messages unread_m
                        WHERE unread_m.task_id = t.id AND unread_m.id > COALESCE(rs.last_read_message_id, 0)
                        AND unread_m.author_id IS DISTINCT FROM ?) AS unread_count,
                    (SELECT COUNT(*) FROM task_chat_mentions mention
                        JOIN task_chat_messages mention_m ON mention_m.id = mention.message_id
                        WHERE mention_m.task_id = t.id AND mention.mentioned_user_id = ?
                        AND mention_m.id > COALESCE(rs.last_read_message_id, 0)
                        AND mention_m.author_id IS DISTINCT FROM ?) AS mention_count
                FROM tasks t
                LEFT JOIN task_chat_read_states rs ON rs.task_id = t.id AND rs.user_id = ?
            )
            SELECT base.id, base.kind, base.title, base.project_id, base.can_write,
                base.pinned, base.muted, base.archived, base.activity,
                lm.content, lm.revoked_at, lm.created_at AS message_at,
                lm.author_id, lm.full_name AS author_name, counters.unread_count, counters.mention_count
            FROM base
            JOIN tasks t ON t.id = base.id
            JOIN counters ON counters.id = base.id
            LEFT JOIN LATERAL (
                SELECT m.content, CASE WHEN m.revoked_at IS NULL THEN m.content END AS search_content,
                    m.revoked_at, m.created_at, m.author_id, author_u.full_name
                FROM task_chat_messages m
                LEFT JOIN users author_u ON author_u.id = m.author_id
                WHERE m.task_id = t.id ORDER BY m.id DESC LIMIT 1
            ) lm ON TRUE
            LEFT JOIN task_chat_read_states rs ON rs.task_id = t.id AND rs.user_id = ?
            WHERE ${conditions.joinToString(" AND ")}
            ORDER BY base.pinned DESC, base.activity DESC, t.id DESC
            LIMIT ?
        """.trimIndent()
        return dsl.fetch(sql, *parameters.toTypedArray()).map {
            ConversationRepository.mapWorkspaceCandidate(it, task = true)
        }
    }

    fun workspaceTaskMembers(taskIds: Collection<Long>): Map<Long, List<Pair<Long, String>>> {
        if (taskIds.isEmpty()) return emptyMap()
        val placeholders = taskIds.joinToString(",") { "?" }
        val parameters = taskIds.toTypedArray<Any?>()
        return dsl.fetch(
            """
            SELECT source.task_id, source.user_id, u.full_name
            FROM (
                SELECT tp.task_id, tp.user_id FROM task_participants tp WHERE tp.task_id IN ($placeholders)
                UNION
                SELECT t.id, direct.user_id FROM tasks t
                CROSS JOIN LATERAL (VALUES (t.initiator_id), (t.assignee_id), (t.reviewer_id)) direct(user_id)
                WHERE t.id IN ($placeholders)
                UNION
                SELECT t.id, department_u.id FROM tasks t
                JOIN users department_u ON department_u.department_id = t.department_id
                WHERE t.area = 'DEPARTMENT' AND t.id IN ($placeholders)
                UNION
                SELECT t.id, pm.user_id FROM tasks t
                JOIN project_members pm ON pm.project_id = t.project_id
                WHERE t.area = 'PROJECT' AND t.id IN ($placeholders)
                UNION
                SELECT t.id, p.owner_id FROM tasks t JOIN projects p ON p.id = t.project_id
                WHERE t.area = 'PROJECT' AND t.id IN ($placeholders)
            ) source
            JOIN users u ON u.id = source.user_id
            ORDER BY source.task_id, source.user_id
            """.trimIndent(),
            *parameters, *parameters, *parameters, *parameters, *parameters
        ).map {
            it.get("task_id", Long::class.java)!! to
                (it.get("user_id", Long::class.java)!! to it.get("full_name", String::class.java)!!)
        }.groupBy({ it.first }, { it.second })
    }

    fun taskDialogState(taskId: Long, userId: Long): DialogState =
        dsl.fetchOne(
            """
            SELECT EXISTS (
                SELECT 1 FROM task_chat_dialog_pins pin WHERE pin.task_id = ? AND pin.user_id = ?
            ) AS pinned, COALESCE(s.muted, FALSE) AS muted, COALESCE(s.archived, FALSE) AS archived
            FROM (VALUES (1)) one
            LEFT JOIN task_chat_dialog_states s ON s.task_id = ? AND s.user_id = ?
            """.trimIndent(),
            taskId, userId, taskId, userId
        )!!.let {
            DialogState(it.get("pinned", Boolean::class.java)!!, it.get("muted", Boolean::class.java)!!,
                it.get("archived", Boolean::class.java)!!)
        }

    fun updateTaskDialogState(taskId: Long, userId: Long, muted: Boolean?, archived: Boolean?) {
        dsl.execute(
            """
            INSERT INTO task_chat_dialog_states (task_id, user_id, muted, archived, updated_at)
            VALUES (?, ?, COALESCE(?, FALSE), COALESCE(?, FALSE), NOW())
            ON CONFLICT (task_id, user_id) DO UPDATE SET
                muted = COALESCE(?, task_chat_dialog_states.muted),
                archived = COALESCE(?, task_chat_dialog_states.archived),
                updated_at = NOW()
            """.trimIndent(),
            taskId, userId, muted, archived, muted, archived
        )
    }

    companion object {
        const val REVOKED_CONTENT = "Сообщение отозвано"
    }
}
