package org.example.project.data

import org.example.project.dto.CreateDocumentLinkRequest
import org.example.project.dto.CreateDocumentRequest
import org.example.project.dto.DocumentDto
import org.example.project.dto.DocumentLinkDto
import org.example.project.dto.DocumentVersionDto
import org.example.project.dto.UpdateDocumentRequest
import org.example.project.dto.VersionTransitionRequest

interface DocumentRepository {
    suspend fun list(): ApiResult<List<DocumentDto>>
    suspend fun get(id: Long): ApiResult<DocumentDto>
    suspend fun create(request: CreateDocumentRequest): ApiResult<DocumentDto>
    suspend fun update(id: Long, request: UpdateDocumentRequest): ApiResult<DocumentDto>
    suspend fun uploadVersion(
        documentId: Long,
        file: AttachmentFileData,
        changeReason: String?
    ): ApiResult<DocumentVersionDto>
    suspend fun approveVersion(
        documentId: Long,
        versionId: Long,
        request: VersionTransitionRequest
    ): ApiResult<DocumentVersionDto>
    suspend fun activateVersion(
        documentId: Long,
        versionId: Long,
        request: VersionTransitionRequest
    ): ApiResult<DocumentVersionDto>
    suspend fun cancelVersion(
        documentId: Long,
        versionId: Long,
        request: VersionTransitionRequest
    ): ApiResult<DocumentVersionDto>
    suspend fun addLink(documentId: Long, request: CreateDocumentLinkRequest): ApiResult<DocumentLinkDto>
    suspend fun removeLink(documentId: Long, linkId: Long): ApiResult<DocumentLinkDto>
    suspend fun downloadVersion(versionId: Long): ApiResult<DownloadedAttachment>
}
