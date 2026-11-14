package org.example.project.dto

import kotlinx.serialization.Serializable

// Количества передаются строками, чтобы сохранить точность NUMERIC(19,4)
// без потерь при сериализации (инвариант 20 ТЗ).

@Serializable
enum class DemandSourceType {
    PROJECT,
    STOCKPILE,
    MANUAL
}

@Serializable
enum class DemandStatus {
    DRAFT,
    PENDING_APPROVAL,
    APPROVED,
    IN_PROCUREMENT,
    PARTIALLY_COVERED,
    COVERED,
    CANCELLED
}

@Serializable
enum class DemandPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

@Serializable
data class DemandHeaderDto(
    val id: Long,
    val number: String,
    val sourceType: DemandSourceType,
    val projectId: Long? = null,
    val stockpileNote: String? = null,
    val initiatorId: Long,
    val requiredDate: String? = null,
    val priority: DemandPriority = DemandPriority.NORMAL,
    val status: DemandStatus = DemandStatus.DRAFT,
    val approvedBy: Long? = null,
    val approvedAt: String? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val sourceBomRevisionId: Long? = null,
    val bomReleaseQuantity: String? = null,
    val bomRevisionVersion: Long? = null,
    val idempotencyKey: String? = null
)

@Serializable
data class DemandLineDto(
    val id: Long,
    val demandId: Long,
    val lineNo: Int,
    val nomenclatureId: Long,
    val quantity: String,
    val unit: String,
    val coveredQuantity: String = "0",
    val deficitQuantity: String? = null,
    val sourceBomRevisionId: Long? = null,
    val status: DemandLineStatus = DemandLineStatus.OPEN,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0
)

@Serializable
enum class DemandLineStatus {
    OPEN,
    PARTIALLY_COVERED,
    COVERED,
    CANCELLED
}

@Serializable
data class DemandDetailDto(
    val header: DemandHeaderDto,
    val lines: List<DemandLineDto> = emptyList()
)

@Serializable
data class DemandLineInput(
    val lineNo: Int,
    val nomenclatureId: Long,
    val quantity: String,
    val unit: String
)

@Serializable
data class CreateDemandRequest(
    val sourceType: DemandSourceType,
    val projectId: Long? = null,
    val stockpileNote: String? = null,
    val requiredDate: String? = null,
    val priority: DemandPriority? = DemandPriority.NORMAL,
    val idempotencyKey: String? = null,
    val lines: List<DemandLineInput> = emptyList()
)

@Serializable
data class CreateDemandFromBomRequest(
    val projectId: Long,
    val revisionId: Long,
    val releaseQuantity: String,
    val requiredDate: String? = null,
    val priority: DemandPriority? = DemandPriority.NORMAL,
    val idempotencyKey: String? = null
)

@Serializable
data class SubmitDemandRequest(
    val version: Long
)

@Serializable
data class ApproveDemandRequest(
    val version: Long
)

@Serializable
data class CancelDemandRequest(
    val version: Long,
    val reason: String
)

@Serializable
data class DemandCoverageLineDto(
    val lineNo: Int,
    val nomenclatureId: Long,
    val unit: String,
    val requiredQuantity: String,
    val stockAvailable: String,
    val projectReservations: String,
    val expectedReceipts: String,
    val deficit: String,
    val recommendedPurchase: String
)

@Serializable
data class DemandCoverageDto(
    val demandId: Long,
    val lines: List<DemandCoverageLineDto> = emptyList()
)
