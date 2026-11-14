package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.ApiResponse
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.NotificationService

fun Route.notificationRoutes(notificationService: NotificationService) {
    route("/api/notifications") {
        install(RequireAuthPlugin) {}

        get {
            val after = call.request.queryParameters["after"]?.toLongOrNull()
            val before = call.request.queryParameters["before"]?.toLongOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val page = notificationService.list(after, before, limit, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(page))
        }

        post("/{id}/read") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid notification id")
            val notification = notificationService.markRead(id, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(notification))
        }
    }
}
