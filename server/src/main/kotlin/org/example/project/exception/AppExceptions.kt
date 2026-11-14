package org.example.project.exception

import io.ktor.http.*

sealed class AppException(
    val statusCode: HttpStatusCode,
    val code: String,
    message: String,
    val details: Map<String, String> = emptyMap()
) : RuntimeException(message)

class ValidationException(
    message: String,
    details: Map<String, String> = emptyMap()
) : AppException(HttpStatusCode.BadRequest, "VALIDATION_ERROR", message, details)

class UnauthorizedException(message: String = "Unauthorized") :
    AppException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", message)

class ForbiddenException(message: String = "Forbidden") :
    AppException(HttpStatusCode.Forbidden, "FORBIDDEN", message)

class NotFoundException(entity: String, id: Any) :
    AppException(HttpStatusCode.NotFound, "NOT_FOUND", "$entity not found: $id")

class ConflictException(message: String) :
    AppException(HttpStatusCode.Conflict, "CONFLICT", message)

class InternalServerException(message: String = "Internal server error") :
    AppException(HttpStatusCode.InternalServerError, "INTERNAL_ERROR", message)
