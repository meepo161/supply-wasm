package org.example.project.model

import io.ktor.server.auth.*

class CurrentUser(
    val userId: Long,
    val login: String,
    val fullName: String,
    val permissions: Set<String>
) {

    fun hasPermission(resource: String, action: String): Boolean {
        return permissions.contains("*:*") ||
            permissions.contains("$resource:*") ||
            permissions.contains("$resource:$action")
    }

    fun requirePermission(resource: String, action: String) {
        if (!hasPermission(resource, action)) {
            throw org.example.project.exception.ForbiddenException("Missing permission $resource:$action")
        }
    }
}
