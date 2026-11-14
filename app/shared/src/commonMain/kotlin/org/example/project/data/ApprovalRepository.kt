package org.example.project.data

import org.example.project.dto.ApprovalRouteDto
import org.example.project.dto.ApprovalTemplateDto
import org.example.project.dto.CancelApprovalRouteRequest
import org.example.project.dto.StartApprovalRouteRequest
import org.example.project.dto.SubmitApprovalDecisionRequest

interface ApprovalRepository {
    suspend fun listTemplates(targetType: String): ApiResult<List<ApprovalTemplateDto>>
    suspend fun listRoutes(targetType: String, targetId: Long): ApiResult<List<ApprovalRouteDto>>
    suspend fun getRoute(id: Long): ApiResult<ApprovalRouteDto>
    suspend fun startRoute(request: StartApprovalRouteRequest): ApiResult<ApprovalRouteDto>
    suspend fun submitDecision(routeId: Long, request: SubmitApprovalDecisionRequest): ApiResult<ApprovalRouteDto>
    suspend fun cancelRoute(routeId: Long, request: CancelApprovalRouteRequest): ApiResult<ApprovalRouteDto>
}
