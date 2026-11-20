package org.example.project.repository

import org.example.project.generated.jooq.tables.Notifications.NOTIFICATIONS
import org.example.project.generated.jooq.tables.TaskChatMessages.TASK_CHAT_MESSAGES
import org.example.project.generated.jooq.tables.TaskParticipants.TASK_PARTICIPANTS
import org.example.project.generated.jooq.tables.records.NotificationsRecord
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.OffsetDateTime

class NotificationRepository(private val dsl: DSLContext) {

    data class NotificationRow(
        val id: Long,
        val userId: Long,
        val type: String,
        val entityType: String,
        val entityId: Long,
        val taskId: Long?,
        val title: String,
        val isRead: Boolean,
        val createdAt: OffsetDateTime
    )

    fun insertForMessage(taskId: Long, messageId: Long, authorId: Long): List<NotificationRow> {
        dsl.insertInto(
            NOTIFICATIONS,
            NOTIFICATIONS.USER_ID,
            NOTIFICATIONS.TYPE,
            NOTIFICATIONS.ENTITY_TYPE,
            NOTIFICATIONS.ENTITY_ID,
            NOTIFICATIONS.TITLE
        )
            .select(
                dsl.selectDistinct(
                    TASK_PARTICIPANTS.USER_ID,
                    DSL.inline("NEW_TASK_MESSAGE"),
                    DSL.inline("TASK_CHAT_MESSAGE"),
                    DSL.inline(messageId),
                    DSL.inline("Новое сообщение в задаче")
                )
                    .from(TASK_PARTICIPANTS)
                    .where(TASK_PARTICIPANTS.TASK_ID.eq(taskId))
                    .and(TASK_PARTICIPANTS.USER_ID.ne(authorId))
            )
            .execute()
        return dsl.select(
            NOTIFICATIONS.ID,
            NOTIFICATIONS.USER_ID,
            NOTIFICATIONS.TYPE,
            NOTIFICATIONS.ENTITY_TYPE,
            NOTIFICATIONS.ENTITY_ID,
            TASK_CHAT_MESSAGES.TASK_ID,
            NOTIFICATIONS.TITLE,
            NOTIFICATIONS.IS_READ,
            NOTIFICATIONS.CREATED_AT
        )
            .from(NOTIFICATIONS)
            .join(TASK_CHAT_MESSAGES).on(
                TASK_CHAT_MESSAGES.ID.eq(messageId)
                    .and(NOTIFICATIONS.ENTITY_ID.eq(TASK_CHAT_MESSAGES.ID))
            )
            .where(NOTIFICATIONS.ENTITY_TYPE.eq("TASK_CHAT_MESSAGE"))
            .and(NOTIFICATIONS.ENTITY_ID.eq(messageId))
            .orderBy(NOTIFICATIONS.ID.asc())
            .fetch { mapNotificationRow(it) }
    }

    fun insertForConversation(conversationId: Long, recipientIds: List<Long>): List<NotificationRow> {
        if (recipientIds.isEmpty()) return emptyList()
        val insert = dsl.insertInto(
            NOTIFICATIONS,
            NOTIFICATIONS.USER_ID,
            NOTIFICATIONS.TYPE,
            NOTIFICATIONS.ENTITY_TYPE,
            NOTIFICATIONS.ENTITY_ID,
            NOTIFICATIONS.TITLE
        )
        recipientIds.forEach { recipientId ->
            insert.values(
                recipientId,
                "NEW_CONVERSATION_MESSAGE",
                "CONVERSATION",
                conversationId,
                "Новое сообщение в чате"
            )
        }
        val insertedIds = insert.returning(NOTIFICATIONS.ID).fetch(NOTIFICATIONS.ID).filterNotNull()
        return dsl.select(
            NOTIFICATIONS.ID,
            NOTIFICATIONS.USER_ID,
            NOTIFICATIONS.TYPE,
            NOTIFICATIONS.ENTITY_TYPE,
            NOTIFICATIONS.ENTITY_ID,
            TASK_CHAT_MESSAGES.TASK_ID,
            NOTIFICATIONS.TITLE,
            NOTIFICATIONS.IS_READ,
            NOTIFICATIONS.CREATED_AT
        )
            .from(NOTIFICATIONS)
            .leftJoin(TASK_CHAT_MESSAGES).on(
                NOTIFICATIONS.ENTITY_ID.eq(TASK_CHAT_MESSAGES.ID)
                    .and(NOTIFICATIONS.ENTITY_TYPE.eq("TASK_CHAT_MESSAGE"))
            )
            .where(NOTIFICATIONS.ID.`in`(insertedIds))
            .orderBy(NOTIFICATIONS.ID.asc())
            .fetch { mapNotificationRow(it) }
    }

    fun listForUser(userId: Long, after: Long?, before: Long?, limit: Int): List<NotificationRow> {
        val effectiveLimit = limit + 1
        val base = dsl.select(
            NOTIFICATIONS.ID,
            NOTIFICATIONS.USER_ID,
            NOTIFICATIONS.TYPE,
            NOTIFICATIONS.ENTITY_TYPE,
            NOTIFICATIONS.ENTITY_ID,
            TASK_CHAT_MESSAGES.TASK_ID,
            NOTIFICATIONS.TITLE,
            NOTIFICATIONS.IS_READ,
            NOTIFICATIONS.CREATED_AT
        )
            .from(NOTIFICATIONS)
            .leftJoin(TASK_CHAT_MESSAGES).on(
                NOTIFICATIONS.ENTITY_ID.eq(TASK_CHAT_MESSAGES.ID)
                    .and(NOTIFICATIONS.ENTITY_TYPE.eq("TASK_CHAT_MESSAGE"))
            )
            .where(NOTIFICATIONS.USER_ID.eq(userId))

        return if (before != null) {
            base.and(NOTIFICATIONS.ID.lt(before))
                .orderBy(NOTIFICATIONS.ID.desc())
                .limit(effectiveLimit)
                .fetch { mapNotificationRow(it) }
        } else {
            val afterId = after ?: 0L
            base.and(NOTIFICATIONS.ID.gt(afterId))
                .orderBy(NOTIFICATIONS.ID.asc())
                .limit(effectiveLimit)
                .fetch { mapNotificationRow(it) }
        }
    }

    private fun mapNotificationRow(record: Record): NotificationRow = NotificationRow(
        id = record.get(NOTIFICATIONS.ID)!!,
        userId = record.get(NOTIFICATIONS.USER_ID)!!,
        type = record.get(NOTIFICATIONS.TYPE)!!,
        entityType = record.get(NOTIFICATIONS.ENTITY_TYPE)!!,
        entityId = record.get(NOTIFICATIONS.ENTITY_ID)!!,
        taskId = record.get(TASK_CHAT_MESSAGES.TASK_ID),
        title = record.get(NOTIFICATIONS.TITLE)!!,
        isRead = record.get(NOTIFICATIONS.IS_READ)!!,
        createdAt = record.get(NOTIFICATIONS.CREATED_AT)!!
    )

    fun findById(id: Long): NotificationsRecord? =
        dsl.selectFrom(NOTIFICATIONS).where(NOTIFICATIONS.ID.eq(id)).fetchOne()

    fun findByIdAndUserId(id: Long, userId: Long): NotificationsRecord? =
        dsl.selectFrom(NOTIFICATIONS)
            .where(NOTIFICATIONS.ID.eq(id))
            .and(NOTIFICATIONS.USER_ID.eq(userId))
            .fetchOne()

    fun findTaskIdByMessageId(messageId: Long): Long? =
        dsl.select(TASK_CHAT_MESSAGES.TASK_ID)
            .from(TASK_CHAT_MESSAGES)
            .where(TASK_CHAT_MESSAGES.ID.eq(messageId))
            .fetchOne(TASK_CHAT_MESSAGES.TASK_ID)

    fun markRead(id: Long, userId: Long): Boolean {
        val updated = dsl.update(NOTIFICATIONS)
            .set(NOTIFICATIONS.IS_READ, true)
            .where(NOTIFICATIONS.ID.eq(id))
            .and(NOTIFICATIONS.USER_ID.eq(userId))
            .execute()
        return updated > 0
    }
}
