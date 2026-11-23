package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlinx.browser.window
import kotlin.js.ExperimentalWasmJsInterop

actual fun resolveApiPort(): Int = if (window.location.port == "8091") 8090 else 8080

@OptIn(ExperimentalWasmJsInterop::class)
actual fun createAuthHttpClient(): HttpClient = HttpClient(Js) {
    configureBaseClient()
    engine {
        configureRequest {
            credentials = "include".toJsString()
        }
    }
}
