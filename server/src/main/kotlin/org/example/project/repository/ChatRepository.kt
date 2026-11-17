package org.example.project.repository

import org.example.project.dto.ChatMessageType
import org.example.project.dto.ReactionSummaryDto
import org.example.project.generated.jooq.tables.ProjectMembers.PROJECT_MEMBERS
import org.example.project.generated.jooq.tables.TaskChatDialogPins.TASK_CHAT_DIALOG_PINS
import org.example.project.generated.jooq.tables.TaskChatMentions.TASK_CHAT_MENTIONS
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
        val replyToId: Long? = null
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
            TASK_CHAT_MESSAGES.REPLY_TO_ID
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
        replyToId = record.get(TASK_CHAT_MESSAGES.REPLY_TO_ID)
    )

    fun insertMessage(
        taskId: Long,
        authorId: Long?,
        type: ChatMessageType,
        content: String,
        idempotencyKey: String? = null,
        replyToId: Long? = null
    ): TaskChatMessagesRecord {
        val record = dsl.newRecord(TASK_CHAT_MESSAGES)
        record.taskId = taskId
        record.authorId = authorId
        record.type = type.name
        record.content = content
        record.idempotencyKey = idempotencyKey
        record.replyToId = replyToId
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
            TASK_CHAT_MESSAGES.REPLY_TO_ID
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
            TASK_CHAT_MESSAGES.REPLY_TO_ID
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
            TASK_CHAT_MESSAGES.REPLY_TO_ID
        )
            .from(TASK_CHAT_MESSAGES)
            .leftJoin(USERS).on(USERS.ID.eq(TASK_CHAT_MESSAGES.AUTHOR_ID))
            .where(TASK_CHAT_MESSAGES.TASK_ID.eq(taskId))
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
        val my = DSL.boolOr(DSL.`when`(TASK_CHAT_REACTIONS.USER_ID.eq(viewerId), DSL.inline(true)))
        return dsl.select(
            TASK_CHAT_REACTIONS.MESSAGE_ID,
            TASK_CHAT_REACTIONS.EMOJI,
            DSL.count(),
            my
        )
            .from(TASK_CHAT_REACTIONS)
            .where(TASK_CHAT_REACTIONS.MESSAGE_ID.`in`(messageIds))
            .groupBy(TASK_CHAT_REACTIONS.MESSAGE_ID, TASK_CHAT_REACTIONS.EMOJI)
            .orderBy(TASK_CHAT_REACTIONS.MESSAGE_ID.asc(), TASK_CHAT_REACTIONS.EMOJI.asc())
            .fetch { record ->
                val messageId = record.get(TASK_CHAT_REACTIONS.MESSAGE_ID)!!
                val summary = ReactionSummaryDto(
                    emoji = record.get(TASK_CHAT_REACTIONS.EMOJI)!!,
                    count = record.get(DSL.count()),
                    reactedByMe = record.get(my) == true
                )
                messageId to summary
            }
            .groupBy({ it.first }, { it.second })
    }

    fun isDialogPinned(taskId: Long, userId: Long): Boolean =
        dsl.fetchExists(
            dsl.selectOne()
                .from(TASK_CHAT_DIALOG_PINS)
                .where(TASK_CHAT_DIALOG_PINS.TASK_ID.eq(taskId))
                .and(TASK_CHAT_DIALOG_PINS.USER_ID.eq(userId))
        )

    fun pinDialog(taskId: Long, userId: Long) {
        dsl.insertInto(TASK_CHAT_DIALOG_PINS)
            .set(TASK_CHAT_DIALOG_PINS.TASK_ID, taskId)
            .set(TASK_CHAT_DIALOG_PINS.USER_ID, userId)
            .onConflictDoNothing()
            .execute()
    }

    fun unpinDialog(taskId: Long, userId: Long) {
        dsl.deleteFrom(TASK_CHAT_DIALOG_PINS)
            .where(TASK_CHAT_DIALOG_PINS.TASK_ID.eq(taskId))
            .and(TASK_CHAT_DIALOG_PINS.USER_ID.eq(userId))
            .execute()
    }

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
            TASK_CHAT_MESSAGES.REPLY_TO_ID
        )
            .from(TASK_CHAT_MESSAGES)
            .leftJoin(USERS).on(USERS.ID.eq(TASK_CHAT_MESSAGES.AUTHOR_ID))
            .where(TASK_CHAT_MESSAGES.TASK_ID.eq(taskId))
            .and(TASK_CHAT_MESSAGES.AUTHOR_ID.eq(authorId))
            .and(TASK_CHAT_MESSAGES.IDEMPOTENCY_KEY.eq(key))
            .fetchOne { mapMessageRow(it) }
    }
}
