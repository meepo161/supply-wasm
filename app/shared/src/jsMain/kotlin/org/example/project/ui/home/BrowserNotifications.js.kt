package org.example.project.ui.home

actual object BrowserNotifications {

    actual fun isSupported(): Boolean =
        js("typeof Notification !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window") as? Boolean ?: false

    actual fun isGranted(): Boolean =
        if (isSupported()) js("Notification.permission === 'granted'") as? Boolean ?: false else false

    actual fun requestPermission(onResult: (Boolean) -> Unit) {
        if (!isSupported()) {
            onResult(false)
            return
        }
        try {
            val promise = js("Notification.requestPermission()")
            promise.then({ permission: String ->
                onResult(permission == "granted")
            }, { _ ->
                onResult(false)
            })
        } catch (_: Throwable) {
            onResult(false)
        }
    }

    actual fun enablePush(onResult: (Boolean) -> Unit) {
        if (!isSupported()) {
            onResult(false)
            return
        }
        try {
            val promise = js(
                "Notification.requestPermission().then(permission => {" +
                    " if (permission !== 'granted' || !window.erpPush) return false;" +
                    " return window.erpPush.enable();" +
                    " })"
            )
            promise.then({ enabled: Boolean -> onResult(enabled) }, { _ -> onResult(false) })
        } catch (_: Throwable) {
            onResult(false)
        }
    }

    actual fun consumePendingEventId(): Long? = try {
        (js("window.erpPush ? String(window.erpPush.consumePendingOpen() || '') : ''") as String)
            .toLongOrNull()
            ?.takeIf { it > 0L }
    } catch (_: Throwable) {
        null
    }

    actual fun setPendingOpenListener(onPending: () -> Unit) {
        if (js("typeof window.erpPush !== 'undefined'") as Boolean) {
            js("window.erpPush.setOpenListener(onPending)")
        }
    }

    actual fun show(title: String, body: String) {
        if (!isGranted()) return
        try {
            js("new Notification(title, { body: body })")
        } catch (_: Throwable) {
        }
    }
}
