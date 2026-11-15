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
import org.example.project.service.ProjectPlanService

/**
 * План проекта: этапы, контрольные точки и риски (5.3, 5.5 ТЗ;
 * PRJ-08/09/10). Права проверяет сервис: читает участник проекта,
 * меняет владелец или держатель `projects:manage_plan`.
 */
fun Route.projectPlanRoutes(projectPlanService: ProjectPlanService) {
    route("/api/projects/{projectId}") {
        install(RequireAuthPlugin) {}

        // --- Этапы ---------------------------------------------------------

        get("/stages") {
            val projectId = call.longParamOrThrow("projectId")
            val status = call.request.queryParameters["status"]?.let {
                try {
                    ProjectStageStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    throw ValidationException("Invalid stage status")
                }
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectPlanService.listStages(projectId, status, call.currentUser()))
            )
        }

        post("/stages") {
            val projectId = call.longParamOrThrow("projectId")
            val request = call.receive<CreateProjectStageRequest>()
            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(projectPlanService.createStage(projectId, request, call.currentUser()))
            )
        }

        // --- Контрольные точки ---------------------------------------------

        get("/milestones") {
            val projectId = call.longParamOrThrow("projectId")
            val stageId = call.request.queryParameters["stageId"]?.let {
                it.toLongOrNull() ?: throw ValidationException("Invalid stageId")
            }
            val status = call.request.queryParameters["status"]?.let {
                try {
                    ProjectMilestoneStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    throw ValidationException("Invalid milestone status")
                }
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(
                    projectPlanService.listMilestones(projectId, stageId, status, call.currentUser())
                )
            )
        }

        post("/milestones") {
            val projectId = call.longParamOrThrow("projectId")
            val request = call.receive<CreateProjectMilestoneRequest>()
            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(
                    projectPlanService.createMilestone(projectId, request, call.currentUser())
                )
            )
        }

        // --- Риски ---------------------------------------------------------

        get("/risks") {
            val projectId = call.longParamOrThrow("projectId")
            val status = call.request.queryParameters["status"]?.let {
                try {
                    ProjectRiskStatus.valueOf(it)
                } catch (_: IllegalArgumentException) {
                    throw ValidationException("Invalid risk status")
                }
            }
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectPlanService.listRisks(projectId, status, call.currentUser()))
            )
        }

        post("/risks") {
            val projectId = call.longParamOrThrow("projectId")
            val request = call.receive<CreateProjectRiskRequest>()
            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(projectPlanService.createRisk(projectId, request, call.currentUser()))
            )
        }

        // --- Сводка плана --------------------------------------------------

        get("/plan-summary") {
            val projectId = call.longParamOrThrow("projectId")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectPlanService.planSummary(projectId, call.currentUser()))
            )
        }
    }

    route("/api/project-stages") {
        install(RequireAuthPlugin) {}

        get("/{id}") {
            val id = call.longParamOrThrow("id")
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectPlanService.getStage(id, call.currentUser()))
            )
        }

        put("/{id}") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<UpdateProjectStageRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectPlanService.updateStage(id, request, call.currentUser()))
            )
        }

        post("/{id}/start") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<ProjectStageCommandRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectPlanService.commandStage(id, "START", request, call.currentUser()))
            )
        }

        post("/{id}/complete") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<ProjectStageCommandRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectPlanService.commandStage(id, "COMPLETE", request, call.currentUser()))
            )
        }

        post("/{id}/cancel") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<ProjectStageCommandRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectPlanService.commandStage(id, "CANCEL", request, call.currentUser()))
            )
        }

        delete("/{id}") {
            val id = call.longParamOrThrow("id")
            val version = call.requiredVersion()
            projectPlanService.deleteStage(id, version, call.currentUser())
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/api/project-milestones") {
        install(RequireAuthPlugin) {}

        put("/{id}") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<UpdateProjectMilestoneRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectPlanService.updateMilestone(id, request, call.currentUser()))
            )
        }

        post("/{id}/reach") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<ProjectMilestoneCommandRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectPlanService.commandMilestone(id, "REACH", request, call.currentUser()))
            )
        }

        post("/{id}/miss") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<ProjectMilestoneCommandRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectPlanService.commandMilestone(id, "MISS", request, call.currentUser()))
            )
        }

        delete("/{id}") {
            val id = call.longParamOrThrow("id")
            val version = call.requiredVersion()
            projectPlanService.deleteMilestone(id, version, call.currentUser())
            call.respond(HttpStatusCode.NoContent)
        }
    }

    route("/api/project-risks") {
        install(RequireAuthPlugin) {}

        put("/{id}") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<UpdateProjectRiskRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectPlanService.updateRisk(id, request, call.currentUser()))
            )
        }

        post("/{id}/mitigate") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<ProjectMilestoneCommandRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectPlanService.commandRisk(id, "MITIGATE", request, call.currentUser()))
            )
        }

        post("/{id}/reopen") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<ProjectMilestoneCommandRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectPlanService.commandRisk(id, "REOPEN", request, call.currentUser()))
            )
        }

        post("/{id}/close") {
            val id = call.longParamOrThrow("id")
            val request = call.receive<CloseProjectRiskRequest>()
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(projectPlanService.closeRisk(id, request, call.currentUser()))
            )
        }

        delete("/{id}") {
            val id = call.longParamOrThrow("id")
            val version = call.requiredVersion()
            projectPlanService.deleteRisk(id, version, call.currentUser())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
