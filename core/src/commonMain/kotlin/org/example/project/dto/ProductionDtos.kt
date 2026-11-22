package org.example.project.dto

import kotlinx.serialization.Serializable

// Производственные заказы (§15 ТЗ). Ревизия BOM у заказа должна быть
// APPROVED или ACTIVE — проверяется сервером и триггером V20.

@Serializable
enum class ProductionOrderStatus {
    DRAFT,
    PLANNED,
    IN_PROGRESS,
    ON_HOLD,
    COMPLETED,
    CLOSED,
    CANCELLED
}

@Serializable
enum class ProductionStageType {
    PREPARATION,
    KITTING,
    ASSEMBLY,
    MOUNTING,
    SETUP,
    TESTING,
    CONTROL,
    REMARK_FIX,
    ACCEPTANCE,
    COMPLETION
}

@Serializable
enum class ProductionStageStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    SKIPPED
}

@Serializable
enum class ProductionStageAssigneeRole {
    EXECUTOR,
    CONTROLLER,
    REVIEWER
}

@Serializable
data class CreateProductionOrderRequest(
    val projectId: Long,
    val productModelId: Long,
    val bomRevisionId: Long,
    val plannedQuantity: Int,
    val plannedStartAt: String? = null,
    val plannedEndAt: String? = null,
    val responsibleId: Long,
    val departmentId: Long? = null,
    val priority: String = "NORMAL",
    val stages: List<CreateProductionStageInput> = emptyList()
)

@Serializable
data class CreateProductionStageInput(
    val stageType: ProductionStageType,
    val name: String,
    val sequence: Int,
    val responsibleId: Long? = null,
    val plannedStartAt: String? = null,
    val plannedEndAt: String? = null
)

// Перевод заказа по статусам: plan / start / hold / resume / close / cancel.
@Serializable
data class ProductionOrderActionRequest(
    val version: Long,
    val note: String? = null
)

// Завершение выпуска: фактическое количество и серийные номера экземпляров
// (по одному на единицу). Карточки экземпляров создаются только здесь.
@Serializable
data class CompleteProductionOrderRequest(
    val version: Long,
    val actualQuantity: Int,
    val serialNumbers: List<String> = emptyList()
)

@Serializable
data class CancelProductionOrderRequest(
    val version: Long,
    val reason: String
)

@Serializable
data class ProductionStageActionRequest(
    val version: Long,
    val reason: String? = null
)

@Serializable
data class ProductionStageAssigneeDto(
    val userId: Long,
    val role: ProductionStageAssigneeRole
)

@Serializable
data class ProductionOrderStageDto(
    val id: Long,
    val orderId: Long,
    val stageType: ProductionStageType,
    val name: String,
    val sequence: Int,
    val status: ProductionStageStatus,
    val plannedStartAt: String? = null,
    val plannedEndAt: String? = null,
    val actualStartAt: String? = null,
    val actualEndAt: String? = null,
    val responsibleId: Long? = null,
    val requiredMaterialsOk: Boolean = false,
    val requiredDocumentsOk: Boolean = false,
    val skipReason: String? = null,
    val notes: String? = null,
    val version: Long = 0,
    val assignees: List<ProductionStageAssigneeDto> = emptyList()
)

@Serializable
data class ProductionOrderDto(
    val id: Long,
    val number: String,
    val projectId: Long,
    val productModelId: Long,
    val bomRevisionId: Long,
    val status: ProductionOrderStatus = ProductionOrderStatus.DRAFT,
    val plannedQuantity: Int,
    val actualQuantity: Int = 0,
    val plannedStartAt: String? = null,
    val plannedEndAt: String? = null,
    val actualStartAt: String? = null,
    val actualEndAt: String? = null,
    val responsibleId: Long,
    val departmentId: Long? = null,
    val priority: String = "NORMAL",
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val stages: List<ProductionOrderStageDto> = emptyList()
)

// Карточка физического экземпляра, созданного при завершении выпуска.
@Serializable
data class ProductInstanceDto(
    val id: Long,
    val orderId: Long,
    val projectId: Long,
    val productModelId: Long,
    val bomRevisionId: Long,
    val serialNumber: String,
    val status: String,
    val manufacturedAt: String? = null
)

@Serializable
data class ProductInstanceComponentDto(
    val id: Long,
    val nomenclatureId: Long,
    val nomenclatureCode: String? = null,
    val nomenclatureName: String? = null,
    val quantity: String,
    val lotId: Long? = null,
    val serialId: Long? = null,
    val substituteReason: String? = null,
    val installedAt: String,
    val installedById: Long,
    val installedByName: String? = null
)

@Serializable
data class ProductInstanceDocumentDto(
    val id: Long,
    val documentId: Long? = null,
    val documentTitle: String? = null,
    val documentVersionId: Long,
    val documentVersionNumber: Int? = null,
    val docKind: String,
    val createdAt: String,
    val createdById: Long,
    val createdByName: String? = null
)

@Serializable
data class ProductInstanceSoftwareDto(
    val id: Long,
    val name: String,
    val version: String,
    val build: String? = null,
    val installedAt: String,
    val installedById: Long,
    val installedByName: String? = null
)

@Serializable
data class ProductInstanceTestDto(
    val id: Long,
    val result: String,
    val notes: String? = null,
    val protocolDocumentVersionId: Long? = null,
    val performedAt: String,
    val performedById: Long,
    val performedByName: String? = null,
    val technicalDecision: String = "PENDING",
    val technicalDecisionById: Long? = null,
    val technicalDecisionAt: String? = null
)

@Serializable
data class ProductInstanceRemarkDto(
    val id: Long,
    val testId: Long? = null,
    val description: String,
    val severity: String,
    val blocksShipment: Boolean = true,
    val status: String,
    val createdById: Long,
    val createdByName: String? = null,
    val allowedById: Long? = null,
    val allowedAt: String? = null,
    val correctiveTaskId: Long? = null,
    val createdAt: String
)

@Serializable
data class ProductInstanceDetailDto(
    val id: Long,
    val orderId: Long,
    val orderNumber: String? = null,
    val projectId: Long,
    val productModelId: Long,
    val productModelCode: String? = null,
    val productModelName: String? = null,
    val bomRevisionId: Long,
    val serialNumber: String,
    val status: String,
    val manufacturedAt: String? = null,
    val acceptedAt: String? = null,
    val shippedAt: String? = null,
    val version: Long = 0,
    val components: List<ProductInstanceComponentDto> = emptyList(),
    val documents: List<ProductInstanceDocumentDto> = emptyList(),
    val software: List<ProductInstanceSoftwareDto> = emptyList(),
    val tests: List<ProductInstanceTestDto> = emptyList(),
    val remarks: List<ProductInstanceRemarkDto> = emptyList()
)

@Serializable
data class RecordInstanceTestRequest(
    val result: String,
    val notes: String? = null,
    val protocolDocumentVersionId: Long? = null,
    val technicalDecision: String = "PENDING"
)

@Serializable
data class RecordInstanceRemarkRequest(
    val description: String,
    val severity: String = "MINOR",
    val blocksShipment: Boolean = true,
    val testId: Long? = null
)

@Serializable
data class AddInstanceSoftwareRequest(
    val name: String,
    val version: String,
    val build: String? = null
)

@Serializable
data class AddInstanceComponentRequest(
    val nomenclatureId: Long,
    val quantity: String = "1",
    val lotId: Long? = null,
    val serialId: Long? = null,
    val substituteReason: String? = null
)
