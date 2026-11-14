package org.example.project.exception

import io.ktor.http.HttpStatusCode

class TooManyRequestsException(
    message: String = "Too many requests",
    val retryAfterSeconds: Long = 0
) : AppException(HttpStatusCode.TooManyRequests, "TOO_MANY_REQUESTS", message)
