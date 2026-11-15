package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
enum class ProjectStatus {
    DRAFT,
    PLANNED,
    IN_PROGRESS,
    ON_HOLD,
    COMPLETED,
    CLOSED,
    CANCELLED
}

@Serializable
enum class ProjectType {
    ORDER_DEVELOPMENT,
    REPEAT_PRODUCTION,
    MODERNIZATION,
    INTERNAL
}

@Serializable
enum class ProjectPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

@Serializable
data class ProjectMemberDto(
    val userId: Long,
    val fullName: String,
    val addedAt: String? = null,
    val addedBy: Long? = null
)

@Serializable
data class ProjectDto(
    val id: Long,
    val number: String,
    val name: String,
    val fullName: String? = null,
    val description: String? = null,
    val projectType: ProjectType? = null,
    val customer: String? = null,
    val ownerId: Long,
    val departmentId: Long? = null,
    val plannedStartAt: String? = null,
    val plannedEndAt: String? = null,
    val priority: ProjectPriority = ProjectPriority.NORMAL,
    val status: ProjectStatus = ProjectStatus.DRAFT,
    val openingReason: String? = null,
    val openedBy: Long? = null,
    val openedAt: String? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0,
    val members: List<ProjectMemberDto> = emptyList()
)

@Serializable
data class CreateProjectRequest(
    val name: String,
    val fullName: String? = null,
    val description: String? = null,
    val projectType: ProjectType? = null,
    val customer: String? = null,
    val ownerId: Long,
    val departmentId: Long? = null,
    val priority: ProjectPriority? = ProjectPriority.NORMAL,
    val plannedStartAt: String? = null,
    val plannedEndAt: String? = null
)

@Serializable
data class OpenProjectRequest(
    val openingReason: String,
    val version: Long
)

@Serializable
data class CloseProjectRequest(
    val version: Long,
    val reason: String? = null
)

@Serializable
data class AddProjectMemberRequest(
    val userId: Long
)

// ---------------------------------------------------------------------------
// Карточка проекта: вкладки «Обзор», «Задачи», «Потребности», «Документы»,
// «История» (ТЗ M.4, PRJ-13/PRJ-14). Этапы, риски и проценты готовности в
// первую поставку не входят (PRJ-08/PRJ-09, M.1/M.4), поэтому их здесь нет.
// ---------------------------------------------------------------------------

/**
 * Запись истории проекта: изменение статуса, участники, открытие и закрытие,
 * приёмка по заказу. Данные берутся из журнала аудита, а не пересчитываются на
 * клиенте, поэтому в карточке видно только то, что действительно произошло.
 */
@Serializable
data class ProjectHistoryEntryDto(
    val id: Long,
    val action: String,
    /** Название действия по-русски; сервер отдаёт готовую подпись. */
    val title: String,
    val userId: Long? = null,
    val userName: String? = null,
    val payload: Map<String, String> = emptyMap(),
    val createdAt: String
)

/**
 * Сводка по проекту для вкладки «Обзор»: только реальные счётчики связанных
 * записей. Проценты готовности не вычисляются — методика не утверждена.
 */
@Serializable
data class ProjectSummaryDto(
    val projectId: Long,
    val taskCount: Int,
    val openTaskCount: Int,
    val completedTaskCount: Int,
    val demandCount: Int,
    val documentCount: Int,
    val memberCount: Int,
    /** Пользователь состоит в проекте; неучастник видит только обзор без вкладок. */
    val isMember: Boolean,
    /** Может добавлять и удалять участников. */
    val canManageMembers: Boolean
)

// ---------------------------------------------------------------------------
// Планирование проекта: этапы, контрольные точки и риски (5.3, 5.5 ТЗ;
// PRJ-08, PRJ-09, PRJ-10).
//
// Правила, общие для всех трёх сущностей:
//  - владелец проекта и держатель `projects:manage_plan` создают и меняют
//    элементы плана;
//  - участник проекта видит план, остальным нужен `projects:read_any`;
//  - изменения версионируются: запись с устаревшей версией отклоняется (409);
//  - проценты выполнения не хранятся и не считаются: показывается статус.
// ---------------------------------------------------------------------------

@Serializable
enum class ProjectStageStatus {
    PLANNED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}

@Serializable
enum class ProjectMilestoneStatus {
    PLANNED,
    REACHED,
    MISSED,
    CANCELLED
}

@Serializable
enum class ProjectRiskCategory {
    TECHNICAL,
    SUPPLY,
    SCHEDULE,
    FINANCIAL,
    ORGANIZATIONAL,
    OTHER
}

/** Уровень вероятности, влияния и критичности задаёт ответственный. */
@Serializable
enum class ProjectRiskLevel {
    LOW,
    MEDIUM,
    HIGH
}

@Serializable
enum class ProjectRiskCriticality {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

@Serializable
enum class ProjectRiskStatus {
    OPEN,
    MITIGATING,
    CLOSED,
    ACCEPTED
}

@Serializable
data class ProjectStageDto(
    val id: Long,
    val projectId: Long,
    val name: String,
    val description: String? = null,
    val sequence: Int,
    val status: ProjectStageStatus = ProjectStageStatus.PLANNED,
    val plannedStartAt: String? = null,
    val plannedEndAt: String? = null,
    val actualStartAt: String? = null,
    val actualEndAt: String? = null,
    val responsibleId: Long? = null,
    val completionCriteria: String? = null,
    val cancelReason: String? = null,
    val createdAt: String,
    val createdBy: Long,
    val version: Long = 0,
    /**
     * Контрольные точки этапа. Счётчика связанных задач здесь нет: привязка
     * задачи к этапу появится вместе с зависимостями (PRJ-12), и до этого
     * любое число было бы выдуманным.
     */
    val milestones: List<ProjectMilestoneDto> = emptyList()
)

@Serializable
data class CreateProjectStageRequest(
    val name: String,
    val description: String? = null,
    val sequence: Int? = null,
    val plannedStartAt: String? = null,
    val plannedEndAt: String? = null,
    val responsibleId: Long? = null,
    val completionCriteria: String? = null
)

/** Изменение этапа: незаданные поля остаются как были. */
@Serializable
data class UpdateProjectStageRequest(
    val name: String? = null,
    val description: String? = null,
    val sequence: Int? = null,
    val plannedStartAt: String? = null,
    val plannedEndAt: String? = null,
    val responsibleId: Long? = null,
    val completionCriteria: String? = null,
    val version: Long
)

@Serializable
data class ProjectStageCommandRequest(
    val version: Long,
    /** Обязателен для `CANCEL`; для остальных команд необязателен. */
    val note: String? = null
)

@Serializable
data class ProjectMilestoneDto(
    val id: Long,
    val projectId: Long,
    val stageId: Long? = null,
    val name: String,
    val description: String? = null,
    val plannedAt: String? = null,
    val actualAt: String? = null,
    val status: ProjectMilestoneStatus = ProjectMilestoneStatus.PLANNED,
    val responsibleId: Long? = null,
    val note: String? = null,
    val createdAt: String,
    val createdBy: Long,
    val version: Long = 0
)

@Serializable
data class CreateProjectMilestoneRequest(
    val name: String,
    val stageId: Long? = null,
    val description: String? = null,
    val plannedAt: String? = null,
    val responsibleId: Long? = null
)

@Serializable
data class UpdateProjectMilestoneRequest(
    val name: String? = null,
    val stageId: Long? = null,
    val description: String? = null,
    val plannedAt: String? = null,
    val responsibleId: Long? = null,
    val note: String? = null,
    val version: Long
)

/** Фактическая дата устанавливается командой, а не редактированием. */
@Serializable
data class ProjectMilestoneCommandRequest(
    val version: Long,
    val note: String? = null
)

@Serializable
data class ProjectRiskDto(
    val id: Long,
    val projectId: Long,
    val title: String,
    val description: String? = null,
    val category: ProjectRiskCategory = ProjectRiskCategory.OTHER,
    val probability: ProjectRiskLevel,
    val impact: ProjectRiskLevel,
    val criticality: ProjectRiskCriticality,
    val status: ProjectRiskStatus = ProjectRiskStatus.OPEN,
    val responsibleId: Long? = null,
    val responsePlan: String? = null,
    val dueDate: String? = null,
    val stageId: Long? = null,
    val taskId: Long? = null,
    val outcome: String? = null,
    val closedAt: String? = null,
    val closedBy: Long? = null,
    val createdAt: String,
    val createdBy: Long,
    val version: Long = 0
)

@Serializable
data class CreateProjectRiskRequest(
    val title: String,
    val description: String? = null,
    val category: ProjectRiskCategory = ProjectRiskCategory.OTHER,
    val probability: ProjectRiskLevel,
    val impact: ProjectRiskLevel,
    val criticality: ProjectRiskCriticality,
    val responsibleId: Long? = null,
    val responsePlan: String? = null,
    val dueDate: String? = null,
    val stageId: Long? = null,
    val taskId: Long? = null
)

@Serializable
data class UpdateProjectRiskRequest(
    val title: String? = null,
    val description: String? = null,
    val category: ProjectRiskCategory? = null,
    val probability: ProjectRiskLevel? = null,
    val impact: ProjectRiskLevel? = null,
    val criticality: ProjectRiskCriticality? = null,
    val responsibleId: Long? = null,
    val responsePlan: String? = null,
    val dueDate: String? = null,
    val stageId: Long? = null,
    val taskId: Long? = null,
    val version: Long
)

/**
 * Закрытие риска требует исхода: без описания результата нельзя отличить
 * «риск снят» от «риск реализовался».
 */
@Serializable
data class CloseProjectRiskRequest(
    val version: Long,
    val outcome: String,
    /** `ACCEPTED` — риск принят без устранения; по умолчанию `CLOSED`. */
    val status: ProjectRiskStatus = ProjectRiskStatus.CLOSED
)

/** Сводка плана для вкладки «Обзор»: реальные счётчики, без процентов. */
@Serializable
data class ProjectPlanSummaryDto(
    val projectId: Long,
    val stageCount: Int,
    val completedStageCount: Int,
    val currentStageId: Long? = null,
    val currentStageName: String? = null,
    val milestoneCount: Int,
    val reachedMilestoneCount: Int,
    val nextMilestoneId: Long? = null,
    val nextMilestoneName: String? = null,
    val nextMilestonePlannedAt: String? = null,
    val openRiskCount: Int,
    val criticalRiskCount: Int
)
