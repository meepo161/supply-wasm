package org.example.project.ui.auth

import org.example.project.ui.userText
import org.example.project.ui.userTitle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.example.project.dto.ApiError
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.IconBadge
import org.example.project.ui.theme.LocalBrandPalette
import org.example.project.ui.theme.SoftButton

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

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 980.dp
        Row(modifier = Modifier.fillMaxSize()) {
            if (wide) {
                BrandPanel(
                    modifier = Modifier
                        .weight(1.05f)
                        .fillMaxHeight()
                        .padding(20.dp)
                )
            }
            Box(
                modifier = Modifier
                    .weight(if (wide) 0.95f else 1f)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 430.dp)
                        .padding(horizontal = 26.dp, vertical = 32.dp)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (!wide) {
                        LogoMark()
                        Spacer(Modifier.height(18.dp))
                    }
                    Text(
                        text = if (showBootstrap) "Новое пространство" else "С возвращением",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (showBootstrap)
                            "Создайте первого администратора системы"
                        else
                            "Войдите, чтобы продолжить работу с системой предприятия",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))

                    GlassCard(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = 22.dp,
                        gradient = null
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            AppTextField(
                                value = login,
                                onValueChange = {
                                    login = it
                                    if (state.error != null) onDismissError()
                                },
                                label = "Логин",
                                glyph = ErpIconGlyph.User,
                                enabled = !state.isLoading,
                                imeAction = ImeAction.Next
                            )
                            AppTextField(
                                value = password,
                                onValueChange = {
                                    password = it
                                    if (state.error != null) onDismissError()
                                },
                                label = "Пароль",
                                glyph = ErpIconGlyph.Shield,
                                enabled = !state.isLoading,
                                isPassword = true,
                                imeAction = ImeAction.Done,
                                onDone = {
                                    if (login.isNotBlank() && password.isNotBlank()) {
                                        onLogin(login, password)
                                    }
                                }
                            )

                            state.error?.let { error -> ErrorBanner(error) }
                            if (state.passwordChangeSuccess) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Brand.Mint.copy(alpha = 0.14f))
                                        .border(1.dp, Brand.Mint.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Пароль успешно изменён. Войдите с новым паролем.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Brand.Teal
                                    )
                                }
                            }

                            GradientButton(
                                text = if (state.isLoading) "Подключаемся…" else "Войти в систему",
                                onClick = { onLogin(login, password) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.isLoading && login.isNotBlank() && password.isNotBlank(),
                            )
                            if (state.isLoading) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = MaterialTheme.colorScheme.primary,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (state.bootstrapRequired || showBootstrap) {
                        Spacer(Modifier.height(18.dp))

                        SoftButton(
                            text = if (showBootstrap) "Вернуться ко входу" else "Создать первого администратора",
                            onClick = { showBootstrap = !showBootstrap },
                            enabled = !state.isLoading,
                            leadingIcon = if (showBootstrap) ErpIconGlyph.ChevronLeft else ErpIconGlyph.Plus
                        )
                    }

                    if (showBootstrap) {
                        Spacer(Modifier.height(18.dp))
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

                    Spacer(Modifier.height(28.dp))
                    Text(
                        text = "ООО «Авиаагрегат-Н» · Единая информационная система",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun BrandPanel(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Gradients.Sidebar.first())
            .padding(36.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Логотип не утверждён, поэтому вместо знака стоит название предприятия текстом.
        Column {
            Text(
                text = "Авиаагрегат-Н",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )
            Text(
                text = "Единая ERP-платформа",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.72f)
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            // Короткая сигнальная черта: тот же янтарный маркер, что отмечает текущий раздел в меню.
            Box(
                modifier = Modifier
                    .size(width = 48.dp, height = 3.dp)
                    .background(Brand.Signal)
            )
            Text(
                text = "От потребности\nдо готового изделия",
                style = MaterialTheme.typography.displaySmall,
                color = Color.White
            )
            Text(
                text = "Проекты, задачи, склад, снабжение и документы в одном связанном контуре: с историей изменений и понятными статусами.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.78f)
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FeaturePill("Задачи и приёмка", ErpIconGlyph.Tasks)
                FeaturePill("Склад и BOM", ErpIconGlyph.Layers)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FeaturePill("Снабжение", ErpIconGlyph.Cart)
                FeaturePill("Документы", ErpIconGlyph.Documents)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ErpIcon(ErpIconGlyph.Shield, Color.White.copy(alpha = 0.8f), Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Доступ к разделам определяется ролью и правами пользователя",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.72f)
            )
        }
    }
}

@Composable
private fun FeaturePill(text: String, glyph: ErpIconGlyph) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ErpIcon(glyph, Color.White.copy(alpha = 0.8f), Modifier.size(14.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f)
        )
    }
}

/** На узком экране вместо неутверждённого логотипа показывается название предприятия. */
@Composable
private fun LogoMark() {
    Text(
        text = "Авиаагрегат-Н",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    glyph: ErpIconGlyph,
    enabled: Boolean,
    isPassword: Boolean = false,
    imeAction: ImeAction = ImeAction.Next,
    onDone: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    val palette = LocalBrandPalette.current
    var passwordVisible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        leadingIcon = {
            ErpIcon(glyph, scheme.onSurfaceVariant, Modifier.size(18.dp))
        },
        trailingIcon = if (isPassword) {
            {
                org.example.project.ui.theme.TextButton(
                    onClick = { passwordVisible = !passwordVisible },
                    enabled = enabled
                ) {
                    Text(
                        text = if (passwordVisible) "Скрыть" else "Показать",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.primary
                    )
                }
            }
        } else null,
        visualTransformation = if (isPassword && !passwordVisible) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        shape = RoundedCornerShape(8.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = scheme.primary,
            unfocusedBorderColor = scheme.outline.copy(alpha = 0.7f),
            focusedContainerColor = if (palette.isDark) scheme.surfaceContainerHigh else scheme.surfaceContainerLow,
            unfocusedContainerColor = if (palette.isDark) scheme.surfaceContainer.copy(alpha = 0.6f) else scheme.surfaceContainerLow.copy(alpha = 0.7f),
            focusedLabelColor = scheme.primary,
            unfocusedLabelColor = scheme.onSurfaceVariant
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ErrorBanner(error: ApiError) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f))
            .border(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ErpIcon(
            ErpIconGlyph.Alert,
            MaterialTheme.colorScheme.error,
            Modifier.size(18.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text = error.userTitle(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error
            )
            Text(
                text = error.userText(),
                style = MaterialTheme.typography.bodySmall,
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
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 20.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(ErpIconGlyph.Shield, gradient = Gradients.Aurora, size = 32.dp, iconSize = 16.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Первый администратор",
                style = MaterialTheme.typography.titleSmall
            )
        }
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AppTextField(
                value = fullName,
                onValueChange = onFullNameChange,
                label = "Полное имя",
                glyph = ErpIconGlyph.User,
                enabled = !isLoading
            )
            AppTextField(
                value = login,
                onValueChange = onLoginChange,
                label = "Логин",
                glyph = ErpIconGlyph.User,
                enabled = !isLoading
            )
            AppTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = "Пароль",
                glyph = ErpIconGlyph.Shield,
                enabled = !isLoading,
                isPassword = true,
                imeAction = ImeAction.Done,
                onDone = onSubmit
            )
            GradientButton(
                text = "Создать и войти",
                onClick = onSubmit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !isLoading && login.isNotBlank() && password.isNotBlank() && fullName.isNotBlank(),
                gradient = Gradients.Aurora
            )
        }
    }
}
