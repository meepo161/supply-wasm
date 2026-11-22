package org.example.project.dto

import kotlinx.serialization.Serializable

// --- PRJ-12: Зависимости задач ---

@Serializable
enum class TaskDependencyType {
    FINISH_TO_START,
    START_TO_START,
    FINISH_TO_FINISH
}

@Serializable
data class TaskDependencyDto(
    val id: Long,
    val predecessorTaskId: Long,
    val predecessorTitle: String = "",
    val successorTaskId: Long,
    val successorTitle: String = "",
    val dependencyType: TaskDependencyType = TaskDependencyType.FINISH_TO_START,
    val createdBy: Long,
    val createdAt: String? = null
)

@Serializable
data class CreateTaskDependencyRequest(
    val predecessorTaskId: Long,
    val successorTaskId: Long,
    val dependencyType: TaskDependencyType = TaskDependencyType.FINISH_TO_START
)

// --- CRM-01, CRM-02: Контрагенты и контактные лица ---

@Serializable
enum class CounterpartyKind {
    CUSTOMER,
    PARTNER,
    SUPPLIER,
    OTHER
}

@Serializable
data class CounterpartyContactDto(
    val id: Long,
    val counterpartyId: Long,
    val fullName: String,
    val position: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val isPrimary: Boolean = false,
    val notes: String? = null
)

@Serializable
data class CounterpartyDto(
    val id: Long,
    val name: String,
    val kind: CounterpartyKind = CounterpartyKind.CUSTOMER,
    val inn: String? = null,
    val kpp: String? = null,
    val ogrn: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val archived: Boolean = false,
    val createdBy: Long,
    val createdAt: String? = null,
    val version: Long = 0,
    val contacts: List<CounterpartyContactDto> = emptyList()
)

@Serializable
data class CreateCounterpartyRequest(
    val name: String,
    val kind: CounterpartyKind = CounterpartyKind.CUSTOMER,
    val inn: String? = null,
    val kpp: String? = null,
    val ogrn: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val notes: String? = null
)

@Serializable
data class UpdateCounterpartyRequest(
    val name: String,
    val kind: CounterpartyKind,
    val inn: String? = null,
    val kpp: String? = null,
    val ogrn: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val notes: String? = null,
    val archived: Boolean = false,
    val version: Long
)

@Serializable
data class CreateCounterpartyContactRequest(
    val fullName: String,
    val position: String? = null,
    val email: String? = null,
    val phone: String? = null,
    val isPrimary: Boolean = false,
    val notes: String? = null
)

// --- SUP-10, SUP-20, SUP-21: Объединение потребностей и планирование закупок ---

@Serializable
data class MergeDemandsRequest(
    val sourceDemandIds: List<Long>,
    val targetTitle: String? = null,
    val reason: String? = null
)

@Serializable
data class ProcurementPlanItemDto(
    val nomenclatureId: Long,
    val code: String,
    val name: String,
    val unit: String,
    val requiredQty: String,
    val availableQty: String,
    val inTransitQty: String,
    val openPoQty: String,
    val minStock: String,
    val safetyStock: String,
    val minOrderQty: String,
    val orderMultiple: String,
    val leadTimeDays: Int,
    val netDeficitQty: String,
    val recommendedOrderQty: String
)

// --- DMS-07, DMS-08: Документы экземпляров и шаблоны документов ---

@Serializable
data class InstanceDocumentDto(
    val id: Long,
    val instanceId: Long,
    val documentVersionId: Long,
    val documentId: Long = 0,
    val documentTitle: String = "",
    val versionNumber: Int = 1,
    val docKind: String = "OTHER",
    val createdBy: Long,
    val createdAt: String? = null,
    val version: Long = 0
)

@Serializable
data class AttachInstanceDocumentRequest(
    val documentVersionId: Long,
    val docKind: String = "OTHER"
)

// --- BOM-14: Замена материала в заказе без изменения BOM ---

@Serializable
data class SubstituteOrderMaterialRequest(
    val newNomenclatureId: Long,
    val reason: String? = null,
    val version: Long
)

// --- ANA-08: Импорт данных из CSV ---

@Serializable
data class CsvImportRequest(
    val csvContent: String
)

@Serializable
data class CsvImportResultDto(
    val importedCount: Int,
    val skippedCount: Int,
    val errors: List<String> = emptyList()
)

// --- IAM-13: Делегирование полномочий ---

@Serializable
data class DelegationDto(
    val id: Long,
    val delegatorId: Long,
    val delegatorName: String = "",
    val delegateId: Long,
    val delegateName: String = "",
    val startsAt: String,
    val endsAt: String,
    val reason: String? = null,
    val active: Boolean = true
)

@Serializable
data class CreateDelegationRequest(
    val delegateId: Long,
    val endsAt: String,
    val reason: String? = null
)

// --- TASK-33: Связи задачи с объектами ---

@Serializable
data class TaskObjectLinkDto(
    val id: Long,
    val taskId: Long,
    val objectType: String,
    val objectId: Long,
    val note: String? = null,
    val createdBy: Long,
    val createdAt: String? = null
)

@Serializable
data class CreateTaskObjectLinkRequest(
    val objectType: String,
    val objectId: Long,
    val note: String? = null
)

// --- PRJ-11: Запросы на изменение проекта ---

@Serializable
data class ProjectChangeRequestDto(
    val id: Long,
    val projectId: Long,
    val title: String,
    val reason: String,
    val newEndDate: String? = null,
    val status: String = "OPEN",
    val requestedBy: Long,
    val createdAt: String? = null
)

@Serializable
data class CreateProjectChangeRequest(
    val title: String,
    val reason: String,
    val newEndDate: String? = null
)

// --- TASK-27: Массовые действия над задачами ---

@Serializable
data class BulkTaskActionRequest(
    val taskIds: List<Long>,
    val action: String,
    val reason: String? = null
)

// --- HOME-06..08 / INV-08: Панель руководителя и очереди подразделений ---

@Serializable
data class DepartmentQueueDto(
    val departmentId: Long,
    val departmentName: String,
    val openTasks: Int,
    val overdueTasks: Int,
    val inProgressOrders: Int
)
