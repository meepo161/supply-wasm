package org.example.project.ui.home

actual object BrowserNotifications {

    actual fun isSupported(): Boolean =
        js("typeof Notification !== 'undefined'") as? Boolean ?: false

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

    actual fun show(title: String, body: String) {
        if (!isGranted()) return
        try {
            js("new Notification(title, { body: body })")
        } catch (_: Throwable) {
        }
    }
}
