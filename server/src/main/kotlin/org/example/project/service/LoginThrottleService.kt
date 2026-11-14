package org.example.project.service

import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Duration
import java.time.OffsetDateTime

class LoginThrottleService(private val dsl: DSLContext) {

    fun isBlocked(normalizedLogin: String, ipAddress: String): Pair<Boolean, Long> {
        return dsl.transactionResult { configuration ->
            val ctx = DSL.using(configuration)
            val cutoff = OffsetDateTime.now().minusMinutes(WINDOW_MINUTES)
            val count = ctx.fetchCount(
                ctx.selectFrom(LOGIN_ATTEMPTS)
                    .where(NORMALIZED_LOGIN.eq(normalizedLogin))
                    .and(IP_ADDRESS.eq(ipAddress))
                    .and(ATTEMPTED_AT.greaterOrEqual(cutoff))
            )
            if (count < MAX_FAILED_ATTEMPTS) {
                false to 0L
            } else {
                val lastAttempt = ctx.select(DSL.max(ATTEMPTED_AT))
                    .from(LOGIN_ATTEMPTS)
                    .where(NORMALIZED_LOGIN.eq(normalizedLogin))
                    .and(IP_ADDRESS.eq(ipAddress))
                    .and(ATTEMPTED_AT.greaterOrEqual(cutoff))
                    .fetchOneInto(OffsetDateTime::class.java)
                val retryAfter = if (lastAttempt != null) {
                    val elapsed = Duration.between(lastAttempt, OffsetDateTime.now()).seconds
                    maxOf(0L, LOCKOUT_SECONDS - elapsed)
                } else {
                    LOCKOUT_SECONDS
                }
                true to retryAfter
            }
        }
    }

    fun recordFailure(normalizedLogin: String, ipAddress: String) {
        dsl.transactionResult { configuration ->
            val ctx = DSL.using(configuration)
            val cutoff = OffsetDateTime.now().minusMinutes(WINDOW_MINUTES)
            ctx.deleteFrom(LOGIN_ATTEMPTS)
                .where(NORMALIZED_LOGIN.eq(normalizedLogin))
                .and(IP_ADDRESS.eq(ipAddress))
                .and(ATTEMPTED_AT.lessThan(cutoff))
                .execute()
            ctx.insertInto(LOGIN_ATTEMPTS)
                .set(NORMALIZED_LOGIN, normalizedLogin)
                .set(IP_ADDRESS, ipAddress)
                .set(ATTEMPTED_AT, OffsetDateTime.now())
                .execute()
        }
    }

    fun clearFailures(normalizedLogin: String, ipAddress: String) {
        dsl.execute(
            "DELETE FROM login_attempts WHERE normalized_login = ? AND ip_address = ?",
            normalizedLogin,
            ipAddress
        )
    }

    companion object {
        const val MAX_FAILED_ATTEMPTS = 5
        const val WINDOW_MINUTES = 15L
        const val LOCKOUT_MINUTES = 15L
        private val LOCKOUT_SECONDS = LOCKOUT_MINUTES * 60

        private val LOGIN_ATTEMPTS = DSL.table("login_attempts")
        private val NORMALIZED_LOGIN = DSL.field("normalized_login", String::class.java)
        private val IP_ADDRESS = DSL.field("ip_address", String::class.java)
        private val ATTEMPTED_AT = DSL.field("attempted_at", OffsetDateTime::class.java)
    }
}
