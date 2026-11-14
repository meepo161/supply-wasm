package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.*
import org.example.project.exception.ValidationException
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.ApprovalService

fun Route.approvalRoutes(approvalService: ApprovalService) {
    route("/api/approvals") {
        install(RequireAuthPlugin) {}

        post("/templates") {
            val request = call.receive<CreateApprovalTemplateRequest>()
            val template = approvalService.createTemplate(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(template))
        }

        get("/templates") {
            val targetType = call.request.queryParameters["targetType"]
            call.respond(HttpStatusCode.OK, ApiResponse.success(approvalService.listTemplates(targetType, call.currentUser())))
        }

        get("/templates/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid template id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(approvalService.getTemplate(id, call.currentUser())))
        }

        post("/routes") {
            val request = call.receive<StartApprovalRouteRequest>()
            val route = approvalService.startRoute(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(route))
        }

        get("/routes") {
            val targetType = call.request.queryParameters["targetType"]
                ?: throw ValidationException("targetType is required", mapOf("targetType" to "required"))
            val targetId = call.request.queryParameters["targetId"]?.toLongOrNull()
                ?: throw ValidationException("targetId is required", mapOf("targetId" to "required"))
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(approvalService.listRoutesForTarget(targetType, targetId, call.currentUser()))
            )
        }

        get("/routes/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid route id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(approvalService.getRoute(id, call.currentUser())))
        }

        post("/routes/{id}/decisions") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid route id")
            val request = call.receive<SubmitApprovalDecisionRequest>()
            val route = approvalService.decide(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(route))
        }

        post("/routes/{id}/cancel") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid route id")
            val request = call.receive<CancelApprovalRouteRequest>()
            val route = approvalService.cancelRoute(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(route))
        }

        post("/routes/{id}/stages/{stageId}/assignees") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid route id")
            val stageId = call.parameters["stageId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid stage id")
            val request = call.receive<AddApprovalAssigneeRequest>()
            val route = approvalService.addAssignee(id, stageId, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(route))
        }

        delete("/routes/{id}/stages/{stageId}/assignees/{assigneeId}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid route id")
            val stageId = call.parameters["stageId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid stage id")
            val assigneeId = call.parameters["assigneeId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid assignee id")
            val route = approvalService.removeAssignee(id, stageId, assigneeId, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(route))
        }
    }
}
