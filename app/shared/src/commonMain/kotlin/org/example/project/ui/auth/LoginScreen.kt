package org.example.project.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.example.project.dto.ApiError

@Composable
fun LoginScreen(
    state: AuthUiState,
    onLogin: (login: String, password: String) -> Unit,
    onBootstrap: (login: String, password: String, fullName: String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showBootstrap by remember { mutableStateOf(false) }
    var bootstrapLogin by remember { mutableStateOf("") }
    var bootstrapPassword by remember { mutableStateOf("") }
    var bootstrapFullName by remember { mutableStateOf("") }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "ERP «Авиаагрегат-Н»",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Вход в систему",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = login,
                onValueChange = {
                    login = it
                    if (state.error != null) onDismissError()
                },
                label = { Text("Логин") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading
            )

            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    if (state.error != null) onDismissError()
                },
                label = { Text("Пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading
            )

            state.error?.let { error ->
                ErrorMessage(error)
            }

            Button(
                onClick = { onLogin(login, password) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading && login.isNotBlank() && password.isNotBlank()
            ) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(4.dp)
                    )
                } else {
                    Text("Войти")
                }
            }

            TextButton(
                onClick = { showBootstrap = !showBootstrap },
                enabled = !state.isLoading
            ) {
                Text(
                    if (showBootstrap) "Скрыть создание администратора"
                    else "Создать первого администратора"
                )
            }

            if (showBootstrap) {
                BootstrapForm(
                    login = bootstrapLogin,
                    password = bootstrapPassword,
                    fullName = bootstrapFullName,
                    onLoginChange = { bootstrapLogin = it },
                    onPasswordChange = { bootstrapPassword = it },
                    onFullNameChange = { bootstrapFullName = it },
                    onSubmit = { onBootstrap(bootstrapLogin, bootstrapPassword, bootstrapFullName) },
                    isLoading = state.isLoading
                )
            }
        }
    }
}

@Composable
private fun ErrorMessage(error: ApiError) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = error.code,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = error.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun BootstrapForm(
    login: String,
    password: String,
    fullName: String,
    onLoginChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onFullNameChange: (String) -> Unit,
    onSubmit: () -> Unit,
    isLoading: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Первый администратор",
                style = MaterialTheme.typography.titleSmall
            )
            OutlinedTextField(
                value = login,
                onValueChange = onLoginChange,
                label = { Text("Логин") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )
            OutlinedTextField(
                value = fullName,
                onValueChange = onFullNameChange,
                label = { Text("Полное имя") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )
            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Пароль") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading
            )
            Button(
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && login.isNotBlank() && password.isNotBlank() && fullName.isNotBlank()
            ) {
                Text("Создать и войти")
            }
        }
    }
}
