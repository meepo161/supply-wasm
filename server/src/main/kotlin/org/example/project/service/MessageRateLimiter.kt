package org.example.project.service

import org.example.project.exception.TooManyRequestsException

class MessageRateLimiter(
    private val limit: Int = 20,
    private val windowMillis: Long = 10_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val windows = mutableMapOf<Pair<Long, String>, ArrayDeque<Long>>()

    @Synchronized
    fun check(userId: Long, chatKey: String) {
        val now = clock()
        val cutoff = now - windowMillis
        windows.entries.removeAll { (_, timestamps) ->
            while (timestamps.isNotEmpty() && timestamps.first() <= cutoff) {
                timestamps.removeFirst()
            }
            timestamps.isEmpty()
        }
        val key = userId to chatKey
        val timestamps = windows.getOrPut(key) { ArrayDeque() }
        if (timestamps.size >= limit) {
            val oldest = timestamps.first()
            val retryAfter = ((oldest + windowMillis - now + 999) / 1000).coerceAtLeast(1)
            throw TooManyRequestsException("Too many messages", retryAfter)
        }
        timestamps.addLast(now)
    }

    internal val trackedWindowCount: Int
        @Synchronized get() = windows.size
}
