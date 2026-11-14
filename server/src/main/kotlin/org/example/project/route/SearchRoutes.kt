package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.dto.*
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.SearchService

fun Route.searchRoutes(searchService: SearchService) {
    route("/api/search") {
        install(RequireAuthPlugin) {}

        get {
            val query = call.request.queryParameters["q"] ?: ""
            val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 10
            val response = searchService.search(query, limit, call.currentUser())
            call.respond(HttpStatusCode.OK, ApiResponse.success(response))
        }
    }
}
