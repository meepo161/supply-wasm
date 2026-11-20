package org.example.project.route

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.*
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.exception.ValidationException
import org.example.project.service.AttachmentService
import org.example.project.service.ChatService
import org.example.project.service.MessageRateLimiter
import org.example.project.service.PreparedMessageFile
import org.koin.ktor.ext.getKoin

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
            call.application.getKoin().get<MessageRateLimiter>().check(call.currentUser().userId, "task:$taskId")
            val message = if (call.request.contentType().withoutParameters().match(ContentType.MultiPart.FormData)) {
                sendChatMessageFromMultipart(chatService, taskId)
            } else {
                val request = call.receive<SendChatMessageRequest>()
                chatService.sendMessage(taskId, request, call.currentUser())
            }
            call.respond(HttpStatusCode.Created, ApiResponse.success(message))
        }

        post("/system-events") {
            val taskId = call.parameters["taskId"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val request = call.receive<AddSystemEventRequest>()
            val event = chatService.addSystemEvent(taskId, request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(event))
        }

        post("/messages/{messageId}/edit") {
            val taskId = call.parameters["taskId"]?.toLongOrNull()
                ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val messageId = call.parameters["messageId"]?.toLongOrNull()
                ?: throw org.example.project.exception.ValidationException("Invalid message id")
            val message = chatService.editMessage(taskId, messageId, call.receive(), call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(message))
        }

        post("/messages/{messageId}/revoke") {
            val taskId = call.parameters["taskId"]?.toLongOrNull()
                ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val messageId = call.parameters["messageId"]?.toLongOrNull()
                ?: throw org.example.project.exception.ValidationException("Invalid message id")
            val message = chatService.revokeMessage(taskId, messageId, call.receive(), call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(message))
        }

        post("/messages/{messageId}/forward") {
            val taskId = call.parameters["taskId"]?.toLongOrNull()
                ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val messageId = call.parameters["messageId"]?.toLongOrNull()
                ?: throw org.example.project.exception.ValidationException("Invalid message id")
            val request = call.receive<ForwardMessageRequest>()
            call.application.getKoin().get<MessageRateLimiter>()
                .check(call.currentUser().userId, "task:${request.targetId}")
            val message = chatService.forwardMessage(taskId, messageId, request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(message))
        }

        post("/read") {
            val taskId = call.parameters["taskId"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val request = call.receive<MarkChatReadRequest>()
            val effective = chatService.markRead(taskId, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("lastReadMessageId" to effective)))
        }

        put("/messages/{messageId}/reactions/{emoji}") {
            val taskId = call.parameters["taskId"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val messageId = call.parameters["messageId"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid message id")
            val emoji = call.parameters["emoji"] ?: throw org.example.project.exception.ValidationException("Invalid emoji")
            val reactions = chatService.addReaction(taskId, messageId, emoji, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(reactions))
        }

        delete("/messages/{messageId}/reactions/{emoji}") {
            val taskId = call.parameters["taskId"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val messageId = call.parameters["messageId"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid message id")
            val emoji = call.parameters["emoji"] ?: throw org.example.project.exception.ValidationException("Invalid emoji")
            val reactions = chatService.removeReaction(taskId, messageId, emoji, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(reactions))
        }

        put("/pin") {
            val taskId = call.parameters["taskId"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val pinned = chatService.setDialogPinned(taskId, pinned = true, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("isPinned" to pinned)))
        }

        delete("/pin") {
            val taskId = call.parameters["taskId"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val pinned = chatService.setDialogPinned(taskId, pinned = false, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(mapOf("isPinned" to pinned)))
        }

        get("/search") {
            val taskId = call.parameters["taskId"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val query = call.request.queryParameters["query"]
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 20
            val results = chatService.searchMessages(taskId, query, limit, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(results))
        }
    }

    route("/api/tasks/chat") {
        install(RequireAuthPlugin) {}

        get("/list") {
            val query = call.request.queryParameters["query"]
            val filter = call.request.queryParameters["filter"]
                ?.let {
                    runCatching { TaskChatListFilter.valueOf(it.uppercase()) }
                        .getOrElse { throw org.example.project.exception.ValidationException("Invalid filter", mapOf("filter" to "invalid")) }
                }
                ?: TaskChatListFilter.ALL
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 200
            val items = chatService.listChats(query, filter, limit, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(items))
        }
    }
}

private suspend fun RoutingContext.sendChatMessageFromMultipart(
    chatService: ChatService,
    taskId: Long
): ChatMessageDto {
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
                            chatService.validateMultipartMessage(taskId, request, call.currentUser())?.let { existing ->
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
                        val file = chatService.prepareMessageFile(
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
        return chatService.sendMessage(
            taskId,
            SendChatMessageRequest(content, idempotencyKey, replyToMessageId),
            call.currentUser(),
            prepared
        )
    } finally {
        if (!handedOff) chatService.discardMessageFiles(prepared)
    }
}
