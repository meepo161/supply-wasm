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
