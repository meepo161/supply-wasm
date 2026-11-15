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
