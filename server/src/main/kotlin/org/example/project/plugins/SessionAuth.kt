package org.example.project.plugins

import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.response.*
import io.ktor.util.*
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.exception.UnauthorizedException
import org.example.project.model.CurrentUser
import org.example.project.service.SessionService
import java.util.*

val CurrentUserKey = AttributeKey<CurrentUser>("CurrentUser")

class SessionContextConfiguration {
    var sessionService: SessionService? = null
    var cookieName: String = "session"
}

val SessionContextPlugin = createApplicationPlugin("SessionContext", ::SessionContextConfiguration) {
    val sessionService = pluginConfig.sessionService ?: throw IllegalStateException("SessionService not configured")
    val cookieName = pluginConfig.cookieName

    onCall { call ->
        val cookie = call.request.cookies[cookieName]
        if (!cookie.isNullOrBlank()) {
            try {
                val token = UUID.fromString(cookie)
                sessionService.validateSession(token)?.let { user ->
                    call.attributes.put(CurrentUserKey, user)
                }
            } catch (_: IllegalArgumentException) {
                // ignore malformed cookie
            }
        }
    }
}

val RequireAuthPlugin = createRouteScopedPlugin("RequireAuth") {
    onCall { call ->
        call.currentUser()
    }
}

fun ApplicationCall.currentUser(): CurrentUser {
    return attributes.getOrNull(CurrentUserKey) ?: throw UnauthorizedException()
}

fun ApplicationCall.requirePermission(resource: String, action: String) {
    currentUser().requirePermission(resource, action)
}

