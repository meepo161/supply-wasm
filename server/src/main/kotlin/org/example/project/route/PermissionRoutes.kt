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
import org.example.project.service.PermissionService

fun Route.permissionRoutes(permissionService: PermissionService) {
    route("/api/permissions") {
        install(RequireAuthPlugin) {}

        get {
            call.requirePermission("permissions", "read")
            call.respond(HttpStatusCode.OK, ApiResponse.success(permissionService.list()))
        }

        post {
            call.requirePermission("permissions", "create")
            val request = call.receive<CreatePermissionWithRoleRequest>()
            val perm = permissionService.create(
                request.roleId,
                CreatePermissionRequest(request.resource, request.action, request.scope),
                call.currentUser()
            )
            call.respond(HttpStatusCode.Created, ApiResponse.success(perm))
        }

        delete("/{id}") {
            call.requirePermission("permissions", "delete")
            val id = call.parameters["id"]?.toLongOrNull() ?: throw ValidationException("Invalid permission id")
            permissionService.delete(id, call.currentUser())
            call.respond(HttpStatusCode.NoContent, ApiResponse.success(Unit))
        }
    }
}

@kotlinx.serialization.Serializable
data class CreatePermissionWithRoleRequest(
    val roleId: Long,
    val resource: String,
    val action: String,
    val scope: String? = null
)
