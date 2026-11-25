package org.example.project

import org.example.project.dto.DemandStatus
import org.example.project.dto.ProjectDto
import org.example.project.dto.ProjectRiskCriticality
import org.example.project.dto.ProjectRiskDto
import org.example.project.dto.ProjectRiskLevel
import org.example.project.dto.ProjectRiskStatus
import org.example.project.dto.ProjectStatus
import org.example.project.dto.TaskArea
import org.example.project.dto.TaskDto
import org.example.project.dto.TaskPriority
import org.example.project.dto.TaskStatus
import org.example.project.dto.TaskWorkType
import org.example.project.ui.demand.DEFICIT_AT_RISK_DAYS
import org.example.project.ui.demand.demandOpenDays
import org.example.project.ui.demand.isDemandAtRisk
import org.example.project.ui.projects.ProjectTab
import org.example.project.ui.projects.ProjectsUiState
import org.example.project.ui.projects.buildProjectNextAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class Supply2ImprovementsTest {

    @Test
    fun demandAgingFlagsOpenDemandsAtOrAboveThreshold() {
        val openDays = demandOpenDays("2026-11-10T09:00:00Z", todayIso = "2026-11-20")
        assertEquals(10L, openDays)
        assertTrue(isDemandAtRisk(DemandStatus.APPROVED, openDays))
        assertTrue(isDemandAtRisk(DemandStatus.IN_PROCUREMENT, DEFICIT_AT_RISK_DAYS))
        assertFalse(isDemandAtRisk(DemandStatus.APPROVED, DEFICIT_AT_RISK_DAYS - 1))
        assertFalse(isDemandAtRisk(DemandStatus.COVERED, 30L))
        assertFalse(isDemandAtRisk(DemandStatus.CANCELLED, 30L))
    }

    @Test
    fun projectNextActionPrioritizesRisksThenDemandsThenTasks() {
        val activeProject = ProjectDto(
            id = 1L,
            number = "PRJ-001",
            name = "Испытательный стенд",
            status = ProjectStatus.IN_PROGRESS,
            ownerId = 1L,
            createdBy = 1L
        )
        val openRisk = ProjectRiskDto(
            id = 10L,
            projectId = 1L,
            title = "Задержка поставки",
            probability = ProjectRiskLevel.MEDIUM,
            impact = ProjectRiskLevel.HIGH,
            criticality = ProjectRiskCriticality.HIGH,
            status = ProjectRiskStatus.OPEN,
            createdBy = 1L,
            createdAt = "2026-11-20T10:00:00Z"
        )
        val withRisk = buildProjectNextAction(
            activeProject,
            emptyList(),
            ProjectsUiState(risks = listOf(openRisk))
        )
        assertEquals(ProjectTab.RISKS, withRisk.targetTab)

        val noTasks = buildProjectNextAction(activeProject, emptyList(), null)
        assertEquals(ProjectTab.TASKS, noTasks.targetTab)

        val closedProject = activeProject.copy(status = ProjectStatus.CLOSED)
        val closedAction = buildProjectNextAction(closedProject, emptyList(), null)
        assertNull(closedAction.targetTab)
        assertNull(closedAction.buttonText)
    }

    @Test
    fun projectNextActionRecognizesAllTasksDone() {
        val activeProject = ProjectDto(
            id = 2L,
            number = "PRJ-002",
            name = "Стенд-2",
            status = ProjectStatus.IN_PROGRESS,
            ownerId = 1L,
            createdBy = 1L
        )
        val doneTask = TaskDto(
            id = 5L,
            number = "TSK-5",
            area = TaskArea.PROJECT,
            status = TaskStatus.CLOSED,
            type = TaskWorkType.GENERAL,
            title = "Сборка",
            priority = TaskPriority.NORMAL,
            initiatorId = 1L,
            createdBy = 1L
        )
        val action = buildProjectNextAction(activeProject, listOf(doneTask), null)
        assertEquals(ProjectTab.OVERVIEW, action.targetTab)
    }
}
