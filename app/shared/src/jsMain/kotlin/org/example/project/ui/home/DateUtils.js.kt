package org.example.project.ui.home

actual fun currentIsoDate(): String = js("new Date().toISOString().slice(0,10)") as String
