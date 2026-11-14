package org.example.project.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.js.Js

actual fun createAuthHttpClient(): HttpClient = HttpClient(Js) {
    configureBaseClient()
    engine {
        configureRequest {
            credentials = "include"
        }
    }
}
