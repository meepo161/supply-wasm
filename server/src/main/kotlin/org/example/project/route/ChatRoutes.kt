package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.*
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.ChatService

fun Route.chatRoutes(chatService: ChatService) {
    route("/api/tasks/{taskId}/chat") {
        install(RequireAuthPlugin) {}

        get("/messages") {
            val taskId = call.parameters["taskId"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val after = call.request.queryParameters["after"]?.toLongOrNull()
            val before = call.request.queryParameters["before"]?.toLongOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val page = chatService.listMessages(taskId, after, before, limit, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(page))
        }

        post("/messages") {
            val taskId = call.parameters["taskId"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val request = call.receive<SendChatMessageRequest>()
            val message = chatService.sendMessage(taskId, request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(message))
        }

        post("/system-events") {
            val taskId = call.parameters["taskId"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val request = call.receive<AddSystemEventRequest>()
            val event = chatService.addSystemEvent(taskId, request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(event))
        }
    }
}
