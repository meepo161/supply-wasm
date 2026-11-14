package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
enum class DocumentType {
    TECHNICAL_SPECIFICATION, CONTRACT, INVOICE, COMMERCIAL_OFFER,
    ORDER, WAYBILL, ACT, PRODUCT_PASSPORT, MANUAL, DRAWING,
    SPECIFICATION, TEST_PROTOCOL, MEMO, ORDER_DOC, REPORT,
    CERTIFICATE, PHOTO, OTHER
}

@Serializable
enum class DocumentStatus {
    DRAFT, ON_APPROVAL, APPROVED, ACTIVE, REPLACED, ARCHIVED, REJECTED
}

@Serializable
enum class DocumentConfidentiality {
    PUBLIC, INTERNAL, CONFIDENTIAL, SECRET
}

@Serializable
enum class DocumentVersionStatus {
    DRAFT, APPROVED, ACTIVE, REPLACED, CANCELLED
}

@Serializable
enum class DocumentLinkType {
    PROJECT, TASK, PRODUCT_MODEL, BOM_REVISION, DEMAND
}

@Serializable
data class DocumentVersionDto(
    val id: Long,
    val documentId: Long,
    val versionNumber: Int,
    val status: DocumentVersionStatus,
    val changeReason: String?,
    val fileId: Long?,
    val fileName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val sha256: String?,
    val approvedBy: Long?,
    val approvedAt: String?,
    val createdBy: Long,
    val createdAt: String,
    val version: Long
)

@Serializable
data class DocumentLinkDto(
    val id: Long,
    val documentId: Long,
    val linkType: DocumentLinkType,
    val targetId: Long,
    val createdBy: Long,
    val createdAt: String
)

@Serializable
data class DocumentDto(
    val id: Long,
    val number: String,
    val name: String,
    val docType: DocumentType,
    val description: String?,
    val authorId: Long?,
    val ownerId: Long,
    val status: DocumentStatus,
    val confidentiality: DocumentConfidentiality,
    val retentionUntil: String?,
    val currentVersionId: Long?,
    val createdBy: Long,
    val createdAt: String,
    val updatedAt: String,
    val version: Long,
    val versions: List<DocumentVersionDto> = emptyList(),
    val links: List<DocumentLinkDto> = emptyList()
)

@Serializable
data class CreateDocumentRequest(
    val name: String,
    val docType: DocumentType,
    val description: String? = null,
    val ownerId: Long? = null,
    val confidentiality: DocumentConfidentiality = DocumentConfidentiality.INTERNAL,
    val retentionUntil: String? = null
)

@Serializable
data class UpdateDocumentRequest(
    val version: Long,
    val name: String? = null,
    val description: String? = null,
    val confidentiality: DocumentConfidentiality? = null,
    val retentionUntil: String? = null
)

@Serializable
data class CreateDocumentLinkRequest(
    val linkType: DocumentLinkType,
    val targetId: Long
)

@Serializable
data class VersionTransitionRequest(
    val version: Long,
    val reason: String? = null
)
