package org.example.project

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject

@Composable
fun App() {
    KoinApplication(application = { modules(sharedModule) }) {
        MaterialTheme {
            val repository: AuthRepository = koinInject()
            val viewModel = remember { AuthViewModel(repository) }
            val state by viewModel.uiState.collectAsState()

            val currentUser = state.currentUser
            when {
                state.isLoading -> LoadingIndicator()
                currentUser != null -> MainScaffold(
                    user = currentUser,
                    onLogout = viewModel::logout
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

@Composable
private fun LoadingIndicator() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}
