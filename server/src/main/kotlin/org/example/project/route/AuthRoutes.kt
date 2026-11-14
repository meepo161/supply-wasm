package org.example.project.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.origin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.example.project.config.AppConfig
import org.example.project.dto.*
import org.example.project.plugins.CurrentUserKey
import org.example.project.plugins.RequireAuthPlugin
import org.example.project.plugins.currentUser
import org.example.project.service.AuthService
import org.example.project.service.SessionService
import java.util.*

private fun AppConfig.sessionCookie(value: String, maxAge: Int): Cookie = Cookie(
    name = cookieName,
    value = value,
    httpOnly = true,
    secure = cookieSecure,
    path = "/",
    maxAge = maxAge,
    extensions = mapOf("SameSite" to "Lax")
)

fun Route.authRoutes(authService: AuthService, sessionService: SessionService, config: AppConfig) {
    route("/api/admin") {
        post("/bootstrap") {
            val request = call.receive<BootstrapRequest>()
            val response = authService.bootstrap(request)
            call.respond(HttpStatusCode.Created, ApiResponse.success(response))
        }
    }

    route("/api/auth") {
        post("/login") {
            val request = call.receive<LoginRequest>()
            val remoteIp = call.request.origin.remoteAddress
            val (currentUser, token) = authService.login(request, remoteIp)
            call.response.cookies.append(
                config.sessionCookie(
                    value = token.toString(),
                    maxAge = (config.sessionTtlMinutes * 60).toInt()
                )
            )
            call.respond(
                HttpStatusCode.OK,
                ApiResponse.success(
                    LoginResponse(
                        user = UserDto(
                            id = currentUser.userId,
                            login = currentUser.login,
                            fullName = currentUser.fullName,
                            permissions = currentUser.permissions.toList()
                        )
                    )
                )
            )
        }

        post("/logout") {
            val rawToken = call.request.cookies[config.cookieName]
            if (rawToken != null) {
                try {
                    val token = UUID.fromString(rawToken)
                    call.attributes.getOrNull(CurrentUserKey)?.let { user ->
                        authService.logout(user.userId, token)
                    }
                } catch (_: IllegalArgumentException) {
                    // malformed cookie — ignore and clear it
                }
            }
            call.response.cookies.append(
                config.sessionCookie(
                    value = "",
                    maxAge = 0
                )
            )
            call.respond(HttpStatusCode.OK, ApiResponse.success(Unit))
        }

        route("/me") {
            install(RequireAuthPlugin) {}
            get {
                val response = authService.me(call.currentUser().userId)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }

        route("/password") {
            install(RequireAuthPlugin) {}
            post {
                val request = call.receive<ChangePasswordRequest>()
                val response = authService.changePassword(call.currentUser().userId, request)
                call.respond(HttpStatusCode.OK, ApiResponse.success(response))
            }
        }
    }
}
