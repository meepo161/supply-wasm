package org.example.project

import org.example.project.dto.TaskArea
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskPriority
import org.example.project.dto.TaskStatus
import org.example.project.dto.TaskWorkType
import org.example.project.ui.tasks.TaskAreaFilter
import org.example.project.ui.tasks.filterByArea
import org.example.project.ui.tasks.filterByProject
import org.example.project.ui.tasks.filterByStatuses
import org.example.project.ui.tasks.filterMine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskListFilteringTest {

    private fun task(
        id: Long,
        area: TaskArea = TaskArea.PERSONAL,
        status: TaskStatus = TaskStatus.NEW,
        projectId: Long? = null,
        initiatorId: Long = 1,
        assigneeId: Long? = null,
        reviewerId: Long? = null,
        coexecutorIds: List<Long> = emptyList(),
        observerIds: List<Long> = emptyList()
    ) = TaskDto(
        id = id,
        number = "T-2026-$id",
        area = area,
        status = status,
        type = TaskWorkType.GENERAL,
        title = "Задача $id",
        initiatorId = initiatorId,
        assigneeId = assigneeId,
        reviewerId = reviewerId,
        coexecutorIds = coexecutorIds,
        observerIds = observerIds,
        projectId = projectId,
        createdBy = initiatorId
    )

    private val tasks = listOf(
        task(id = 1, area = TaskArea.PERSONAL, status = TaskStatus.IN_PROGRESS, assigneeId = 10),
        task(id = 2, area = TaskArea.PROJECT, status = TaskStatus.ASSIGNED, projectId = 100, assigneeId = 20),
        task(id = 3, area = TaskArea.PROJECT, status = TaskStatus.CLOSED, projectId = 200, reviewerId = 10),
        task(id = 4, area = TaskArea.DEPARTMENT, status = TaskStatus.ON_REVIEW, coexecutorIds = listOf(10, 30))
    )

    @Test
    fun `filter by project keeps only tasks of this project`() {
        val result = tasks.filterByProject(100)
        assertEquals(listOf(2L), result.map { it.id })
    }

    @Test
    fun `filter by project returns all tasks when project id is null`() {
        assertEquals(tasks, tasks.filterByProject(null))
    }

    @Test
    fun `filter by project is empty when project has no tasks`() {
        assertTrue(tasks.filterByProject(999).isEmpty())
    }

    @Test
    fun `area filter keeps only matching area`() {
        assertEquals(listOf(2L, 3L), tasks.filterByArea(TaskAreaFilter.PROJECT).map { it.id })
        assertEquals(listOf(1L), tasks.filterByArea(TaskAreaFilter.PERSONAL).map { it.id })
        assertEquals(listOf(4L), tasks.filterByArea(TaskAreaFilter.DEPARTMENT).map { it.id })
    }

    @Test
    fun `area filter ALL keeps every task`() {
        assertEquals(tasks.map { it.id }, tasks.filterByArea(TaskAreaFilter.ALL).map { it.id })
    }

    @Test
    fun `mine filter matches any participant role`() {
        val result = tasks.filterMine(10)
        assertEquals(listOf(1L, 3L, 4L), result.map { it.id })
    }

    @Test
    fun `mine filter keeps initiator`() {
        val own = task(id = 5, initiatorId = 42, assigneeId = 7)
        assertEquals(listOf(5L), listOf(own).filterMine(42).map { it.id })
    }

    @Test
    fun `mine filter without user keeps every task`() {
        assertEquals(tasks, tasks.filterMine(null))
    }

    @Test
    fun `status filter keeps only listed statuses and ALL keeps everything`() {
        val active = tasks.filterByStatuses(
            listOf(TaskStatus.IN_PROGRESS, TaskStatus.ASSIGNED, TaskStatus.NEW)
        )
        assertEquals(listOf(1L, 2L), active.map { it.id })
        assertEquals(tasks.map { it.id }, tasks.filterByStatuses(emptyList()).map { it.id })
    }

    @Test
    fun `filters compose: project tasks on review of current user`() {
        val result = tasks
            .filterByArea(TaskAreaFilter.PROJECT)
            .filterByStatuses(listOf(TaskStatus.CLOSED))
            .filterMine(10)
        assertEquals(listOf(3L), result.map { it.id })
    }

    @Test
    fun `text query finds a task by its number or title without changing its order`() {
        assertTrue(tasks.filterByQuery("2026-2").isEmpty())
        assertEquals(listOf(3L), tasks.filterByQuery("задача 3").map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), tasks.filterByQuery("задача").map { it.id })
    }
}
