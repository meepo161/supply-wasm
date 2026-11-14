package org.example.project

import org.example.project.dto.ApprovalAssigneeDto
import org.example.project.dto.ApprovalAssigneeType
import org.example.project.dto.ApprovalDecision
import org.example.project.dto.ApprovalDecisionDto
import org.example.project.dto.ApprovalDecisionPolicy
import org.example.project.dto.ApprovalRouteDto
import org.example.project.dto.ApprovalRouteStatus
import org.example.project.dto.ApprovalStageDto
import org.example.project.dto.ApprovalStageStatus
import org.example.project.ui.approvals.activeRoute
import org.example.project.ui.approvals.canUserDecideCurrentStage
import org.example.project.ui.approvals.currentStage
import org.example.project.ui.approvals.isTerminal
import org.example.project.ui.approvals.requiresComment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApprovalUiLogicTest {

    @Test
    fun commentRequiredForRejectAndReturnOnly() {
        assertFalse(ApprovalDecision.APPROVE.requiresComment())
        assertTrue(ApprovalDecision.REJECT.requiresComment())
        assertTrue(ApprovalDecision.RETURN.requiresComment())
    }

    @Test
    fun onlyActiveRouteIsNonTerminal() {
        assertFalse(ApprovalRouteStatus.ACTIVE.isTerminal())
        assertTrue(ApprovalRouteStatus.COMPLETED.isTerminal())
        assertTrue(ApprovalRouteStatus.REJECTED.isTerminal())
        assertTrue(ApprovalRouteStatus.RETURNED.isTerminal())
        assertTrue(ApprovalRouteStatus.CANCELLED.isTerminal())
    }

    @Test
    fun activeRouteSelectedFromList() {
        val active = route(id = 1, status = ApprovalRouteStatus.ACTIVE)
        val completed = route(id = 2, status = ApprovalRouteStatus.COMPLETED)
        assertEquals(1L, listOf(completed, active).activeRoute()?.id)
        assertNull(listOf(completed).activeRoute())
    }

    @Test
    fun currentStageResolvedByStageNo() {
        val r = route(
            id = 1,
            status = ApprovalRouteStatus.ACTIVE,
            currentStageNo = 2,
            stages = listOf(stage(id = 10, stageNo = 1), stage(id = 11, stageNo = 2))
        )
        assertEquals(11L, r.currentStage()?.id)
        assertNull(r.copy(currentStageNo = 99).currentStage())
    }

    @Test
    fun userAssigneeOfCurrentStageCanDecide() {
        val r = route(
            id = 1,
            status = ApprovalRouteStatus.ACTIVE,
            stages = listOf(stage(id = 10, stageNo = 1, assignees = listOf(userAssignee(7))))
        )
        assertTrue(canUserDecideCurrentStage(r, userId = 7))
        assertFalse(canUserDecideCurrentStage(r, userId = 8))
    }

    @Test
    fun roleAssigneeShownOptimisticallyServerStaysSourceOfTruth() {
        val r = route(
            id = 1,
            status = ApprovalRouteStatus.ACTIVE,
            stages = listOf(
                stage(
                    id = 10,
                    stageNo = 1,
                    assignees = listOf(
                        ApprovalAssigneeDto(id = 2, type = ApprovalAssigneeType.ROLE, roleId = 3, roleName = "Руководители")
                    )
                )
            )
        )
        assertTrue(canUserDecideCurrentStage(r, userId = 42))
    }

    @Test
    fun decisionHiddenAfterOwnDecisionAndOnInactiveStages() {
        val stageActive = stage(id = 10, stageNo = 1, assignees = listOf(userAssignee(7)))
        val decided = ApprovalDecisionDto(
            id = 1, stageId = 10, stageNo = 1, decidedBy = 7, decision = ApprovalDecision.APPROVE
        )
        assertFalse(canUserDecideCurrentStage(
            route(id = 1, status = ApprovalRouteStatus.ACTIVE, stages = listOf(stageActive), decisions = listOf(decided)),
            userId = 7
        ))
        assertFalse(canUserDecideCurrentStage(
            route(
                id = 1,
                status = ApprovalRouteStatus.ACTIVE,
                stages = listOf(stageActive.copy(status = ApprovalStageStatus.APPROVED))
            ),
            userId = 7
        ))
        assertFalse(canUserDecideCurrentStage(
            route(id = 1, status = ApprovalRouteStatus.COMPLETED, stages = listOf(stageActive)),
            userId = 7
        ))
    }

    private fun userAssignee(userId: Long) =
        ApprovalAssigneeDto(id = userId, type = ApprovalAssigneeType.USER, userId = userId, userFullName = "User $userId")

    private fun stage(
        id: Long,
        stageNo: Int,
        status: ApprovalStageStatus = ApprovalStageStatus.ACTIVE,
        assignees: List<ApprovalAssigneeDto> = emptyList()
    ) = ApprovalStageDto(
        id = id,
        stageNo = stageNo,
        name = "Этап $stageNo",
        decisionPolicy = ApprovalDecisionPolicy.ALL,
        status = status,
        assignees = assignees
    )

    private fun route(
        id: Long,
        status: ApprovalRouteStatus,
        currentStageNo: Int = 1,
        stages: List<ApprovalStageDto> = listOf(stage(id = 10, stageNo = 1)),
        decisions: List<ApprovalDecisionDto> = emptyList()
    ) = ApprovalRouteDto(
        id = id,
        templateId = 1,
        targetType = "DEMAND",
        targetId = 100,
        targetVersion = 0,
        status = status,
        currentStageNo = currentStageNo,
        stages = stages,
        decisions = decisions,
        startedBy = 1
    )
}
