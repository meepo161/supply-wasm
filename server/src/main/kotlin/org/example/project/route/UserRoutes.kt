package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.*
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.plugins.requirePermission
import org.example.project.service.UserService

fun Route.userRoutes(userService: UserService) {
    route("/api/users") {
        install(RequireAuthPlugin) {}

        get {
            call.requirePermission("users", "read_any")
            call.respond(HttpStatusCode.OK, ApiResponse.success(userService.list()))
        }

        post {
            call.requirePermission("users", "create")
            val request = call.receive<CreateUserRequest>()
            val user = userService.create(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(user))
        }

        get("/options") {
            call.respond(HttpStatusCode.OK, ApiResponse.success(userService.listOptions()))
        }

        put("/me") {
            val request = call.receive<UpdateOwnProfileRequest>()
            val user = userService.updateOwnProfile(request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(user))
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid user id")
            val user = userService.get(id, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(user))
        }

        put("/{id}") {
            call.requirePermission("users", "update_any")
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid user id")
            val request = call.receive<UpdateUserRequest>()
            val user = userService.update(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(user))
        }

        put("/{id}/active") {
            call.requirePermission("users", "activate")
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid user id")
            val request = call.receive<SetActiveRequest>()
            val user = userService.setActive(id, request.active, request.version, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(user))
        }

        post("/{id}/reset-password") {
            call.requirePermission("users", "reset_password")
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid user id")
            val request = call.receive<AdminResetPasswordRequest>()
            val user = userService.resetPassword(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(user))
        }
    }
}

@kotlinx.serialization.Serializable
data class SetActiveRequest(val active: Boolean, val version: Long)
