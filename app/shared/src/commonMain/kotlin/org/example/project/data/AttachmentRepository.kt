package org.example.project.data

import org.example.project.dto.AttachmentDto
import org.example.project.dto.AttachmentEntityType

data class AttachmentFileData(
    val fileName: String,
    val mimeType: String?,
    val bytes: ByteArray
)

data class DownloadedAttachment(
    val bytes: ByteArray,
    val mimeType: String?
)

interface AttachmentRepository {
    suspend fun list(entityType: AttachmentEntityType, entityId: Long): ApiResult<List<AttachmentDto>>
    suspend fun upload(
        entityType: AttachmentEntityType,
        entityId: Long,
        file: AttachmentFileData
    ): ApiResult<AttachmentDto>
    suspend fun download(id: Long): ApiResult<DownloadedAttachment>
    suspend fun delete(id: Long): ApiResult<AttachmentDto>
}
