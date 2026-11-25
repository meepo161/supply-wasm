package org.example.project

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.example.project.data.AuthRepository
import org.example.project.di.sharedModule
import org.example.project.ui.auth.AuthViewModel
import org.example.project.ui.auth.LoginScreen
import org.example.project.ui.main.MainScaffold
import org.example.project.ui.theme.AppBackground
import org.example.project.ui.theme.ErpTheme
import org.example.project.ui.theme.GradientSpinner
import org.example.project.ui.theme.LocalThemeController
import org.example.project.ui.theme.ThemeController
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject

@Composable
fun App() {
    val themeController = remember { ThemeController(dark = false) }
    CompositionLocalProvider(LocalThemeController provides themeController) {
        ErpTheme(darkTheme = themeController.isDark) {
            KoinApplication(application = { modules(sharedModule) }) {
                val repository: AuthRepository = koinInject()
                val viewModel = remember { AuthViewModel(repository) }
                val state by viewModel.uiState.collectAsState()

                val currentUser = state.currentUser
                AppBackground {
                    SelectionContainer {
                        AnimatedContent(
                            targetState = when {
                                state.isLoading -> "loading"
                                currentUser != null -> "app"
                                else -> "login"
                            },
                            transitionSpec = {
                                fadeIn(tween(260)) togetherWith fadeOut(tween(160))
                            },
                            label = "root"
                        ) { screen ->
                            when (screen) {
                                "loading" -> LoadingScreen()
                                "app" -> MainScaffold(
                                    user = currentUser!!,
                                    onLogout = viewModel::logout,
                                    passwordChangeLoading = state.passwordChangeLoading,
                                    passwordChangeError = state.passwordChangeError,
                                    onChangePassword = viewModel::changePassword,
                                    onClearPasswordError = viewModel::clearPasswordChangeState
                                )
                                else -> LoginScreen(
                                    state = state,
                                    onLogin = viewModel::login,
                                    onBootstrap = viewModel::bootstrap,
                                    onDismissError = viewModel::dismissError
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            GradientSpinner(size = 44.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Загрузка системы…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
