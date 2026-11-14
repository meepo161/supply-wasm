package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js
import kotlin.js.ExperimentalWasmJsInterop

@OptIn(ExperimentalWasmJsInterop::class)
actual fun createAuthHttpClient(): HttpClient = HttpClient(Js) {
    configureBaseClient()
    engine {
        configureRequest {
            credentials = "include".toJsString()
        }
    }
}
