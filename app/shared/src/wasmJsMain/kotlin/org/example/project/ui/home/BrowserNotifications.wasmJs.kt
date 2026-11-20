@file:OptIn(ExperimentalWasmJsInterop::class)

package org.example.project.ui.home

import kotlin.js.ExperimentalWasmJsInterop

@JsFun("() => typeof Notification !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window")
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

@JsFun(
    "(onResult) => {" +
        " if (typeof Notification === 'undefined') { onResult(false); return; }" +
        " Notification.requestPermission().then(async p => {" +
        "   if (p !== 'granted' || !window.erpPush) { onResult(false); return; }" +
        "   try { onResult(await window.erpPush.enable()); } catch (e) { onResult(false); }" +
        " }).catch(() => onResult(false));" +
        "}"
)
private external fun enablePushJs(onResult: (Boolean) -> Unit)

@JsFun("() => window.erpPush ? String(window.erpPush.consumePendingOpen() || '') : ''")
private external fun consumePendingEventIdJs(): String

@JsFun("(onPending) => window.erpPush && window.erpPush.setOpenListener(onPending)")
private external fun setPendingOpenListenerJs(onPending: () -> Unit)

actual object BrowserNotifications {

    actual fun isSupported(): Boolean = notificationSupportedJs()

    actual fun isGranted(): Boolean = notificationGrantedJs()

    actual fun requestPermission(onResult: (Boolean) -> Unit) {
        requestNotificationPermissionJs(onResult)
    }

    actual fun enablePush(onResult: (Boolean) -> Unit) {
        enablePushJs(onResult)
    }

    actual fun consumePendingEventId(): Long? =
        consumePendingEventIdJs().toLongOrNull()?.takeIf { it > 0L }

    actual fun setPendingOpenListener(onPending: () -> Unit) {
        setPendingOpenListenerJs(onPending)
    }

    actual fun show(title: String, body: String) {
        showNotificationJs(title, body)
    }
}
