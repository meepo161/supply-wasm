package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
enum class AssistantRole {
    USER,
    ASSISTANT
}

@Serializable
data class AssistantAttachmentDto(
    val fileName: String,
    val mimeType: String? = null,
    val sizeBytes: Long = 0L
)

@Serializable
data class AssistantUploadDto(
    val fileName: String,
    val mimeType: String? = null,
    val base64Data: String
)

@Serializable
data class AssistantDraftSupplierDto(
    val name: String,
    val inn: String? = null,
    val kpp: String? = null,
    val existingSupplierId: Long? = null,
    val createIfMissing: Boolean = true
)

@Serializable
data class AssistantDraftLineDto(
    val code: String,
    val name: String,
    val unit: String = "шт",
    val quantity: String = "1",
    val unitPrice: String? = null,
    val existingNomenclatureId: Long? = null,
    val inStockQty: String? = null,
    val createIfMissing: Boolean = true
)

@Serializable
enum class AssistantActionKind {
    CREATE_TASK,
    CREATE_PROJECT,
    CREATE_WAREHOUSE,
    CREATE_LOCATION,
    CREATE_RESERVATION
}

@Serializable
data class AssistantActionDto(
    val kind: AssistantActionKind,
    val label: String,
    val params: Map<String, String> = emptyMap()
)

@Serializable
data class AssistantDraftDto(
    val draftId: String,
    val title: String,
    val summary: String,
    val invoiceNumber: String? = null,
    val invoiceDate: String? = null,
    val supplier: AssistantDraftSupplierDto? = null,
    val lines: List<AssistantDraftLineDto> = emptyList(),
    val createDemand: Boolean = true,
    val demandNote: String? = null,
    val actions: List<AssistantActionDto> = emptyList(),
    val executed: Boolean = false,
    val executionSummary: String? = null
)

@Serializable
data class AssistantMessageDto(
    val role: AssistantRole,
    val content: String,
    val toolUsed: String? = null,
    val attachments: List<AssistantAttachmentDto> = emptyList(),
    val draft: AssistantDraftDto? = null
)

@Serializable
data class AssistantChatRequestDto(
    val messages: List<AssistantMessageDto>,
    val attachments: List<AssistantUploadDto> = emptyList(),
    val conversationId: Long? = null
)

@Serializable
data class AssistantChatResponseDto(
    val reply: String,
    val model: String,
    val toolUsed: String? = null,
    val draft: AssistantDraftDto? = null,
    val conversationId: Long? = null
)

@Serializable
data class AssistantConfirmDraftRequestDto(
    val draft: AssistantDraftDto,
    val idempotencyKey: String? = null,
    val conversationId: Long? = null
)

@Serializable
data class AssistantConversationSummaryDto(
    val id: Long,
    val title: String,
    val updatedAt: String
)

@Serializable
data class AssistantConversationDetailDto(
    val id: Long,
    val title: String,
    val messages: List<AssistantMessageDto>
)

@Serializable
data class AssistantConfirmDraftResponseDto(
    val summary: String,
    val createdSupplierId: Long? = null,
    val createdNomenclatureCount: Int = 0,
    val createdDemandNumber: String? = null
)

@Serializable
data class AssistantStatusDto(
    val available: Boolean,
    val model: String,
    val reason: String? = null
)
