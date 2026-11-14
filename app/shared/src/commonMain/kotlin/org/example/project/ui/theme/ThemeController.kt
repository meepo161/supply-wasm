package org.example.project.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Простое состояние темы оформления. Приложение однoоконное, поэтому
 * достаточно глобального состояния, доступного через CompositionLocal.
 */
class ThemeController(dark: Boolean = false) {
    var isDark by mutableStateOf(dark)
        private set

    fun toggle() {
        isDark = !isDark
    }

    fun setDark(value: Boolean) {
        isDark = value
    }
}

val LocalThemeController = staticCompositionLocalOf { ThemeController() }

@Composable
fun rememberThemeController(): ThemeController = LocalThemeController.current
