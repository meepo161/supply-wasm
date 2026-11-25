package org.example.project.realtime

import org.example.project.dto.ErpEventDomain

/**
 * Реестр онлайн-присутствия пользователей по открытым WebSocket-сессиям
 * (перенесено из supply2). Считает количество активных подключений каждого
 * пользователя и публикует событие [ErpEventDomain.PRESENCE] только при переходе
 * offline <-> online.
 */
object PresenceRegistry {
    private val lock = Any()
    private val sessions = HashMap<Long, Int>()

    fun connect(userId: Long, eventBus: ErpEventBus? = null) {
        val becameOnline = synchronized(lock) {
            val prev = sessions[userId] ?: 0
            sessions[userId] = prev + 1
            prev == 0
        }
        if (becameOnline) {
            eventBus?.publish(ErpEventDomain.PRESENCE, "ONLINE", userId)
        }
    }

    fun disconnect(userId: Long, eventBus: ErpEventBus? = null) {
        val becameOffline = synchronized(lock) {
            val prev = sessions[userId] ?: 0
            if (prev <= 1) {
                sessions.remove(userId)
                prev == 1
            } else {
                sessions[userId] = prev - 1
                false
            }
        }
        if (becameOffline) {
            eventBus?.publish(ErpEventDomain.PRESENCE, "OFFLINE", userId)
        }
    }

    fun onlineUserIds(): List<Long> = synchronized(lock) {
        sessions.keys.sorted()
    }
}
