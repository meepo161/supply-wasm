package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.*
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.ProjectService

fun Route.projectRoutes(projectService: ProjectService) {
    route("/api/projects") {
        install(RequireAuthPlugin) {}

        get {
            call.respond(HttpStatusCode.OK, ApiResponse.success(projectService.list(call.currentUser())))
        }

        post {
            val request = call.receive<CreateProjectRequest>()
            val project = projectService.create(request, call.currentUser())
            call.respond(HttpStatusCode.Created, ApiResponse.success(project))
        }

        get("/{id}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid project id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(projectService.get(id, call.currentUser())))
        }

        post("/{id}/open") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid project id")
            val request = call.receive<OpenProjectRequest>()
            val project = projectService.open(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(project))
        }

        post("/{id}/close") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid project id")
            val request = call.receive<CloseProjectRequest>()
            val project = projectService.close(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(project))
        }

        post("/{id}/members") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid project id")
            val request = call.receive<AddProjectMemberRequest>()
            val project = projectService.addMember(id, request, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(project))
        }

        delete("/{id}/members/{userId}") {
            val id = call.parameters["id"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid project id")
            val userId = call.parameters["userId"]?.toLongOrNull() ?: throw org.example.project.exception.ValidationException("Invalid user id")
            val project = projectService.removeMember(id, userId, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(project))
        }
    }
}
