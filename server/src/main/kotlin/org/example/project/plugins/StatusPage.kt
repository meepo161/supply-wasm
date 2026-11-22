package org.example.project.plugins

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
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
        // Ошибки чтения тела запроса — вина клиента, а не сервера (аудит AUDIT-01):
        // без этих обработчиков они попадали в Throwable и отдавались как 500.
        exception<UnsupportedMediaTypeException> { call, _ ->
            call.respond(
                HttpStatusCode.UnsupportedMediaType,
                ApiResponse.error<Nothing>(ApiError("UNSUPPORTED_MEDIA_TYPE", "Unsupported request content type"))
            )
        }
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ApiResponse.error<Nothing>(ApiError("PAYLOAD_TOO_LARGE", "Request body is too large"))
            )
        }
        exception<ContentTransformationException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error<Nothing>(ApiError("VALIDATION_ERROR", "Invalid request body"))
            )
        }
        exception<BadRequestException> { call, _ ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error<Nothing>(ApiError("VALIDATION_ERROR", "Invalid request body"))
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
