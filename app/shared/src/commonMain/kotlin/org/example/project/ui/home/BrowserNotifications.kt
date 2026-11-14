package org.example.project.ui.home

expect object BrowserNotifications {
    fun isSupported(): Boolean
    fun isGranted(): Boolean
    fun requestPermission(onResult: (Boolean) -> Unit)
    fun show(title: String, body: String)
}
