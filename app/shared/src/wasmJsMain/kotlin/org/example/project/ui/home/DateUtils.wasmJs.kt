@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.home

import kotlin.js.ExperimentalWasmJsInterop

@JsFun("() => new Date().toISOString().slice(0,10)")
private external fun currentIsoDateJs(): String

actual fun currentIsoDate(): String = currentIsoDateJs()
