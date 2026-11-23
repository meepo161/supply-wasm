package org.example.project.service

import org.example.project.dto.NotificationDto
import org.example.project.dto.NotificationPageDto
import org.example.project.exception.NotFoundException
import org.example.project.model.CurrentUser
import org.example.project.realtime.NotificationEventBus
import org.example.project.repository.NotificationRepository

class NotificationService(private val notificationRepository: NotificationRepository) {

    fun createForNewMessage(taskId: Long, messageId: Long, authorId: Long): List<NotificationEventBus.Event> =
        notificationRepository.insertForMessage(taskId, messageId, authorId)
            .map { NotificationEventBus.Event(it.userId, it.toDto()) }

    fun createForNewConversationMessage(
        conversationId: Long,
        recipientIds: List<Long>
    ): List<NotificationEventBus.Event> =
        notificationRepository.insertForConversation(conversationId, recipientIds)
            .map { NotificationEventBus.Event(it.userId, it.toDto()) }

    fun list(after: Long?, before: Long?, limit: Int, currentUser: CurrentUser): NotificationPageDto {
        val effectiveLimit = limit.coerceIn(1, 200)
        val rows = notificationRepository.listForUser(currentUser.userId, after, before, effectiveLimit)
        return toPage(rows, effectiveLimit, after != null, before != null)
    }

    fun markRead(id: Long, currentUser: CurrentUser): NotificationDto {
        val record = notificationRepository.findByIdAndUserId(id, currentUser.userId)
            ?: throw NotFoundException("notification", id)
        if (record.isRead != true) {
            notificationRepository.markRead(id, currentUser.userId)
            record.isRead = true
        }
        return record.toDto()
    }

    fun markAllRead(currentUser: CurrentUser): Int {
        return notificationRepository.markAllRead(currentUser.userId)
    }

    private fun toPage(
        rows: List<NotificationRepository.NotificationRow>,
        limit: Int,
        isAfter: Boolean,
        isBefore: Boolean
    ): NotificationPageDto {
        val hasMore = rows.size > limit
        val notifications = rows.take(limit).map { it.toDto() }
        return when {
            isBefore -> NotificationPageDto(
                notifications = notifications,
                nextBefore = if (hasMore) notifications.last().id else null,
                hasMore = hasMore
            )
            else -> NotificationPageDto(
                notifications = notifications,
                nextAfter = if (hasMore) notifications.last().id else null,
                hasMore = hasMore
            )
        }
    }

    private fun NotificationRepository.NotificationRow.toDto(): NotificationDto = NotificationDto(
        id = id,
        type = type,
        entityType = entityType,
        entityId = entityId,
        title = title,
        isRead = isRead,
        createdAt = createdAt.toString(),
        taskId = taskId
    )

    private fun org.example.project.generated.jooq.tables.records.NotificationsRecord.toDto(): NotificationDto = NotificationDto(
        id = id!!,
        type = type!!,
        entityType = entityType!!,
        entityId = entityId!!,
        title = title!!,
        isRead = isRead ?: false,
        createdAt = createdAt?.toString() ?: "",
        taskId = entityId?.takeIf { entityType == "TASK_CHAT_MESSAGE" }
            ?.let(notificationRepository::findTaskIdByMessageId)
    )
}
