package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.*
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.TaskService

fun Route.taskRoutes(taskService: TaskService) {
    route("/api/tasks") {
        install(RequireAuthPlugin) {}

        get {
            call.respond(HttpStatusCode.OK, ApiResponse.success(taskService.list(call.currentUser())))
        }

        post {
            val request = call.receive<CreateTaskRequest>()
            val task = taskService.create(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(task))
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(taskService.get(id, call.currentUser())))
        }

        post("/{id}/assign") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val request = call.receive<AssignTaskRequest>()
            val task = taskService.assign(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(task))
        }

        post("/{id}/accept") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val request = call.receive<TaskCommandRequest>()
            val task = taskService.accept(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(task))
        }

        post("/{id}/start") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val request = call.receive<TaskCommandRequest>()
            val task = taskService.start(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(task))
        }

        post("/{id}/result") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val request = call.receive<SubmitResultRequest>()
            val task = taskService.submitResult(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(task))
        }

        post("/{id}/send-to-review") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val request = call.receive<TaskCommandRequest>()
            val task = taskService.sendToReview(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(task))
        }

        post("/{id}/review") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val request = call.receive<ReviewTaskRequest>()
            val task = taskService.review(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(task))
        }

        post("/{id}/close") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val request = call.receive<TaskCommandRequest>()
            val task = taskService.closeWithoutReview(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(task))
        }

        post("/{id}/block") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val request = call.receive<BlockTaskRequest>()
            val task = taskService.block(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(task))
        }

        post("/{id}/unblock") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val request = call.receive<TaskCommandRequest>()
            val task = taskService.unblock(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(task))
        }

        post("/{id}/reopen") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val request = call.receive<ReopenTaskRequest>()
            val task = taskService.reopen(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(task))
        }

        post("/{id}/cancel") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid task id")
            val request = call.receive<CancelTaskRequest>()
            val task = taskService.cancel(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(task))
        }
    }
}
