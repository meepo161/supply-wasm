package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.URLProtocol
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Адрес API-сервера. Один источник для HTTP-клиента и для ссылок, которые открываются
 * в браузере напрямую (печать наряда, CSV-выгрузки), чтобы они не расходились.
 */
object ApiServer {
    const val HOST = "localhost"
    val PORT: Int get() = resolveApiPort()
    val baseUrl: String get() = "http://$HOST:$PORT"
}

internal fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureBaseClient() {
    expectSuccess = false
    install(WebSockets)
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = false
            }
        )
    }
    defaultRequest {
        url {
            protocol = URLProtocol.HTTP
            host = ApiServer.HOST
            port = ApiServer.PORT
        }
    }
}

expect fun resolveApiPort(): Int

expect fun createAuthHttpClient(): HttpClient
