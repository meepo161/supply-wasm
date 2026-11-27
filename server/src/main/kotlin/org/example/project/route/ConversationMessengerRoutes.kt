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
import org.example.project.service.ConversationMessengerService

fun Route.conversationMessengerRoutes(messengerService: ConversationMessengerService) {
    route("/api/conversations") {
        post("/saved") {
            call.respond(HttpStatusCode.OK, ApiResponse.success(messengerService.getOrCreateSavedMessages(call.currentUser())))
        }

        patch("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid conversation id")
            val body = call.receive<UpdateConversationRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(messengerService.updateConversation(id, body, call.currentUser())))
        }

        post("/{id}/members") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid conversation id")
            val body = call.receive<UpdateConversationMembersRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(messengerService.updateMembers(id, body, call.currentUser())))
        }

        post("/{id}/leave") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid conversation id")
            messengerService.leaveConversation(id, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(Unit))
        }

        post("/{id}/messages/{messageId}/pin") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid conversation id")
            val msgId = call.parameters["messageId"]?.toLongOrNull() ?: throw ValidationException("Invalid message id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(messengerService.pinMessage(id, msgId, call.currentUser())))
        }

        delete("/{id}/messages/{messageId}/pin") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid conversation id")
            val msgId = call.parameters["messageId"]?.toLongOrNull() ?: throw ValidationException("Invalid message id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(messengerService.unpinMessage(id, msgId, call.currentUser())))
        }

        post("/{id}/polls") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid conversation id")
            val body = call.receive<CreatePollRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(messengerService.createPoll(id, body, call.currentUser())))
        }

        post("/{id}/polls/{pollId}/vote") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid conversation id")
            val pollId = call.parameters["pollId"]?.toLongOrNull() ?: throw ValidationException("Invalid poll id")
            val body = call.receive<VotePollRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(messengerService.votePoll(id, pollId, body, call.currentUser())))
        }

        get("/{id}/messages/{messageId}/thread") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid conversation id")
            val msgId = call.parameters["messageId"]?.toLongOrNull() ?: throw ValidationException("Invalid message id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(messengerService.listThreadMessages(id, msgId, call.currentUser())))
        }

        post("/{id}/messages/{messageId}/thread") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid conversation id")
            val msgId = call.parameters["messageId"]?.toLongOrNull() ?: throw ValidationException("Invalid message id")
            val body = call.receive<SendChatMessageRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(messengerService.sendThreadReply(id, msgId, body, call.currentUser())))
        }

        post("/{id}/scheduled") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid conversation id")
            val body = call.receive<ScheduledMessageRequest>()
            call.respond(HttpStatusCode.Created, ApiResponse.success(messengerService.scheduleOrSendSilent(id, body, call.currentUser())))
        }

        get("/{id}/scheduled") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid conversation id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(messengerService.listScheduledMessages(id, call.currentUser())))
        }

        post("/{id}/ai-summary") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid conversation id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(messengerService.summarizeConversation(id, call.currentUser())))
        }
    }

    route("/api/presence") {
        get {
            call.respond(HttpStatusCode.OK, ApiResponse.success(messengerService.listPresence()))
        }

        post {
            val body = call.receive<UpdatePresenceRequest>()
            call.respond(HttpStatusCode.OK, ApiResponse.success(messengerService.updatePresence(body.status, call.currentUser())))
        }
    }
}
