package org.example.project.config

import io.ktor.server.config.*

private const val DEFAULT_PORT = 8080
private const val DEFAULT_SESSION_TTL_MINUTES = 480L
private const val DEFAULT_COOKIE_NAME = "erp_session"
private const val DEFAULT_STORAGE_PATH = "./.local/storage"
private val DEFAULT_ALLOWED_ORIGINS = listOf("http://localhost:8080", "http://localhost:8081")

data class AppConfig(
    val port: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val sessionTtlMinutes: Long,
    val cookieName: String,
    val storagePath: String,
    val allowedOrigins: List<String>,
    val cookieSecure: Boolean
) {
    companion object {
        fun load(config: ApplicationConfig): AppConfig {
            return AppConfig(
                port = config.propertyOrNull("ktor.deployment.port")?.getString()?.toIntOrNull() ?: DEFAULT_PORT,
                dbUrl = config.propertyOrNull("database.url")?.getString() ?: "jdbc:postgresql://localhost:5433/erp",
                dbUser = config.propertyOrNull("database.user")?.getString() ?: "erp",
                dbPassword = config.propertyOrNull("database.password")?.getString() ?: "erp",
                sessionTtlMinutes = config.propertyOrNull("session.ttlMinutes")?.getString()?.toLongOrNull()
                    ?: DEFAULT_SESSION_TTL_MINUTES,
                cookieName = config.propertyOrNull("session.cookieName")?.getString() ?: DEFAULT_COOKIE_NAME,
                storagePath = config.propertyOrNull("storage.path")?.getString() ?: DEFAULT_STORAGE_PATH,
                allowedOrigins = config.propertyOrNull("security.allowedOrigins")?.getString()
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: DEFAULT_ALLOWED_ORIGINS,
                cookieSecure = config.propertyOrNull("security.cookieSecure")?.getString()?.toBooleanStrictOrNull()
                    ?: false
            )
        }
    }
}
