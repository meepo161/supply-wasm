package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.*
import org.example.project.exception.ValidationException
import org.example.project.plugins.currentUser
import org.example.project.service.WmsIssueService

// Ответы команд возвращаются как сохранённое JSON-тело (idempotency replay),
// поэтому используется respondText, а не respond с сериализацией.
fun Route.wmsIssueRoutes(wmsIssueService: WmsIssueService) {
    route("/api/wms") {

        post("/issues") {
            val request = call.receive<CreateIssueOrderRequest>()
            val result = wmsIssueService.createIssue(request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        get("/issues") {
            val warehouseId = call.request.queryParameters["warehouseId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid warehouseId")
            }
            val status = call.request.queryParameters["status"]?.let {
                try {
                    IssueStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    throw ValidationException("Invalid issue status")
                }
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(wmsIssueService.listIssues(warehouseId, status, call.currentUser()))
            )
        }

        get("/issues/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid issue order id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsIssueService.getIssue(id, call.currentUser())))
        }

        post("/issues/{id}/picks") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid issue order id")
            val request = call.receive<PickIssueRequest>()
            val result = wmsIssueService.addPicks(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/issues/{id}/issue") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid issue order id")
            val request = call.receive<IssueActionRequest>()
            val result = wmsIssueService.issue(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/issues/{id}/confirm") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid issue order id")
            val request = call.receive<IssueActionRequest>()
            val result = wmsIssueService.confirm(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/issues/{id}/refuse") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid issue order id")
            val request = call.receive<RefuseIssueRequest>()
            val result = wmsIssueService.refuse(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/issues/{id}/cancel") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid issue order id")
            val request = call.receive<IssueActionRequest>()
            val result = wmsIssueService.cancel(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/returns") {
            val request = call.receive<CreateReturnOrderRequest>()
            val result = wmsIssueService.createReturn(request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        get("/returns") {
            val warehouseId = call.request.queryParameters["warehouseId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid warehouseId")
            }
            val status = call.request.queryParameters["status"]?.let {
                try {
                    ReturnStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    throw ValidationException("Invalid return status")
                }
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(wmsIssueService.listReturns(warehouseId, status, call.currentUser()))
            )
        }

        get("/returns/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid return order id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(wmsIssueService.getReturn(id, call.currentUser())))
        }

        post("/returns/{id}/receive") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid return order id")
            val request = call.receive<ReturnActionRequest>()
            val result = wmsIssueService.receiveReturn(id, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }

        post("/returns/{id}/lines/{lineId}/inspect") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid return order id")
            val lineId = call.parameters["lineId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid return line id")
            val request = call.receive<InspectReturnLineRequest>()
            val result = wmsIssueService.inspectReturnLine(id, lineId, request, call.currentUser())
            call.respondText(result.body, ContentType.Application.Json, result.status)
        }
    }
}
