@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.tables

import kotlin.js.ExperimentalWasmJsInterop

@JsFun("(key) => { try { return window.localStorage.getItem(key); } catch (e) { return null; } }")
private external fun readStorage(key: String): String?

@JsFun("(key, value) => { try { window.localStorage.setItem(key, value); } catch (e) {} }")
private external fun writeStorage(key: String, value: String)

internal actual object TableColumnWidthStorage {
    actual fun read(key: String): String? = readStorage(key)
    actual fun write(key: String, value: String) = writeStorage(key, value)
}
