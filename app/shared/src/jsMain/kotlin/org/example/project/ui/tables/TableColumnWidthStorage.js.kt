package org.example.project.ui.tables

internal actual object TableColumnWidthStorage {
    actual fun read(key: String): String? = try {
        js("window.localStorage.getItem(key)") as? String
    } catch (_: Throwable) {
        null
    }

    actual fun write(key: String, value: String) {
        try {
            js("window.localStorage.setItem(key, value)")
        } catch (_: Throwable) {
        }
    }
}
