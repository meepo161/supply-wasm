package org.example.project

import org.example.project.exception.TooManyRequestsException
import org.example.project.service.MessageRateLimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MessageRateLimiterTest {
    @Test
    fun `limits each user and chat independently within a fixed window`() {
        var now = 1_000L
        val limiter = MessageRateLimiter(limit = 2, windowMillis = 1_000, clock = { now })

        limiter.check(1, "task:10")
        limiter.check(1, "task:10")
        val rejected = assertFailsWith<TooManyRequestsException> { limiter.check(1, "task:10") }
        assertEquals("TOO_MANY_REQUESTS", rejected.code)
        assertTrue(rejected.retryAfterSeconds > 0)

        limiter.check(2, "task:10")
        limiter.check(1, "conversation:20")

        now += 1_000
        limiter.check(1, "task:10")
    }

    @Test
    fun `expired windows are removed when new keys arrive`() {
        var now = 0L
        val limiter = MessageRateLimiter(limit = 10, windowMillis = 100, clock = { now })
        limiter.check(1, "task:1")
        limiter.check(2, "task:2")

        now = 100
        limiter.check(3, "task:3")

        assertEquals(1, limiter.trackedWindowCount)
    }
}
