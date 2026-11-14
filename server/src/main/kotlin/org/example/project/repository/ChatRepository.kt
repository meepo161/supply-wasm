package org.example.project.repository

import org.example.project.dto.ChatMessageType
import org.example.project.generated.jooq.tables.ProjectMembers.PROJECT_MEMBERS
import org.example.project.generated.jooq.tables.TaskChatMessages.TASK_CHAT_MESSAGES
import org.example.project.generated.jooq.tables.TaskParticipants.TASK_PARTICIPANTS
import org.example.project.generated.jooq.tables.Tasks.TASKS
import org.example.project.generated.jooq.tables.Users.USERS
import org.example.project.generated.jooq.tables.records.TaskChatMessagesRecord
import org.example.project.generated.jooq.tables.records.TasksRecord
import org.jooq.DSLContext
import org.jooq.Record
import java.time.OffsetDateTime

class ChatRepository(private val dsl: DSLContext) {

    data class MessageRow(
        val id: Long,
        val taskId: Long,
        val authorId: Long?,
        val authorFullName: String?,
        val type: String,
        val content: String,
        val createdAt: OffsetDateTime
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
            TASK_CHAT_MESSAGES.CREATED_AT
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
        createdAt = record.get(TASK_CHAT_MESSAGES.CREATED_AT)!!
    )

    fun insertMessage(
        taskId: Long,
        authorId: Long?,
        type: ChatMessageType,
        content: String,
        idempotencyKey: String? = null
    ): TaskChatMessagesRecord {
        val record = dsl.newRecord(TASK_CHAT_MESSAGES)
        record.taskId = taskId
        record.authorId = authorId
        record.type = type.name
        record.content = content
        record.idempotencyKey = idempotencyKey
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
            TASK_CHAT_MESSAGES.CREATED_AT
        )
            .from(TASK_CHAT_MESSAGES)
            .leftJoin(USERS).on(USERS.ID.eq(TASK_CHAT_MESSAGES.AUTHOR_ID))
            .where(TASK_CHAT_MESSAGES.ID.eq(messageId))
            .fetchOne { mapMessageRow(it) }
    }

    fun findMessageByIdempotencyKey(taskId: Long, authorId: Long, key: String): MessageRow? {
        return dsl.select(
            TASK_CHAT_MESSAGES.ID,
            TASK_CHAT_MESSAGES.TASK_ID,
            TASK_CHAT_MESSAGES.AUTHOR_ID,
            USERS.FULL_NAME,
            TASK_CHAT_MESSAGES.TYPE,
            TASK_CHAT_MESSAGES.CONTENT,
            TASK_CHAT_MESSAGES.CREATED_AT
        )
            .from(TASK_CHAT_MESSAGES)
            .leftJoin(USERS).on(USERS.ID.eq(TASK_CHAT_MESSAGES.AUTHOR_ID))
            .where(TASK_CHAT_MESSAGES.TASK_ID.eq(taskId))
            .and(TASK_CHAT_MESSAGES.AUTHOR_ID.eq(authorId))
            .and(TASK_CHAT_MESSAGES.IDEMPOTENCY_KEY.eq(key))
            .fetchOne { mapMessageRow(it) }
    }
}
