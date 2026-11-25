package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.*
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.DemandService
import org.example.project.service.DocumentService
import org.example.project.service.ProjectService
import org.example.project.service.TaskService

fun Route.projectRoutes(
    projectService: ProjectService,
    taskService: TaskService,
    demandService: DemandService,
    documentService: DocumentService
) {
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

        // Вкладки карточки проекта (PRJ-13/PRJ-14). Права проверяются в сервисах:
        // неучастник получает 403, а не пустой список.
        get("/{id}/summary") {
            val id = call.longParamOrThrow("id")
            call.respond(HttpStatusCode.OK, ApiResponse.success(projectService.summary(id, call.currentUser())))
        }

        get("/{id}/history") {
            val id = call.longParamOrThrow("id")
            val limit = call.request.queryParameters["limit"]?.let {
                it.toIntOrNull() ?: throw org.example.project.exception.ValidationException("Invalid limit")
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectService.history(id, limit, call.currentUser()))
            )
        }

        get("/{id}/tasks") {
            val id = call.longParamOrThrow("id")
            val status = call.request.queryParameters["status"]?.let {
                try {
                    TaskStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    throw org.example.project.exception.ValidationException("Invalid task status")
                }
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(taskService.listByProject(id, status, call.currentUser()))
            )
        }

        get("/{id}/demands") {
            val id = call.longParamOrThrow("id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(demandService.listByProject(id, call.currentUser()))
            )
        }

        get("/{id}/supply-overview") {
            val id = call.longParamOrThrow("id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(demandService.projectSupplyOverview(id, call.currentUser()))
            )
        }

        post("/{id}/handoff-specification") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<HandoffProjectSpecRequest>()
            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(demandService.handoffProjectSpecification(id, request, call.currentUser()))
            )
        }

        get("/{id}/documents") {
            val id = call.longParamOrThrow("id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(documentService.listByProject(id, call.currentUser()))
            )
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
