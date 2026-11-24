package org.example.project.service

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import org.example.project.dto.AttachmentDto
import org.example.project.dto.AttachmentEntityType
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.model.CurrentUser
import org.example.project.repository.AttachmentRepository
import org.example.project.repository.ChatRepository
import org.example.project.repository.ConversationRepository
import org.example.project.repository.ProjectRepository
import org.jooq.DSLContext
import java.nio.file.NoSuchFileException
import java.util.UUID

class IncomingMessageFile(
    val originalFileName: String?,
    val contentType: String?,
    val channel: ByteReadChannel
)

class PreparedMessageFile(
    val fileName: String,
    val mimeType: String,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String?
)

class AttachmentService(
    private val attachmentRepository: AttachmentRepository,
    private val chatRepository: ChatRepository,
    private val projectRepository: ProjectRepository,
    private val fileStorage: FileStorage,
    private val auditService: AuditService,
    private val dsl: DSLContext,
    private val conversationRepository: ConversationRepository? = null
) {

    data class DownloadedAttachment(
        val fileName: String,
        val mimeType: String?,
        val sizeBytes: Long,
        val content: ByteArray
    )

    suspend fun upload(
        entityType: AttachmentEntityType,
        entityId: Long,
        originalFileName: String,
        contentType: String?,
        channel: ByteReadChannel,
        currentUser: CurrentUser
    ): AttachmentDto {
        requireWriteAccess(entityType.name, entityId, currentUser)
        val fileName = validateFileName(originalFileName)
        val mimeType = validateMimeType(fileName, contentType)
        val content = readContentLimited(channel)

        val storagePath = "${entityType.name.lowercase()}/${UUID.randomUUID()}"
        val stored = fileStorage.store(storagePath, content)

        try {
            return dsl.transactionResult(org.jooq.TransactionalCallable {
                val id = attachmentRepository.insert(
                    entityType = entityType.name,
                    entityId = entityId,
                    fileName = fileName,
                    storagePath = stored.relativePath,
                    sizeBytes = stored.sizeBytes,
                    mimeType = mimeType,
                    sha256 = stored.sha256,
                    uploadedBy = currentUser.userId
                )
                auditService.log(
                    currentUser.userId,
                    "ATTACHMENT_UPLOAD",
                    "ATTACHMENT",
                    id,
                    mapOf(
                        "entityType" to entityType.name,
                        "entityId" to entityId,
                        "fileName" to fileName,
                        "sizeBytes" to stored.sizeBytes,
                        "sha256" to stored.sha256
                    )
                )
                attachmentRepository.findById(id)!!.toDto()
            })
        } catch (e: Exception) {
            // Транзакция не зафиксирована — не оставляем осиротевший файл.
            runCatching { fileStorage.delete(stored.relativePath) }
            throw e
        }
    }

    fun list(entityType: AttachmentEntityType, entityId: Long, currentUser: CurrentUser): List<AttachmentDto> {
        requireReadAccess(entityType.name, entityId, currentUser)
        return attachmentRepository.listForEntity(entityType.name, entityId).map { it.toDto() }
    }

    fun listForMessageAttachments(entityType: String, messageIds: List<Long>): Map<Long, List<AttachmentDto>> {
        if (messageIds.isEmpty()) return emptyMap()
        return attachmentRepository.listForMessages(entityType, messageIds)
            .map { it.toDto() }
            .groupBy { it.entityId }
    }

    suspend fun prepareMessageFile(
        originalFileName: String?,
        contentType: String?,
        channel: ByteReadChannel
    ): PreparedMessageFile {
        val fileName = validateFileName(originalFileName ?: "")
        val mimeType = validateMimeType(fileName, contentType)
        val content = readContentLimited(channel)
        val stored = fileStorage.store("message/${UUID.randomUUID()}", content)
        return PreparedMessageFile(fileName, mimeType, stored.relativePath, stored.sizeBytes, stored.sha256)
    }

    fun discardMessageFile(prepared: PreparedMessageFile) {
        runCatching { fileStorage.delete(prepared.relativePath) }
    }

    fun discardMessageFiles(prepared: Iterable<PreparedMessageFile>) {
        prepared.forEach(::discardMessageFile)
    }

    fun attachPreparedFile(
        prepared: PreparedMessageFile,
        entityType: String,
        entityId: Long,
        uploadedBy: Long
    ): Long = attachmentRepository.insert(
        entityType = entityType,
        entityId = entityId,
        fileName = prepared.fileName,
        storagePath = prepared.relativePath,
        sizeBytes = prepared.sizeBytes,
        mimeType = prepared.mimeType,
        sha256 = prepared.sha256,
        uploadedBy = uploadedBy
    )

    fun download(id: Long, currentUser: CurrentUser): DownloadedAttachment {
        val row = attachmentRepository.findById(id) ?: throw NotFoundException("attachment", id)
        requireReadAccess(row.entityType, row.entityId, currentUser)
        val content = try {
            fileStorage.read(row.storagePath)
        } catch (e: NoSuchFileException) {
            throw NotFoundException("attachment_file", id)
        }
        return DownloadedAttachment(
            fileName = row.fileName,
            mimeType = row.mimeType,
            sizeBytes = row.sizeBytes,
            content = content
        )
    }

    fun delete(id: Long, currentUser: CurrentUser): AttachmentDto {
        val row = attachmentRepository.findById(id) ?: throw NotFoundException("attachment", id)
        requireWriteAccess(row.entityType, row.entityId, currentUser)

        val deleted = dsl.transactionResult(org.jooq.TransactionalCallable {
            if (!attachmentRepository.delete(id)) {
                throw NotFoundException("attachment", id)
            }
            auditService.log(
                currentUser.userId,
                "ATTACHMENT_DELETE",
                "ATTACHMENT",
                id,
                mapOf(
                    "entityType" to row.entityType,
                    "entityId" to row.entityId,
                    "fileName" to row.fileName,
                    "sha256" to row.sha256
                )
            )
            row.toDto()
        })
        // Метаданные уже удалены в транзакции; файл убираем после фиксации.
        runCatching { fileStorage.delete(row.storagePath) }
        return deleted
    }

    private fun requireNomenclatureExists(id: Long) {
        val nomenclature = org.example.project.generated.jooq.tables.Nomenclature.NOMENCLATURE
        if (!dsl.fetchExists(dsl.selectOne().from(nomenclature).where(nomenclature.ID.eq(id)))) {
            throw NotFoundException("nomenclature", id)
        }
    }

    private fun requireReadAccess(entityType: String, entityId: Long, currentUser: CurrentUser) {
        when (entityType) {
            "TASK" -> {
                chatRepository.findTaskById(entityId) ?: throw NotFoundException("task", entityId)
                val hasReadAny = currentUser.hasPermission("tasks", "read_any")
                if (!chatRepository.canRead(entityId, currentUser.userId, hasReadAny)) {
                    throw ForbiddenException("Access to task attachments denied")
                }
            }
            "PROJECT" -> {
                val project = projectRepository.findById(entityId) ?: throw NotFoundException("project", entityId)
                val allowed = currentUser.hasPermission("projects", "read_any") ||
                    project.ownerId == currentUser.userId ||
                    projectRepository.isMember(entityId, currentUser.userId)
                if (!allowed) {
                    throw ForbiddenException("Access to project attachments denied")
                }
            }
            // Изображения и файлы карточки номенклатуры видит любой держатель wms:read (D-63).
            "NOMENCLATURE" -> {
                requireNomenclatureExists(entityId)
                if (!currentUser.hasPermission("wms", "read")) {
                    throw ForbiddenException("Access to nomenclature attachments denied")
                }
            }
            "TASK_CHAT_MESSAGE" -> {
                val taskId = attachmentRepository.findTaskIdForChatMessage(entityId)
                    ?: throw NotFoundException("attachment", entityId)
                val hasReadAny = currentUser.hasPermission("tasks", "read_any")
                if (!chatRepository.canRead(taskId, currentUser.userId, hasReadAny)) {
                    throw ForbiddenException("Access to message attachments denied")
                }
            }
            "CONVERSATION_MESSAGE" -> {
                val message = conversationRepository?.findMessageRowById(entityId)
                    ?: throw NotFoundException("attachment", entityId)
                if (!hasConversationAccess(message.conversationId, currentUser.userId)) {
                    throw ForbiddenException("Access to message attachments denied")
                }
            }
            else -> throw NotFoundException("attachment", entityId)
        }
    }

    private fun requireWriteAccess(entityType: String, entityId: Long, currentUser: CurrentUser) {
        when (entityType) {
            "TASK" -> {
                chatRepository.findTaskById(entityId) ?: throw NotFoundException("task", entityId)
                val hasManage = currentUser.hasPermission("attachments", "manage")
                if (!chatRepository.canWrite(entityId, currentUser.userId, hasManage)) {
                    throw ForbiddenException("Upload/delete of task attachments denied")
                }
                if (chatRepository.isTaskClosed(entityId)) {
                    throw ForbiddenException("Task is closed")
                }
            }
            "PROJECT" -> {
                val project = projectRepository.findById(entityId) ?: throw NotFoundException("project", entityId)
                val allowed = project.ownerId == currentUser.userId ||
                    currentUser.hasPermission("projects", "manage_members")
                if (!allowed) {
                    throw ForbiddenException("Upload/delete of project attachments denied")
                }
            }
            "NOMENCLATURE" -> {
                requireNomenclatureExists(entityId)
                if (!currentUser.hasPermission("wms", "manage")) {
                    throw ForbiddenException("Upload/delete of nomenclature attachments denied")
                }
            }
            "TASK_CHAT_MESSAGE" -> {
                val taskId = attachmentRepository.findTaskIdForChatMessage(entityId)
                    ?: throw NotFoundException("attachment", entityId)
                val hasManage = currentUser.hasPermission("attachments", "manage")
                if (!chatRepository.canWrite(taskId, currentUser.userId, hasManage)) {
                    throw ForbiddenException("Delete of message attachments denied")
                }
                if (chatRepository.isTaskClosed(taskId)) {
                    throw ForbiddenException("Task is closed")
                }
            }
            "CONVERSATION_MESSAGE" -> {
                val message = conversationRepository?.findMessageRowById(entityId)
                    ?: throw NotFoundException("attachment", entityId)
                if (!hasConversationAccess(message.conversationId, currentUser.userId)) {
                    throw ForbiddenException("Delete of message attachments denied")
                }
            }
            else -> throw NotFoundException("attachment", entityId)
        }
    }

    private fun hasConversationAccess(conversationId: Long, userId: Long): Boolean {
        val repository = conversationRepository ?: return false
        val conversation = repository.findById(conversationId) ?: return false
        return when (conversation.kind) {
            "PROJECT" -> conversation.projectId != null &&
                repository.isProjectMemberOrOwner(conversation.projectId, userId)
            else -> repository.isParticipant(conversationId, userId)
        }
    }

    private fun AttachmentRepository.AttachmentRow.toDto(): AttachmentDto = AttachmentDto(
        id = id,
        // Файлы сообщений хранятся с entityType TASK_CHAT_MESSAGE/CONVERSATION_MESSAGE;
        // карточные типы остаются TASK/PROJECT, для DTO сообщений тип деградирует до TASK.
        entityType = runCatching { AttachmentEntityType.valueOf(entityType) }
            .getOrDefault(AttachmentEntityType.TASK),
        entityId = entityId,
        fileName = fileName,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        sha256 = sha256,
        uploadedBy = uploadedBy,
        uploadedByName = uploadedByName,
        uploadedAt = uploadedAt.toString()
    )

    companion object {
        const val MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024
        const val MAX_MESSAGE_FILES = 5
        const val MAX_MESSAGE_AGGREGATE_SIZE_BYTES = 50L * 1024 * 1024
        private const val GENERIC_MIME = "application/octet-stream"

        private val ALLOWED_MIME_BY_EXTENSION = mapOf(
            "pdf" to "application/pdf",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "png" to "image/png",
            "zip" to "application/zip",
            "txt" to "text/plain"
        )

        fun validateFileName(originalFileName: String): String {
            val fileName = originalFileName
                .substringAfterLast('/')
                .substringAfterLast('\\')
                .trim()
            if (fileName.isEmpty()) {
                throw ValidationException("File name is required", mapOf("file" to "required"))
            }
            val extension = fileName.substringAfterLast('.', "").lowercase()
            if (extension !in ALLOWED_MIME_BY_EXTENSION.keys) {
                throw ValidationException(
                    "File extension is not allowed",
                    mapOf("file" to "unsupported_extension")
                )
            }
            return fileName
        }

        fun validateMimeType(fileName: String, contentType: String?): String {
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val expected = ALLOWED_MIME_BY_EXTENSION.getValue(extension)
            val provided = contentType?.substringBefore(';')?.trim()?.lowercase()
            if (!provided.isNullOrEmpty() && provided != GENERIC_MIME && provided != expected) {
                throw ValidationException(
                    "File content type does not match its extension",
                    mapOf("file" to "mime_mismatch")
                )
            }
            return expected
        }

        suspend fun readContentLimited(channel: ByteReadChannel): ByteArray {
            // Фактический размер: читаем не более MAX+1 байт независимо от Content-Length.
            val content = channel.readRemaining(MAX_FILE_SIZE_BYTES + 1).readByteArray()
            if (content.size.toLong() > MAX_FILE_SIZE_BYTES) {
                throw ValidationException(
                    "File exceeds maximum size of ${MAX_FILE_SIZE_BYTES / 1024 / 1024} MB",
                    mapOf("file" to "too_large")
                )
            }
            if (content.isEmpty()) {
                throw ValidationException("Empty files are not allowed", mapOf("file" to "empty"))
            }
            return content
        }
    }
}
