package org.example.project.route

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.ByteReadChannel
import org.example.project.dto.*
import org.example.project.exception.ValidationException
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.ConversationService
import org.example.project.service.IncomingMessageFile

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
            val message = if (call.request.contentType().withoutParameters().match(ContentType.MultiPart.FormData)) {
                sendConversationMessageFromMultipart(conversationService, id)
            } else {
                val request = call.receive<SendChatMessageRequest>()
                conversationService.sendMessage(id, request, call.currentUser())
            }
            call.respond(HttpStatusCode.Created, ApiResponse.success(message))
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

// Файл ожидается последней частью multipart: его канал читается сервисом
// только после проверок прав и идемпотентности.
private suspend fun RoutingContext.sendConversationMessageFromMultipart(
    conversationService: ConversationService,
    conversationId: Long
): ConversationMessageDto {
    val multipart = call.receiveMultipart()
    var content = ""
    var idempotencyKey = ""
    var replyToMessageId: Long? = null
    var fileItem: PartData.FileItem? = null
    var fileChannel: ByteReadChannel? = null
    var fileName: String? = null
    var fileContentType: String? = null
    try {
        var part = multipart.readPart()
        while (part != null) {
            when {
                part is PartData.FormItem && part.name == "content" -> content = part.value
                part is PartData.FormItem && part.name == "idempotencyKey" -> idempotencyKey = part.value
                part is PartData.FormItem && part.name == "replyToMessageId" ->
                    replyToMessageId = part.value.toLongOrNull()
                part is PartData.FileItem && part.name == "file" && fileItem == null -> {
                    fileItem = part
                    fileName = part.originalFileName
                    fileContentType = part.contentType?.toString()
                    fileChannel = part.provider()
                    break
                }
            }
            part.release()
            part = multipart.readPart()
        }
        val file = fileChannel?.let { IncomingMessageFile(fileName, fileContentType, it) }
        return conversationService.sendMessage(
            conversationId,
            SendChatMessageRequest(content, idempotencyKey, replyToMessageId),
            call.currentUser(),
            file
        )
    } finally {
        fileItem?.release()
    }
}
