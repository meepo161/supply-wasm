package org.example.project.config

import io.ktor.server.config.*

private const val DEFAULT_PORT = 8080
private const val DEFAULT_SESSION_TTL_MINUTES = 480L
private const val DEFAULT_COOKIE_NAME = "erp_session"
private const val DEFAULT_STORAGE_PATH = "./.local/storage"
private val DEFAULT_ALLOWED_ORIGINS = listOf(
    "http://localhost:8080",
    "http://localhost:8081",
    "http://localhost:8090",
    "http://localhost:8091"
)
private val DEFAULT_PUSH_ALLOWED_HOSTS = listOf(
    "fcm.googleapis.com",
    "updates.push.services.mozilla.com",
    "web.push.apple.com"
)

data class PushSettings(
    val vapidPublicKey: String?,
    val vapidPrivateKey: String?,
    val vapidSubject: String?,
    val allowedHosts: List<String>
) {
    val enabled: Boolean
        get() = !vapidPublicKey.isNullOrBlank() && !vapidPrivateKey.isNullOrBlank() && !vapidSubject.isNullOrBlank()
}

data class AppConfig(
    val port: Int,
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val sessionTtlMinutes: Long,
    val cookieName: String,
    val storagePath: String,
    val allowedOrigins: List<String>,
    val cookieSecure: Boolean,
    val push: PushSettings
) {
    companion object {
        fun load(config: ApplicationConfig): AppConfig {
            return AppConfig(
                port = config.propertyOrNull("ktor.deployment.port")?.getString()?.toIntOrNull() ?: DEFAULT_PORT,
                dbUrl = config.propertyOrNull("database.url")?.getString() ?: "jdbc:postgresql://localhost:5434/erp",
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
                    ?: false,
                push = PushSettings(
                    vapidPublicKey = config.setting("push.vapidPublicKey", "ERP_PUSH_VAPID_PUBLIC_KEY"),
                    vapidPrivateKey = config.setting("push.vapidPrivateKey", "ERP_PUSH_VAPID_PRIVATE_KEY"),
                    vapidSubject = config.setting("push.vapidSubject", "ERP_PUSH_VAPID_SUBJECT"),
                    allowedHosts = config.setting("push.allowedHosts", "ERP_PUSH_ALLOWED_HOSTS")
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?: DEFAULT_PUSH_ALLOWED_HOSTS
                )
            )
        }

        private fun ApplicationConfig.setting(path: String, environmentVariable: String): String? =
            propertyOrNull(path)?.getString()?.takeIf { it.isNotBlank() } ?: System.getenv(environmentVariable)
    }
}
