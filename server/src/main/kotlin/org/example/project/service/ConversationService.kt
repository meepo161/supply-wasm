package org.example.project.service

import org.example.project.dto.*
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
        val pageRows = rows.take(effectiveLimit)
        val attachmentMap = attachmentsFor(pageRows.map { it.id })
        val messages = pageRows.map { it.toDto(attachmentMap[it.id] ?: emptyList()) }
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

    suspend fun sendMessage(
        id: Long,
        request: SendChatMessageRequest,
        currentUser: CurrentUser,
        file: IncomingMessageFile? = null
    ): ConversationMessageDto {
        val row = conversationRepository.findById(id) ?: throw NotFoundException("conversation", id)
        requireAccess(row, currentUser)
        if (request.content.isBlank() && file == null) {
            throw ValidationException("Message content is required", mapOf("content" to "required"))
        }
        if (request.idempotencyKey.isBlank()) {
            throw ValidationException("Idempotency key is required", mapOf("idempotencyKey" to "required"))
        }
        if (file != null) {
            conversationRepository.findMessageByIdempotencyKey(id, currentUser.userId, request.idempotencyKey)
                ?.let { return it.toDto(attachmentsFor(listOf(it.id))[it.id] ?: emptyList()) }
        }

        val prepared = file?.let {
            val service = attachmentService
                ?: throw ValidationException("File attachments are not available", mapOf("file" to "unsupported"))
            service.prepareMessageFile(it.originalFileName, it.contentType, it.channel)
        }

        val result = try {
            dsl.transactionResult(org.jooq.TransactionalCallable {
                val existing = conversationRepository.findMessageByIdempotencyKey(
                    id, currentUser.userId, request.idempotencyKey
                )
                if (existing != null) {
                    return@TransactionalCallable SendOutcome(
                        existing.toDto(attachmentsFor(listOf(existing.id))[existing.id] ?: emptyList()),
                        created = false,
                        notifications = emptyList()
                    )
                }
                val record = conversationRepository.insertMessage(
                    conversationId = id,
                    authorId = currentUser.userId,
                    content = request.content.trim(),
                    idempotencyKey = request.idempotencyKey
                )
                if (prepared != null) {
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
                val recipientIds = when (row.kind) {
                    "PROJECT" -> conversationRepository.listProjectRecipientIds(row.projectId!!, currentUser.userId)
                    else -> conversationRepository.listParticipantIds(id, currentUser.userId)
                }
                val notifications = notificationService.createForNewConversationMessage(id, recipientIds)
                auditService.log(
                    currentUser.userId,
                    "CONVERSATION_MESSAGE_SEND",
                    "CONVERSATION",
                    id,
                    mapOf("messageId" to record.id!!, "idempotencyKey" to request.idempotencyKey)
                )
                val messageRow = conversationRepository.findMessageRowById(record.id!!)!!
                SendOutcome(
                    messageRow.toDto(attachmentsFor(listOf(messageRow.id))[messageRow.id] ?: emptyList()),
                    created = true,
                    notifications = notifications
                )
            })
        } catch (e: DataAccessException) {
            if (e.sqlState() != "23505") {
                prepared?.let { attachmentService?.discardMessageFile(it) }
                throw e
            }
            prepared?.let { attachmentService?.discardMessageFile(it) }
            SendOutcome(
                conversationRepository.findMessageByIdempotencyKey(id, currentUser.userId, request.idempotencyKey)
                    ?.let { row -> row.toDto(attachmentsFor(listOf(row.id))[row.id] ?: emptyList()) }
                    ?: throw e,
                created = false,
                notifications = emptyList()
            )
        } catch (e: Exception) {
            prepared?.let { attachmentService?.discardMessageFile(it) }
            throw e
        }
        if (result.created) {
            conversationEventBus.publish(result.message)
            notificationEventBus.publishAll(result.notifications)
        } else {
            prepared?.let { attachmentService?.discardMessageFile(it) }
        }
        return result.message
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
        else -> conversationRepository.isParticipant(row.id, userId)
    }

    private fun requireAccess(row: ConversationRepository.ConversationRow, currentUser: CurrentUser) {
        if (!canAccess(row, currentUser.userId)) {
            throw ForbiddenException("Access to conversation denied")
        }
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

    private fun attachmentsFor(messageIds: List<Long>): Map<Long, List<AttachmentDto>> =
        attachmentService?.listForMessageAttachments("CONVERSATION_MESSAGE", messageIds) ?: emptyMap()

    private fun ConversationRepository.MessageRow.toDto(attachments: List<AttachmentDto> = emptyList()): ConversationMessageDto =
        ConversationMessageDto(
            id = id,
            conversationId = conversationId,
            author = authorId?.let { ChatAuthorDto(it, authorFullName ?: "") },
            content = content,
            createdAt = createdAt.toString(),
            attachments = attachments
        )

    private data class SendOutcome(
        val message: ConversationMessageDto,
        val created: Boolean,
        val notifications: List<NotificationEventBus.Event>
    )
}
