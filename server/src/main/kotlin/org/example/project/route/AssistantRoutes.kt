package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.ApiResponse
import org.example.project.dto.AssistantChatRequestDto
import org.example.project.dto.AssistantConfirmDraftRequestDto
import org.example.project.exception.ValidationException
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.AssistantService

fun Route.assistantRoutes(assistantService: AssistantService) {
    route("/api/assistant") {
        install(RequireAuthPlugin) {}

        get("/status") {
            call.respond(HttpStatusCode.OK, ApiResponse.success(assistantService.status()))
        }

        get("/conversations") {
            call.respond(HttpStatusCode.OK, ApiResponse.success(assistantService.listConversations(call.currentUser())))
        }

        get("/conversations/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid conversation id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(assistantService.getConversation(id, call.currentUser())))
        }

        delete("/conversations/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid conversation id")
            assistantService.deleteConversation(id, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(Unit))
        }

        post("/chat") {
            val request = call.receive<AssistantChatRequestDto>()
            val response = assistantService.chat(request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }

        post("/confirm-draft") {
            val request = call.receive<AssistantConfirmDraftRequestDto>()
            val response = assistantService.confirmDraft(request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }
    }
}
