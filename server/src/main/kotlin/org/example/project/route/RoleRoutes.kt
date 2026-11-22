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
import org.example.project.plugins.requirePermission
import org.example.project.service.RoleService

fun Route.roleRoutes(roleService: RoleService) {
    route("/api/roles") {
        install(RequireAuthPlugin) {}

        get {
            call.requirePermission("roles", "read")
            call.respond(HttpStatusCode.OK, ApiResponse.success(roleService.list()))
        }

        post {
            call.requirePermission("roles", "create")
            val request = call.receive<CreateRoleRequest>()
            val role = roleService.create(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(role))
        }

        get("/{id}") {
            call.requirePermission("roles", "read")
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid role id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(roleService.get(id)))
        }
    }
}
