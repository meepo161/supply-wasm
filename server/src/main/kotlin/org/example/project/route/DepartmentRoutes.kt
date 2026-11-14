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
import org.example.project.service.DepartmentService

fun Route.departmentRoutes(departmentService: DepartmentService) {
    route("/api/departments") {
        install(RequireAuthPlugin) {}

        get {
            call.requirePermission("departments", "read")
            call.respond(HttpStatusCode.OK, ApiResponse.success(departmentService.list()))
        }

        post {
            call.requirePermission("departments", "create")
            val request = call.receive<CreateDepartmentRequest>()
            val dept = departmentService.create(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(dept))
        }

        get("/{id}") {
            call.requirePermission("departments", "read")
            val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Invalid department id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(departmentService.get(id)))
        }

        put("/{id}") {
            call.requirePermission("departments", "update")
            val id = call.parameters["id"]?.toLongOrNull() ?: throw IllegalArgumentException("Invalid department id")
            val request = call.receive<UpdateDepartmentRequest>()
            val dept = departmentService.update(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(dept))
        }
    }
}
