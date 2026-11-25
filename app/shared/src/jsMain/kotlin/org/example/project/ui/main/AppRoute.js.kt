package org.example.project.ui.main

internal actual fun browserHashRead(): String = try {
    js("window.location.hash || ''") as? String ?: ""
} catch (_: Throwable) {
    ""
}

internal actual fun browserHashWrite(hash: String, replace: Boolean) {
    try {
        if (replace) js("window.history.replaceState(null, '', '#' + hash)")
        else js("window.history.pushState(null, '', '#' + hash)")
    } catch (_: Throwable) {
    }
}

internal actual fun browserHashListen(onChange: () -> Unit) {
    try {
        val handler: () -> Unit = onChange
        js("window.addEventListener('popstate', function () { handler(); })")
        js("window.addEventListener('hashchange', function () { handler(); })")
    } catch (_: Throwable) {
    }
}
