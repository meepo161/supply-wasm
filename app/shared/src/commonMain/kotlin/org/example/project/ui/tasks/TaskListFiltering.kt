package org.example.project.ui.tasks

import org.example.project.dto.TaskArea
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskStatus

/** Статусные фильтры общего списка задач; пустой список статусов означает «все». */
enum class TaskStatusFilter(val label: String, val statuses: List<TaskStatus>) {
    ALL("Все", emptyList()),
    ACTIVE("В работе", listOf(TaskStatus.IN_PROGRESS, TaskStatus.ASSIGNED, TaskStatus.NEW)),
    REVIEW("На приёмке", listOf(TaskStatus.ON_REVIEW)),
    BLOCKED("Блок", listOf(TaskStatus.BLOCKED, TaskStatus.REWORK)),
    DONE("Завершённые", listOf(TaskStatus.CLOSED, TaskStatus.CANCELLED))
}

/** Фильтр по области задачи: личная, проектная или подразделенческая. */
enum class TaskAreaFilter(val label: String, val area: TaskArea?) {
    ALL("Все области", null),
    PERSONAL("Личные", TaskArea.PERSONAL),
    PROJECT("Проектные", TaskArea.PROJECT),
    DEPARTMENT("Подразделения", TaskArea.DEPARTMENT)
}

fun List<TaskDto>.filterByStatuses(statuses: List<TaskStatus>): List<TaskDto> =
    if (statuses.isEmpty()) this else filter { it.status in statuses }

fun List<TaskDto>.filterByArea(filter: TaskAreaFilter): List<TaskDto> =
    if (filter.area == null) this else filter { it.area == filter.area }

fun List<TaskDto>.filterByProject(projectId: Long?): List<TaskDto> =
    if (projectId == null) this else filter { it.projectId == projectId }

/** Быстрый поиск по уже загруженной и доступной пользователю выборке задач. */
fun List<TaskDto>.filterByQuery(query: String): List<TaskDto> {
    val normalized = query.trim()
    if (normalized.isEmpty()) return this
    return filter { task -> task.title.contains(normalized, ignoreCase = true) }
}

fun List<TaskDto>.filterMine(currentUserId: Long?): List<TaskDto> =
    if (currentUserId == null) this else filter { it.involves(currentUserId) }

/** Пользователь участвует в задаче в любой роли: инициатор, исполнитель, проверяющий, соисполнитель, наблюдатель. */
fun TaskDto.involves(userId: Long): Boolean =
    initiatorId == userId || assigneeId == userId || reviewerId == userId ||
        userId in coexecutorIds || userId in observerIds
