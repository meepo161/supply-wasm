package org.example.project.ui.main

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.example.project.ui.tables.TableColumnWidthStorage

data class UserUiPreferences(
    val soundNotifications: Boolean = true,
    val mentionOnlyNotifications: Boolean = false,
    val sendMessageOnEnter: Boolean = true,
    val groupChatMessages: Boolean = true,
    val showOnlinePresence: Boolean = true,
    val autoOpenFirstChat: Boolean = true,
    val tasksMineOnlyByDefault: Boolean = false,
    val tasksOverdueFirst: Boolean = true,
    val compactTables: Boolean = false,
    val startDestination: String = "HOME"
)

object UserPreferencesStore {
    private const val STORAGE_KEY = "erp.user-preferences"
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<UserUiPreferences> = _state.asStateFlow()

    fun update(transform: (UserUiPreferences) -> UserUiPreferences) {
        val next = transform(_state.value)
        _state.value = next
        save(next)
    }

    fun reset() {
        val def = UserUiPreferences()
        _state.value = def
        save(def)
    }

    private fun save(prefs: UserUiPreferences) {
        val map = mapOf(
            "soundNotifications" to prefs.soundNotifications.toString(),
            "mentionOnlyNotifications" to prefs.mentionOnlyNotifications.toString(),
            "sendMessageOnEnter" to prefs.sendMessageOnEnter.toString(),
            "groupChatMessages" to prefs.groupChatMessages.toString(),
            "showOnlinePresence" to prefs.showOnlinePresence.toString(),
            "autoOpenFirstChat" to prefs.autoOpenFirstChat.toString(),
            "tasksMineOnlyByDefault" to prefs.tasksMineOnlyByDefault.toString(),
            "tasksOverdueFirst" to prefs.tasksOverdueFirst.toString(),
            "compactTables" to prefs.compactTables.toString(),
            "startDestination" to prefs.startDestination
        )
        runCatching {
            TableColumnWidthStorage.write(STORAGE_KEY, json.encodeToString(map))
        }
    }

    private fun load(): UserUiPreferences {
        val raw = runCatching { TableColumnWidthStorage.read(STORAGE_KEY) }.getOrNull()
        if (raw.isNullOrBlank()) return UserUiPreferences()
        val map = runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
        val def = UserUiPreferences()
        return UserUiPreferences(
            soundNotifications = map["soundNotifications"]?.toBooleanStrictOrNull() ?: def.soundNotifications,
            mentionOnlyNotifications = map["mentionOnlyNotifications"]?.toBooleanStrictOrNull() ?: def.mentionOnlyNotifications,
            sendMessageOnEnter = map["sendMessageOnEnter"]?.toBooleanStrictOrNull() ?: def.sendMessageOnEnter,
            groupChatMessages = map["groupChatMessages"]?.toBooleanStrictOrNull() ?: def.groupChatMessages,
            showOnlinePresence = map["showOnlinePresence"]?.toBooleanStrictOrNull() ?: def.showOnlinePresence,
            autoOpenFirstChat = map["autoOpenFirstChat"]?.toBooleanStrictOrNull() ?: def.autoOpenFirstChat,
            tasksMineOnlyByDefault = map["tasksMineOnlyByDefault"]?.toBooleanStrictOrNull() ?: def.tasksMineOnlyByDefault,
            tasksOverdueFirst = map["tasksOverdueFirst"]?.toBooleanStrictOrNull() ?: def.tasksOverdueFirst,
            compactTables = map["compactTables"]?.toBooleanStrictOrNull() ?: def.compactTables,
            startDestination = map["startDestination"] ?: def.startDestination
        )
    }
}
