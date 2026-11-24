package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
enum class AttachmentEntityType {
    TASK,
    PROJECT,
    NOMENCLATURE
}

@Serializable
data class AttachmentDto(
    val id: Long,
    val entityType: AttachmentEntityType,
    val entityId: Long,
    val fileName: String,
    val sizeBytes: Long,
    val mimeType: String?,
    val sha256: String?,
    val uploadedBy: Long,
    val uploadedByName: String?,
    val uploadedAt: String
)
