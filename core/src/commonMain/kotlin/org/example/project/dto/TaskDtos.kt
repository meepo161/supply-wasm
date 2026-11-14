package org.example.project.dto

import kotlinx.serialization.Serializable

@Serializable
enum class TaskArea {
    PERSONAL,
    PROJECT,
    DEPARTMENT
}

@Serializable
enum class TaskStatus {
    NEW,
    ASSIGNED,
    IN_PROGRESS,
    BLOCKED,
    ON_REVIEW,
    REWORK,
    CLOSED,
    CANCELLED
}

@Serializable
enum class TaskPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

@Serializable
enum class TaskWorkType {
    GENERAL,
    REVIEWED
}

@Serializable
enum class TaskParticipantRole {
    INITIATOR,
    ASSIGNEE,
    COEXECUTOR,
    OBSERVER,
    REVIEWER
}

@Serializable
enum class ReviewDecision {
    ACCEPT,
    RETURN
}

@Serializable
data class TaskDto(
    val id: Long,
    val number: String,
    val area: TaskArea,
    val status: TaskStatus,
    val type: TaskWorkType,
    val title: String,
    val description: String? = null,
    val acceptanceCriteria: String? = null,
    val result: String? = null,
    val initiatorId: Long,
    val assigneeId: Long? = null,
    val reviewerId: Long? = null,
    val coexecutorIds: List<Long> = emptyList(),
    val observerIds: List<Long> = emptyList(),
    val projectId: Long? = null,
    val departmentId: Long? = null,
    val dueDate: String? = null,
    val plannedStartAt: String? = null,
    val priority: TaskPriority = TaskPriority.NORMAL,
    val blockedReason: String? = null,
    val blockedBy: Long? = null,
    val blockedAt: String? = null,
    val unblockResponsibleId: Long? = null,
    val closedAt: String? = null,
    val createdAt: String? = null,
    val createdBy: Long,
    val version: Long = 0
)

@Serializable
data class CreateTaskRequest(
    val title: String,
    val description: String? = null,
    val area: TaskArea,
    val type: TaskWorkType,
    val projectId: Long? = null,
    val departmentId: Long? = null,
    val assigneeId: Long? = null,
    val reviewerId: Long? = null,
    val coexecutorIds: List<Long> = emptyList(),
    val observerIds: List<Long> = emptyList(),
    val dueDate: String? = null,
    val plannedStartAt: String? = null,
    val priority: TaskPriority? = TaskPriority.NORMAL,
    val acceptanceCriteria: String? = null
)

@Serializable
data class AssignTaskRequest(
    val assigneeId: Long,
    val version: Long
)

@Serializable
data class TaskCommandRequest(
    val version: Long
)

@Serializable
data class SubmitResultRequest(
    val result: String,
    val version: Long
)

@Serializable
data class ReviewTaskRequest(
    val decision: ReviewDecision,
    val reason: String? = null,
    val version: Long
)

@Serializable
data class BlockTaskRequest(
    val reason: String,
    val unblockResponsibleId: Long? = null,
    val version: Long
)

@Serializable
data class ReopenTaskRequest(
    val reason: String,
    val version: Long
)

@Serializable
data class CancelTaskRequest(
    val reason: String,
    val version: Long
)
