@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.main

import kotlin.js.ExperimentalWasmJsInterop

@JsFun("() => window.location.hash || ''")
private external fun readHashJs(): String

@JsFun(
    "(hash, replace) => { try {" +
        " if (replace) { window.history.replaceState(null, '', '#' + hash); }" +
        " else { window.history.pushState(null, '', '#' + hash); }" +
        " } catch (e) {} }"
)
private external fun writeHashJs(hash: String, replace: Boolean)

@JsFun(
    "(onChange) => {" +
        " window.addEventListener('popstate', () => onChange());" +
        " window.addEventListener('hashchange', () => onChange());" +
        "}"
)
private external fun listenHashJs(onChange: () -> Unit)

internal actual fun browserHashRead(): String = readHashJs()

internal actual fun browserHashWrite(hash: String, replace: Boolean) = writeHashJs(hash, replace)

internal actual fun browserHashListen(onChange: () -> Unit) = listenHashJs(onChange)
