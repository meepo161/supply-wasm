package org.example.project.service

import org.example.project.dto.*
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.model.CurrentUser
import org.example.project.realtime.ChatEventBus
import org.example.project.repository.ChatRepository
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException

class ChatService(
    private val chatRepository: ChatRepository,
    private val notificationService: NotificationService,
    private val auditService: AuditService,
    private val dsl: DSLContext,
    private val chatEventBus: ChatEventBus = ChatEventBus()
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
        return toPage(rows, effectiveLimit, after != null, before != null)
    }

    fun sendMessage(taskId: Long, request: SendChatMessageRequest, currentUser: CurrentUser): ChatMessageDto {
        chatRepository.findTaskById(taskId) ?: throw NotFoundException("task", taskId)
        if (!chatRepository.canWrite(taskId, currentUser.userId, currentUser.hasPermission("tasks", "write_any"))) {
            throw ForbiddenException("Write access to task chat denied")
        }
        if (chatRepository.isTaskClosed(taskId)) {
            throw ForbiddenException("Task is closed")
        }
        if (request.content.isBlank()) {
            throw ValidationException("Message content is required", mapOf("content" to "required"))
        }
        if (request.idempotencyKey.isBlank()) {
            throw ValidationException("Idempotency key is required", mapOf("idempotencyKey" to "required"))
        }

        val result = try {
            dsl.transactionResult(org.jooq.TransactionalCallable {
                val existing = chatRepository.findMessageByIdempotencyKey(taskId, currentUser.userId, request.idempotencyKey)
                if (existing != null) {
                    return@TransactionalCallable (existing.toDto() to false)
                }
                val record = chatRepository.insertMessage(
                    taskId = taskId,
                    authorId = currentUser.userId,
                    type = ChatMessageType.USER,
                    content = request.content.trim(),
                    idempotencyKey = request.idempotencyKey
                )
                notificationService.createForNewMessage(taskId, record.id!!, currentUser.userId)
                auditService.log(
                    currentUser.userId,
                    "CHAT_MESSAGE_SEND",
                    "TASK",
                    taskId,
                    mapOf("messageId" to record.id!!, "idempotencyKey" to request.idempotencyKey)
                )
                (chatRepository.findMessageRowById(record.id!!)!!.toDto() to true)
            })
        } catch (e: DataAccessException) {
            if (e.sqlState() != "23505") throw e
            (chatRepository.findMessageByIdempotencyKey(taskId, currentUser.userId, request.idempotencyKey)?.toDto()
                ?: throw e) to false
        }
        if (result.second) {
            chatEventBus.publish(result.first)
        }
        return result.first
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
            chatRepository.findMessageRowById(record.id!!)!!.toDto()
        })
        chatEventBus.publish(event)
        return event
    }

    private fun toPage(
        rows: List<ChatRepository.MessageRow>,
        limit: Int,
        isAfter: Boolean,
        isBefore: Boolean
    ): ChatMessagePageDto {
        val hasMore = rows.size > limit
        val messages = rows.take(limit).map { it.toDto() }
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

    private fun ChatRepository.MessageRow.toDto(): ChatMessageDto = ChatMessageDto(
        id = id,
        taskId = taskId,
        author = authorId?.let { ChatAuthorDto(it, authorFullName ?: "") },
        type = ChatMessageType.valueOf(type),
        content = content,
        createdAt = createdAt.toString()
    )
}
