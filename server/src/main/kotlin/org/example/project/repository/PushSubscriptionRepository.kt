package org.example.project.repository

import org.example.project.generated.jooq.tables.ChatPushSubscriptions.CHAT_PUSH_SUBSCRIPTIONS
import org.jooq.DSLContext

data class PushSubscription(
    val id: Long,
    val userId: Long,
    val endpoint: String,
    val p256dh: String,
    val auth: String
)

interface PushSubscriptionStore {
    fun listForUser(userId: Long): List<PushSubscription>
    fun findByEndpoint(endpoint: String): PushSubscription?
    fun findById(id: Long): PushSubscription?
    fun insert(userId: Long, endpoint: String, p256dh: String, auth: String): PushSubscription
    fun update(userId: Long, id: Long, p256dh: String, auth: String): PushSubscription
    fun deleteOwned(id: Long, userId: Long): Boolean
    fun deleteById(id: Long): Boolean
}

class PushSubscriptionRepository(private val dsl: DSLContext) : PushSubscriptionStore {

    override fun listForUser(userId: Long): List<PushSubscription> =
        dsl.selectFrom(CHAT_PUSH_SUBSCRIPTIONS)
            .where(CHAT_PUSH_SUBSCRIPTIONS.USER_ID.eq(userId))
            .orderBy(CHAT_PUSH_SUBSCRIPTIONS.ID.asc())
            .fetch { it.toSubscription() }

    override fun findByEndpoint(endpoint: String): PushSubscription? =
        dsl.selectFrom(CHAT_PUSH_SUBSCRIPTIONS)
            .where(CHAT_PUSH_SUBSCRIPTIONS.ENDPOINT.eq(endpoint))
            .fetchOne()?.toSubscription()

    override fun findById(id: Long): PushSubscription? =
        dsl.selectFrom(CHAT_PUSH_SUBSCRIPTIONS)
            .where(CHAT_PUSH_SUBSCRIPTIONS.ID.eq(id))
            .fetchOne()?.toSubscription()

    override fun insert(userId: Long, endpoint: String, p256dh: String, auth: String): PushSubscription =
        dsl.insertInto(CHAT_PUSH_SUBSCRIPTIONS)
            .set(CHAT_PUSH_SUBSCRIPTIONS.USER_ID, userId)
            .set(CHAT_PUSH_SUBSCRIPTIONS.ENDPOINT, endpoint)
            .set(CHAT_PUSH_SUBSCRIPTIONS.P256DH, p256dh)
            .set(CHAT_PUSH_SUBSCRIPTIONS.AUTH, auth)
            .returning()
            .fetchOne()!!
            .toSubscription()

    override fun update(userId: Long, id: Long, p256dh: String, auth: String): PushSubscription =
        dsl.update(CHAT_PUSH_SUBSCRIPTIONS)
            .set(CHAT_PUSH_SUBSCRIPTIONS.P256DH, p256dh)
            .set(CHAT_PUSH_SUBSCRIPTIONS.AUTH, auth)
            .where(CHAT_PUSH_SUBSCRIPTIONS.ID.eq(id))
            .and(CHAT_PUSH_SUBSCRIPTIONS.USER_ID.eq(userId))
            .returning()
            .fetchOne()!!
            .toSubscription()

    override fun deleteOwned(id: Long, userId: Long): Boolean =
        dsl.deleteFrom(CHAT_PUSH_SUBSCRIPTIONS)
            .where(CHAT_PUSH_SUBSCRIPTIONS.ID.eq(id))
            .and(CHAT_PUSH_SUBSCRIPTIONS.USER_ID.eq(userId))
            .execute() > 0

    override fun deleteById(id: Long): Boolean =
        dsl.deleteFrom(CHAT_PUSH_SUBSCRIPTIONS)
            .where(CHAT_PUSH_SUBSCRIPTIONS.ID.eq(id))
            .execute() > 0

    private fun org.example.project.generated.jooq.tables.records.ChatPushSubscriptionsRecord.toSubscription() =
        PushSubscription(
            id = id!!,
            userId = userId!!,
            endpoint = endpoint!!,
            p256dh = p256dh!!,
            auth = auth!!
        )
}
