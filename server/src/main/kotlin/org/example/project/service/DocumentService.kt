package org.example.project.service

import io.ktor.utils.io.ByteReadChannel
import org.example.project.dto.*
import org.example.project.exception.ConflictException
import org.example.project.exception.ForbiddenException
import org.example.project.exception.NotFoundException
import org.example.project.exception.ValidationException
import org.example.project.model.CurrentUser
import org.example.project.repository.DocumentRepository
import org.example.project.repository.UserRepository
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import java.nio.file.NoSuchFileException
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.UUID

class DocumentService(
    private val documentRepository: DocumentRepository,
    private val documentAccess: DocumentAccess,
    private val userRepository: UserRepository,
    private val fileStorage: FileStorage,
    private val auditService: AuditService,
    private val dsl: DSLContext
) {

    data class DownloadedDocument(
        val fileName: String,
        val mimeType: String?,
        val sizeBytes: Long?,
        val content: ByteArray
    )

    fun create(request: CreateDocumentRequest, currentUser: CurrentUser): DocumentDto {
        if (request.name.isBlank()) {
            throw ValidationException("Document name is required", mapOf("name" to "required"))
        }
        val ownerId = request.ownerId ?: currentUser.userId
        if (ownerId != currentUser.userId) {
            currentUser.requirePermission("documents", "manage")
        }
        if (!userRepository.exists(ownerId)) {
            throw NotFoundException("user", ownerId)
        }
        val retentionUntil = parseRetentionDate(request.retentionUntil)

        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val number = documentRepository.generateNumber()
            val id = documentRepository.insertDocument(
                number = number,
                name = request.name.trim(),
                docType = request.docType.name,
                description = request.description?.trim(),
                authorId = currentUser.userId,
                ownerId = ownerId,
                confidentiality = request.confidentiality.name,
                retentionUntil = retentionUntil,
                createdBy = currentUser.userId
            )
            auditService.log(
                currentUser.userId,
                "DOCUMENT_CREATE",
                "DOCUMENT",
                id,
                mapOf("number" to number, "name" to request.name.trim(), "docType" to request.docType.name, "ownerId" to ownerId)
            )
            loadFull(id)
        })
    }

    fun list(currentUser: CurrentUser): List<DocumentDto> {
        return documentRepository.listVisible(currentUser.userId, currentUser.hasPermission("documents", "read_any"))
            .map { it.toDto() }
    }

    /**
     * Документы проекта для вкладки карточки (PRJ-16). Видимость документов
     * не расширяется членством в проекте: закрытый документ остаётся закрытым,
     * поэтому список дополнительно фильтруется правилами DMS.
     */
    fun listByProject(projectId: Long, currentUser: CurrentUser): List<DocumentDto> {
        documentAccess.requireReadProject(projectId, currentUser)
        return documentRepository
            .listByProject(
                projectId = projectId,
                userId = currentUser.userId,
                hasReadAny = currentUser.hasPermission("documents", "read_any")
            )
            .map { it.toDto() }
    }

    /**
     * Документы номенклатуры для карточки (D-63). Право `wms:read` открывает
     * вкладку, но не расширяет видимость DMS: закрытый документ остаётся закрытым.
     */
    fun listByNomenclature(nomenclatureId: Long, currentUser: CurrentUser): List<DocumentDto> {
        currentUser.requirePermission("wms", "read")
        if (!documentRepository.linkTargetExists("NOMENCLATURE", nomenclatureId)) {
            throw NotFoundException("nomenclature", nomenclatureId)
        }
        return documentRepository
            .listByNomenclature(
                nomenclatureId = nomenclatureId,
                userId = currentUser.userId,
                hasReadAny = currentUser.hasPermission("documents", "read_any")
            )
            .map { it.toDto() }
    }

    fun get(id: Long, currentUser: CurrentUser): DocumentDto {
        loadReadable(id, currentUser)
        return loadFull(id)
    }

    fun update(id: Long, request: UpdateDocumentRequest, currentUser: CurrentUser): DocumentDto {
        val document = loadReadable(id, currentUser)
        requireManage(document, currentUser)
        request.name?.let {
            if (it.isBlank()) {
                throw ValidationException("Document name must not be blank", mapOf("name" to "blank"))
            }
        }
        val retentionUntil = parseRetentionDate(request.retentionUntil)

        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val updated = documentRepository.updateDocument(
                id = id,
                name = request.name?.trim(),
                description = request.description?.trim(),
                confidentiality = request.confidentiality?.name,
                retentionUntil = retentionUntil,
                expectedVersion = request.version
            )
            if (updated == 0) {
                throw ConflictException("Document was modified concurrently")
            }
            auditService.log(
                currentUser.userId,
                "DOCUMENT_UPDATE",
                "DOCUMENT",
                id,
                mapOf(
                    "name" to request.name,
                    "description" to request.description,
                    "confidentiality" to request.confidentiality?.name,
                    "retentionUntil" to request.retentionUntil
                )
            )
            loadFull(id)
        })
    }

    suspend fun uploadVersion(
        documentId: Long,
        originalFileName: String,
        contentType: String?,
        changeReason: String?,
        channel: ByteReadChannel,
        currentUser: CurrentUser
    ): DocumentVersionDto {
        val document = loadReadable(documentId, currentUser)
        requireManage(document, currentUser)
        val replacement = documentRepository.countVersions(documentId) > 0
        val reason = changeReason?.trim()?.ifEmpty { null }
        if (replacement && reason == null) {
            throw ValidationException("Change reason is required for a new version", mapOf("changeReason" to "required"))
        }
        val fileName = AttachmentService.validateFileName(originalFileName)
        val mimeType = AttachmentService.validateMimeType(fileName, contentType)
        val content = AttachmentService.readContentLimited(channel)

        val storageKey = "document/${UUID.randomUUID()}"
        val stored = fileStorage.store(storageKey, content)

        try {
            return dsl.transactionResult(org.jooq.TransactionalCallable {
                val fileId = documentRepository.insertFile(
                    storageKey = stored.relativePath,
                    fileName = fileName,
                    mimeType = mimeType,
                    sizeBytes = stored.sizeBytes,
                    sha256 = stored.sha256,
                    uploadedBy = currentUser.userId
                )
                val versionNumber = documentRepository.nextVersionNumber(documentId)
                val versionId = documentRepository.insertVersion(
                    documentId = documentId,
                    versionNumber = versionNumber,
                    changeReason = reason,
                    fileId = fileId,
                    fileName = fileName,
                    mimeType = mimeType,
                    sizeBytes = stored.sizeBytes,
                    sha256 = stored.sha256,
                    createdBy = currentUser.userId
                )
                documentRepository.addHistory(
                    versionId = versionId,
                    action = if (replacement) "FILE_REPLACED" else "CREATED",
                    fromStatus = null,
                    toStatus = "DRAFT",
                    reason = reason,
                    changedBy = currentUser.userId
                )
                documentRepository.touchDocument(documentId)
                auditService.log(
                    currentUser.userId,
                    "DOCUMENT_VERSION_UPLOAD",
                    "DOCUMENT",
                    documentId,
                    mapOf(
                        "versionId" to versionId,
                        "versionNumber" to versionNumber,
                        "fileName" to fileName,
                        "sizeBytes" to stored.sizeBytes,
                        "sha256" to stored.sha256
                    )
                )
                documentRepository.findVersion(documentId, versionId)!!.toDto()
            })
        } catch (e: Exception) {
            // Метаданные не зафиксированы — физический файл не должен осиротеть.
            runCatching { fileStorage.delete(stored.relativePath) }
            if (e is DataAccessException && e.sqlState() == "23505") {
                throw ConflictException("Document version was created concurrently")
            }
            throw e
        }
    }

    fun approve(
        documentId: Long,
        versionId: Long,
        request: VersionTransitionRequest,
        currentUser: CurrentUser
    ): DocumentVersionDto {
        loadReadable(documentId, currentUser)
        currentUser.requirePermission("documents", "approve")
        documentRepository.findVersion(documentId, versionId)
            ?: throw NotFoundException("document_version", versionId)

        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val updated = documentRepository.approveVersion(documentId, versionId, currentUser.userId, request.version)
            if (updated == 0) {
                throw ConflictException("Version is not in DRAFT status or was modified concurrently")
            }
            documentRepository.addHistory(versionId, "APPROVED", "DRAFT", "APPROVED", request.reason, currentUser.userId)
            documentRepository.setDocumentStatus(documentId, "APPROVED")
            auditService.log(
                currentUser.userId,
                "DOCUMENT_VERSION_APPROVE",
                "DOCUMENT",
                documentId,
                mapOf("versionId" to versionId, "reason" to request.reason)
            )
            documentRepository.findVersion(documentId, versionId)!!.toDto()
        })
    }

    fun activate(
        documentId: Long,
        versionId: Long,
        request: VersionTransitionRequest,
        currentUser: CurrentUser
    ): DocumentVersionDto {
        loadReadable(documentId, currentUser)
        currentUser.requirePermission("documents", "activate")
        documentRepository.findVersion(documentId, versionId)
            ?: throw NotFoundException("document_version", versionId)

        return dsl.transactionResult(org.jooq.TransactionalCallable {
            // Атомарно в одной транзакции: прежняя ACTIVE -> REPLACED, новая -> ACTIVE, карточка -> current_version_id.
            val replacedIds = documentRepository.replaceActiveVersions(documentId, versionId)
            val updated = documentRepository.activateVersion(documentId, versionId, request.version)
            if (updated == 0) {
                throw ConflictException("Version is not in APPROVED status or was modified concurrently")
            }
            replacedIds.forEach { replacedId ->
                documentRepository.addHistory(replacedId, "REPLACED", "ACTIVE", "REPLACED", request.reason, currentUser.userId)
            }
            documentRepository.addHistory(versionId, "ACTIVATED", "APPROVED", "ACTIVE", request.reason, currentUser.userId)
            documentRepository.setCurrentVersion(documentId, versionId, "ACTIVE")
            auditService.log(
                currentUser.userId,
                "DOCUMENT_VERSION_ACTIVATE",
                "DOCUMENT",
                documentId,
                mapOf("versionId" to versionId, "replacedVersionIds" to replacedIds.joinToString(","))
            )
            documentRepository.findVersion(documentId, versionId)!!.toDto()
        })
    }

    fun cancel(
        documentId: Long,
        versionId: Long,
        request: VersionTransitionRequest,
        currentUser: CurrentUser
    ): DocumentVersionDto {
        val document = loadReadable(documentId, currentUser)
        requireManage(document, currentUser)
        documentRepository.findVersion(documentId, versionId)
            ?: throw NotFoundException("document_version", versionId)

        return dsl.transactionResult(org.jooq.TransactionalCallable {
            val updated = documentRepository.cancelVersion(documentId, versionId, request.version)
            if (updated == 0) {
                throw ConflictException("Version is not in DRAFT status or was modified concurrently")
            }
            documentRepository.addHistory(versionId, "CANCELLED", "DRAFT", "CANCELLED", request.reason, currentUser.userId)
            auditService.log(
                currentUser.userId,
                "DOCUMENT_VERSION_CANCEL",
                "DOCUMENT",
                documentId,
                mapOf("versionId" to versionId, "reason" to request.reason)
            )
            documentRepository.findVersion(documentId, versionId)!!.toDto()
        })
    }

    fun addLink(documentId: Long, request: CreateDocumentLinkRequest, currentUser: CurrentUser): DocumentLinkDto {
        val document = loadReadable(documentId, currentUser)
        requireManage(document, currentUser)
        validateLinkTarget(request.linkType, request.targetId, currentUser)

        try {
            return dsl.transactionResult(org.jooq.TransactionalCallable {
                val linkId = documentRepository.insertLink(
                    documentId = documentId,
                    linkType = request.linkType.name,
                    targetId = request.targetId,
                    createdBy = currentUser.userId
                )
                auditService.log(
                    currentUser.userId,
                    "DOCUMENT_LINK_ADD",
                    "DOCUMENT",
                    documentId,
                    mapOf("linkId" to linkId, "linkType" to request.linkType.name, "targetId" to request.targetId)
                )
                documentRepository.findLink(documentId, linkId)!!.toDto()
            })
        } catch (e: DataAccessException) {
            if (e.sqlState() != "23505") throw e
            throw ConflictException("Document is already linked to this entity")
        }
    }

    fun removeLink(documentId: Long, linkId: Long, currentUser: CurrentUser): DocumentLinkDto {
        val document = loadReadable(documentId, currentUser)
        requireManage(document, currentUser)
        val link = documentRepository.findLink(documentId, linkId)
            ?: throw NotFoundException("document_link", linkId)

        return dsl.transactionResult(org.jooq.TransactionalCallable {
            if (documentRepository.deleteLink(documentId, linkId) == 0) {
                throw NotFoundException("document_link", linkId)
            }
            auditService.log(
                currentUser.userId,
                "DOCUMENT_LINK_REMOVE",
                "DOCUMENT",
                documentId,
                mapOf("linkId" to linkId, "linkType" to link.linkType, "targetId" to link.targetId)
            )
            link.toDto()
        })
    }

    fun download(
        versionId: Long,
        ipAddress: String?,
        userAgent: String?,
        currentUser: CurrentUser
    ): DownloadedDocument {
        val version = documentRepository.findVersionById(versionId)
            ?: throw NotFoundException("document_version", versionId)
        val document = documentRepository.findDocumentById(version.documentId)!!
        if (!canRead(document, currentUser)) {
            // Не раскрываем существование и метаданные чужого документа.
            throw NotFoundException("document_version", versionId)
        }
        if (!canDownload(document, currentUser)) {
            throw ForbiddenException("Missing permission documents:download")
        }
        val fileId = version.fileId ?: throw NotFoundException("document_file", versionId)
        val storageKey = documentRepository.findFileStorageKey(fileId)
            ?: throw NotFoundException("document_file", versionId)
        val content = try {
            fileStorage.read(storageKey)
        } catch (e: NoSuchFileException) {
            throw NotFoundException("document_file", versionId)
        }

        dsl.transactionResult(org.jooq.TransactionalCallable {
            documentRepository.logDownload(versionId, currentUser.userId, ipAddress, userAgent)
            auditService.log(
                currentUser.userId,
                "DOCUMENT_DOWNLOAD",
                "DOCUMENT_VERSION",
                versionId,
                mapOf(
                    "documentId" to version.documentId,
                    "versionNumber" to version.versionNumber,
                    "fileName" to version.fileName,
                    "sha256" to version.sha256
                )
            )
            Unit
        })
        return DownloadedDocument(
            fileName = version.fileName,
            mimeType = version.mimeType,
            sizeBytes = version.sizeBytes,
            content = content
        )
    }

    private fun validateLinkTarget(linkType: DocumentLinkType, targetId: Long, currentUser: CurrentUser) {
        if (!documentRepository.linkTargetExists(linkType.name, targetId)) {
            throw NotFoundException("link_target", "${linkType.name}:$targetId")
        }
        val allowed = when (linkType) {
            DocumentLinkType.PROJECT -> documentAccess.canReadProject(targetId, currentUser)
            DocumentLinkType.TASK -> documentAccess.canReadTask(targetId, currentUser)
            DocumentLinkType.PRODUCT_MODEL, DocumentLinkType.BOM_REVISION ->
                currentUser.hasPermission("bom", "read_any")
            DocumentLinkType.DEMAND -> currentUser.hasPermission("demands", "read_any")
            // Привязка меняет состав карточки номенклатуры, поэтому нужно право её изменять.
            DocumentLinkType.NOMENCLATURE -> currentUser.hasPermission("wms", "manage")
        }
        if (!allowed) {
            throw ForbiddenException("Access to the linked entity denied")
        }
    }

    private fun loadReadable(id: Long, currentUser: CurrentUser): DocumentRepository.DocumentRow {
        val document = documentRepository.findDocumentById(id) ?: throw NotFoundException("document", id)
        if (!canRead(document, currentUser)) {
            // Не раскрываем существование чужого документа.
            throw NotFoundException("document", id)
        }
        return document
    }

    private fun canRead(document: DocumentRepository.DocumentRow, currentUser: CurrentUser): Boolean {
        return document.confidentiality == "PUBLIC" ||
            document.confidentiality == "INTERNAL" ||
            document.ownerId == currentUser.userId ||
            currentUser.hasPermission("documents", "read_any")
    }

    private fun canDownload(document: DocumentRepository.DocumentRow, currentUser: CurrentUser): Boolean {
        return canRead(document, currentUser) &&
            (document.ownerId == currentUser.userId ||
                currentUser.hasPermission("documents", "download") ||
                currentUser.hasPermission("documents", "read_any"))
    }

    private fun requireManage(document: DocumentRepository.DocumentRow, currentUser: CurrentUser) {
        if (document.ownerId != currentUser.userId && !currentUser.hasPermission("documents", "manage")) {
            throw ForbiddenException("Only document owner or authorized user can modify this document")
        }
    }

    private fun loadFull(id: Long): DocumentDto {
        val document = documentRepository.findDocumentById(id) ?: throw NotFoundException("document", id)
        val versions = documentRepository.listVersions(id).map { it.toDto() }
        val links = documentRepository.listLinks(id).map { it.toDto() }
        return document.toDto(versions, links)
    }

    private fun parseRetentionDate(value: String?): LocalDate? {
        if (value.isNullOrBlank()) return null
        return try {
            LocalDate.parse(value.trim())
        } catch (e: DateTimeParseException) {
            throw ValidationException("Invalid retention date", mapOf("retentionUntil" to "invalid"))
        }
    }

    private fun DocumentRepository.DocumentRow.toDto(
        versions: List<DocumentVersionDto> = emptyList(),
        links: List<DocumentLinkDto> = emptyList()
    ): DocumentDto = DocumentDto(
        id = id,
        number = number,
        name = name,
        docType = DocumentType.valueOf(docType),
        description = description,
        authorId = authorId,
        ownerId = ownerId,
        status = DocumentStatus.valueOf(status),
        confidentiality = DocumentConfidentiality.valueOf(confidentiality),
        retentionUntil = retentionUntil?.toString(),
        currentVersionId = currentVersionId,
        createdBy = createdBy,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        version = version,
        versions = versions,
        links = links
    )

    private fun DocumentRepository.DocumentVersionRow.toDto(): DocumentVersionDto = DocumentVersionDto(
        id = id,
        documentId = documentId,
        versionNumber = versionNumber,
        status = DocumentVersionStatus.valueOf(status),
        changeReason = changeReason,
        fileId = fileId,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        sha256 = sha256,
        approvedBy = approvedBy,
        approvedAt = approvedAt?.toString(),
        createdBy = createdBy,
        createdAt = createdAt.toString(),
        version = version
    )

    private fun DocumentRepository.DocumentLinkRow.toDto(): DocumentLinkDto = DocumentLinkDto(
        id = id,
        documentId = documentId,
        linkType = DocumentLinkType.valueOf(linkType),
        targetId = targetId,
        createdBy = createdBy,
        createdAt = createdAt.toString()
    )
}
