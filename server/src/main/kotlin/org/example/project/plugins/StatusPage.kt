package org.example.project.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import org.example.project.dto.ApiError
import org.example.project.dto.ApiResponse
import org.example.project.exception.AppException
import org.example.project.exception.TooManyRequestsException

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<TooManyRequestsException> { call, cause ->
            call.response.headers.append(HttpHeaders.RetryAfter, cause.retryAfterSeconds.toString())
            call.respond(
                cause.statusCode,
                ApiResponse.error<Nothing>(ApiError(cause.code, cause.message ?: "", cause.details))
            )
        }
        exception<AppException> { call, cause ->
            call.respond(
                cause.statusCode,
                ApiResponse.error<Nothing>(ApiError(cause.code, cause.message ?: "", cause.details))
            )
        }
        exception<Throwable> { call, cause ->
            call.application.environment.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiResponse.error<Nothing>(ApiError("INTERNAL_ERROR", "Internal server error"))
            )
        }
    }
}
