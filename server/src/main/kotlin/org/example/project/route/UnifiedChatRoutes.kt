package org.example.project.route

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.*
import org.example.project.exception.ValidationException
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.UnifiedChatListService

fun Route.unifiedChatRoutes(unifiedChatListService: UnifiedChatListService) {
    route("/api/chat/workspace") {
        install(RequireAuthPlugin) {}

        get {
            val kind = call.request.queryParameters["kind"]?.let {
                runCatching { ConversationKind.valueOf(it.uppercase()) }
                    .getOrElse { throw ValidationException("Invalid conversation kind", mapOf("kind" to "invalid")) }
            }
            val filter = call.request.queryParameters["filter"]?.let {
                runCatching { ConversationListFilter.valueOf(it.uppercase()) }
                    .getOrElse { throw ValidationException("Invalid filter", mapOf("filter" to "invalid")) }
            } ?: ConversationListFilter.ALL
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            if (limit < 1) throw ValidationException("Invalid limit", mapOf("limit" to "invalid"))
            val page = unifiedChatListService.list(
                query = call.request.queryParameters["query"],
                kind = kind,
                filter = filter,
                cursor = call.request.queryParameters["cursor"],
                limit = limit,
                currentUser = call.currentUser()
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(page))
        }

        post("/{kind}/{id}/state") {
            val kind = call.parameters["kind"]?.let {
                runCatching { ConversationKind.valueOf(it.uppercase()) }.getOrNull()
            } ?: throw ValidationException("Invalid conversation kind", mapOf("kind" to "invalid"))
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid conversation id")
            val item = unifiedChatListService.changeState(kind, id, call.receive(), call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(item))
        }
    }
}
