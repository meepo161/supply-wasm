package org.example.project.route

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.*
import org.example.project.exception.ValidationException
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.AttachmentService
import org.example.project.service.ConversationService
import org.example.project.service.MessageRateLimiter
import org.example.project.service.PreparedMessageFile
import org.koin.ktor.ext.getKoin

fun Route.conversationRoutes(conversationService: ConversationService) {
    route("/api/conversations") {
        install(RequireAuthPlugin) {}

        get {
            call.respond(HttpStatusCode.OK, ApiResponse.success(conversationService.list(call.currentUser())))
        }

        post {
            val request = call.receive<CreateConversationRequest>()
            val (conversation, created) = conversationService.create(request, call.currentUser())
            call.respond(
                if (created) HttpStatusCode.Created else HttpStatusCode.OK,
                ApiResponse.success(conversation)
            )
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid conversation id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(conversationService.get(id, call.currentUser())))
        }

        get("/{id}/messages") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid conversation id")
            val after = call.request.queryParameters["after"]?.toLongOrNull()
            val before = call.request.queryParameters["before"]?.toLongOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val page = conversationService.listMessages(id, after, before, limit, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(page))
        }

        post("/{id}/messages") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid conversation id")
            call.application.getKoin().get<MessageRateLimiter>()
                .check(call.currentUser().userId, "conversation:$id")
            val message = if (call.request.contentType().withoutParameters().match(ContentType.MultiPart.FormData)) {
                sendConversationMessageFromMultipart(conversationService, id)
            } else {
                val request = call.receive<SendChatMessageRequest>()
                conversationService.sendMessage(id, request, call.currentUser())
            }
            call.respond(HttpStatusCode.Created, ApiResponse.success(message))
        }
        post("/{id}/messages/{messageId}/reactions") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid conversation id")
            val messageId = call.parameters["messageId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid message id")
            val request = call.receive<ReactionRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(conversationService.toggleReaction(id, messageId, request.emoji, call.currentUser()))
            )
        }

        post("/{id}/messages/{messageId}/edit") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid conversation id")
            val messageId = call.parameters["messageId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid message id")
            val message = conversationService.editMessage(id, messageId, call.receive(), call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(message))
        }

        post("/{id}/messages/{messageId}/revoke") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid conversation id")
            val messageId = call.parameters["messageId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid message id")
            val message = conversationService.revokeMessage(id, messageId, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(message))
        }

        post("/{id}/messages/{messageId}/forward") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid conversation id")
            val messageId = call.parameters["messageId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid message id")
            val request = call.receive<ForwardMessageRequest>()
            call.application.getKoin().get<MessageRateLimiter>()
                .check(call.currentUser().userId, "conversation:${request.targetId}")
            val message = conversationService.forwardMessage(id, messageId, request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(message))
        }

        post("/{id}/read") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid conversation id")
            val cursor = conversationService.markRead(id, call.receive(), call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(cursor))
        }

        get("/{id}/messages/search") {
            val id = call.parameters["id"]?.toLongOrNull()
                ?: throw ValidationException("Invalid conversation id")
            val after = call.request.queryParameters["after"]?.toLongOrNull()
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 50
            val page = conversationService.searchMessages(
                id,
                call.request.queryParameters["q"],
                after,
                limit,
                call.currentUser()
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(page))
        }
    }

    route("/api/projects/{projectId}/chat") {
        install(RequireAuthPlugin) {}

        post {
            val projectId = call.parameters["projectId"]?.toLongOrNull()
                ?: throw ValidationException("Invalid project id")
            val conversation = conversationService.ensureProjectChat(projectId, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(conversation))
        }
    }
}

private suspend fun RoutingContext.sendConversationMessageFromMultipart(
    conversationService: ConversationService,
    conversationId: Long
): ConversationMessageDto {
    val multipart = call.receiveMultipart()
    var content = ""
    var idempotencyKey = ""
    var replyToMessageId: Long? = null
    val prepared = mutableListOf<PreparedMessageFile>()
    var aggregateSize = 0L
    var handedOff = false
    try {
        var validated = false
        var part = multipart.readPart()
        while (part != null) {
            try {
                when {
                    part is PartData.FormItem && part.name == "content" -> content = part.value
                    part is PartData.FormItem && part.name == "idempotencyKey" -> idempotencyKey = part.value
                    part is PartData.FormItem && part.name == "replyToMessageId" ->
                        replyToMessageId = part.value.toLongOrNull()
                    part is PartData.FileItem && (part.name == "files" || part.name == "file") -> {
                        val request = SendChatMessageRequest(content, idempotencyKey, replyToMessageId)
                        if (!validated) {
                            conversationService.validateMultipartMessage(
                                conversationId, request, call.currentUser()
                            )?.let { existing ->
                                part.release()
                                while (multipart.readPart()?.also { it.release() } != null) Unit
                                return existing
                            }
                            validated = true
                        }
                        if (prepared.size >= AttachmentService.MAX_MESSAGE_FILES) {
                            throw ValidationException(
                                "Too many files in message",
                                mapOf("files" to "too_many", "maxFiles" to AttachmentService.MAX_MESSAGE_FILES.toString())
                            )
                        }
                        val file = conversationService.prepareMessageFile(
                            part.originalFileName, part.contentType?.toString(), part.provider()
                        )
                        prepared += file
                        aggregateSize += file.sizeBytes
                        if (aggregateSize > AttachmentService.MAX_MESSAGE_AGGREGATE_SIZE_BYTES) {
                            throw ValidationException(
                                "Files exceed maximum aggregate size",
                                mapOf("files" to "aggregate_too_large")
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                part.release()
                throw t
            }
            part.release()
            part = multipart.readPart()
        }
        handedOff = true
        return conversationService.sendMessage(
            conversationId,
            SendChatMessageRequest(content, idempotencyKey, replyToMessageId),
            call.currentUser(),
            prepared
        )
    } finally {
        if (!handedOff) conversationService.discardMessageFiles(prepared)
    }
}
