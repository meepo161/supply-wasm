package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.ApiResponse
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.AuditService

fun Route.auditRoutes(auditService: AuditService) {
    route("/api/audit") {
        install(RequireAuthPlugin) {}

        get {
            val entityType = call.request.queryParameters["entityType"]
            val action = call.request.queryParameters["action"]
            val userId = call.request.queryParameters["userId"]?.toLongOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100
            val offset = call.request.queryParameters["offset"]?.toIntOrNull() ?: 0
            val result = auditService.list(
                entityType = entityType,
                action = action,
                userId = userId,
                limit = limit,
                offset = offset,
                currentUser = call.currentUser()
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(result))
        }
    }
}
