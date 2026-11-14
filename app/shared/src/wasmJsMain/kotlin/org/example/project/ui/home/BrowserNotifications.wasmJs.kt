@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.home

import kotlin.js.ExperimentalWasmJsInterop

@JsFun("() => typeof Notification !== 'undefined'")
private external fun notificationSupportedJs(): Boolean

@JsFun("() => typeof Notification !== 'undefined' && Notification.permission === 'granted'")
private external fun notificationGrantedJs(): Boolean

@JsFun(
    "(onResult) => {" +
        " if (typeof Notification === 'undefined') { onResult(false); return; }" +
        " Notification.requestPermission().then(p => onResult(p === 'granted')).catch(() => onResult(false));" +
        "}"
)
private external fun requestNotificationPermissionJs(onResult: (Boolean) -> Unit)

@JsFun(
    "(title, body) => {" +
        " if (typeof Notification === 'undefined' || Notification.permission !== 'granted') return;" +
        " try { new Notification(title, { body: body }); } catch (e) {}" +
        "}"
)
private external fun showNotificationJs(title: String, body: String)

actual object BrowserNotifications {

    actual fun isSupported(): Boolean = notificationSupportedJs()

    actual fun isGranted(): Boolean = notificationGrantedJs()

    actual fun requestPermission(onResult: (Boolean) -> Unit) {
        requestNotificationPermissionJs(onResult)
    }

    actual fun show(title: String, body: String) {
        showNotificationJs(title, body)
    }
}
