package org.example.project.service

import org.example.project.exception.TooManyRequestsException

class MessageRateLimiter(
    private val limit: Int = 20,
    private val windowMillis: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Window(val startedAt: Long, var count: Int)

    private val windows = mutableMapOf<Pair<Long, String>, Window>()

    @Synchronized
    fun check(userId: Long, chatKey: String) {
        val now = clock()
        windows.entries.removeAll { (_, window) -> now - window.startedAt >= windowMillis }
        val startedAt = now - Math.floorMod(now, windowMillis)
        val key = userId to chatKey
        val window = windows[key]?.takeIf { it.startedAt == startedAt }
            ?: Window(startedAt, 0).also { windows[key] = it }
        if (window.count >= limit) {
            val retryAfter = ((startedAt + windowMillis - now + 999) / 1000).coerceAtLeast(1)
            throw TooManyRequestsException("Too many messages", retryAfter)
        }
        window.count++
    }

    internal val trackedWindowCount: Int
        @Synchronized get() = windows.size
}
