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
