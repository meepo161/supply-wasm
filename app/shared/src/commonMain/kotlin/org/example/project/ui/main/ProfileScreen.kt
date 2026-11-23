package org.example.project.ui.main

import org.example.project.ui.userText
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.example.project.data.ApiResult
import org.example.project.data.UserRepository
import org.example.project.dto.ApiError
import org.example.project.dto.UpdateOwnProfileRequest
import org.example.project.dto.UserDto
import org.koin.compose.koinInject
import org.example.project.ui.projects.ErrorMessageCard
import org.example.project.ui.theme.Avatar
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.DetailRow
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
import org.example.project.ui.theme.GradientButton
import org.example.project.ui.theme.Gradients
import org.example.project.ui.theme.IconBadge
import org.example.project.ui.theme.LocalBrandPalette
import org.example.project.ui.theme.LocalThemeController
import org.example.project.ui.theme.PillBadge
import org.example.project.ui.theme.SoftButton

@Composable
fun ProfileScreen(
    user: UserDto,
    onLogout: () -> Unit,
    passwordChangeLoading: Boolean = false,
    passwordChangeError: ApiError? = null,
    onChangePassword: (currentPassword: String, newPassword: String, confirmPassword: String) -> Unit = { _, _, _ -> },
    onClearPasswordError: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val theme = LocalThemeController.current
    val palette = LocalBrandPalette.current
    val userRepository: UserRepository = koinInject()
    val scope = rememberCoroutineScope()
    var currentUser by remember(user) { mutableStateOf(user) }
    var editingProfile by remember { mutableStateOf(false) }
    var editFullName by remember(user) { mutableStateOf(user.fullName) }
    var editEmail by remember(user) { mutableStateOf(user.email ?: "") }
    var profileSaving by remember { mutableStateOf(false) }
    var profileError by remember { mutableStateOf<ApiError?>(null) }
    var profileSavedMsg by remember { mutableStateOf<String?>(null) }
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Профильная шапка
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Brush.linearGradient(Gradients.Sidebar))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .padding(20.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(name = user.fullName, size = 72.dp)
                    Spacer(Modifier.width(20.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = user.fullName,
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "@${user.login}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.8f)
                            )
                            user.email?.takeIf { it.isNotBlank() }?.let {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }

            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    GlassCard(modifier = Modifier.weight(1f)) {
                        SectionTitle("Учётная запись", ErpIconGlyph.User)
                        Spacer(Modifier.height(12.dp))
                        DetailRow("Логин", currentUser.login)
                        if (!editingProfile) {
                            DetailRow("Полное имя", currentUser.fullName)
                            currentUser.email?.takeIf { it.isNotBlank() }?.let {
                                DetailRow("Email", it)
                            }
                            profileSavedMsg?.let {
                                Spacer(Modifier.height(6.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(Modifier.height(10.dp))
                            SoftButton(
                                text = "Редактировать профиль",
                                onClick = {
                                    editFullName = currentUser.fullName
                                    editEmail = currentUser.email ?: ""
                                    profileError = null
                                    profileSavedMsg = null
                                    editingProfile = true
                                }
                            )
                        } else {
                            profileError?.let {
                                ErrorMessageCard(error = it)
                                Spacer(Modifier.height(8.dp))
                            }
                            OutlinedTextField(
                                value = editFullName,
                                onValueChange = { editFullName = it },
                                label = { Text("Полное имя *") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = editEmail,
                                onValueChange = { editEmail = it },
                                label = { Text("Email") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GradientButton(
                                    text = if (profileSaving) "Сохранение…" else "Сохранить",
                                    enabled = !profileSaving && editFullName.isNotBlank(),
                                    onClick = {
                                        profileSaving = true
                                        profileError = null
                                        scope.launch {
                                            when (val res = userRepository.updateOwnProfile(
                                                UpdateOwnProfileRequest(
                                                    fullName = editFullName.trim(),
                                                    email = editEmail.trim().ifBlank { null },
                                                    version = currentUser.version
                                                )
                                            )) {
                                                is ApiResult.Success -> {
                                                    currentUser = res.data
                                                    editingProfile = false
                                                    profileSaving = false
                                                    profileSavedMsg = "Профиль обновлён"
                                                }
                                                is ApiResult.Error -> {
                                                    profileError = res.error
                                                    profileSaving = false
                                                }
                                            }
                                        }
                                    }
                                )
                                SoftButton(text = "Отмена", onClick = { editingProfile = false })
                            }
                        }
                    }
                    GlassCard(modifier = Modifier.weight(1f)) {
                        SectionTitle("Оформление", if (theme.isDark) ErpIconGlyph.Moon else ErpIconGlyph.Sun)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (theme.isDark) "Включена тёмная тема."
                            else "Включена светлая тема.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(14.dp))
                        SoftButton(
                            text = if (theme.isDark) "Светлая тема" else "Тёмная тема",
                            onClick = { theme.toggle() },
                            leadingIcon = if (theme.isDark) ErpIconGlyph.Sun else ErpIconGlyph.Moon
                        )
                    }
                }
            } else {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    SectionTitle("Учётная запись", ErpIconGlyph.User)
                    Spacer(Modifier.height(12.dp))
                    DetailRow("Логин", user.login)
                    DetailRow("Полное имя", user.fullName)
                    user.email?.takeIf { it.isNotBlank() }?.let {
                        DetailRow("Email", it)
                    }
                    Spacer(Modifier.height(12.dp))
                    SoftButton(
                        text = if (theme.isDark) "Светлая тема" else "Тёмная тема",
                        onClick = { theme.toggle() },
                        leadingIcon = if (theme.isDark) ErpIconGlyph.Sun else ErpIconGlyph.Moon
                    )
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                val extrasRepo: org.example.project.data.ErpExtrasRepository = org.koin.compose.koinInject()
                val delScope = rememberCoroutineScope()
                var delegations by remember { mutableStateOf<List<org.example.project.dto.DelegationDto>>(emptyList()) }
                var delegateIdInput by remember { mutableStateOf("") }
                var endsAtInput by remember { mutableStateOf("2026-12-31T23:59:59Z") }
                var delReason by remember { mutableStateOf("") }
                var delMsg by remember { mutableStateOf<String?>(null) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val res = extrasRepo.listDelegations()
                    if (res is ApiResult.Success) delegations = res.data
                }
                SectionTitle("Делегирование полномочий", ErpIconGlyph.User)
                Spacer(Modifier.height(8.dp))
                delMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                delegations.forEach { d ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${d.delegatorName} → ${d.delegateName} (до ${d.endsAt}, ${if (d.active) "активно" else "отозвано"})",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        if (d.active && d.delegatorId == user.id) {
                            SoftButton(text = "Отозвать", onClick = {
                                delScope.launch {
                                    if (extrasRepo.revokeDelegation(d.id) is ApiResult.Success) {
                                        delegations = delegations.map { if (it.id == d.id) it.copy(active = false) else it }
                                    }
                                }
                            })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = delegateIdInput,
                        onValueChange = { delegateIdInput = it },
                        label = { Text("ID заместителя") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    org.example.project.ui.theme.ErpDateField(
                        value = endsAtInput.substringBefore('T'),
                        onValueChange = { endsAtInput = it },
                        label = "Действует до",
                        modifier = Modifier.weight(1.4f)
                    )
                    SoftButton(text = "Назначить", onClick = {
                        val targetId = delegateIdInput.trim().toLongOrNull()
                        if (targetId != null) {
                            val rawDate = endsAtInput.trim()
                            val isoEndsAt = if (rawDate.length == 10 && !rawDate.contains('T')) "${rawDate}T23:59:59Z" else rawDate
                            delScope.launch {
                                when (val res = extrasRepo.createDelegation(
                                    org.example.project.dto.CreateDelegationRequest(
                                        delegateId = targetId,
                                        endsAt = isoEndsAt,
                                        reason = delReason.ifBlank { null }
                                    )
                                )) {
                                    is ApiResult.Success -> {
                                        delegations = listOf(res.data) + delegations
                                        delegateIdInput = ""
                                        delMsg = "Делегирование создано"
                                    }
                                    is ApiResult.Error -> delMsg = res.error.userText()
                                }
                            }
                        }
                    })
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                var browserNotifyEnabled by remember { mutableStateOf(true) }
                var soundNotifyEnabled by remember { mutableStateOf(false) }
                var mentionOnlyNotify by remember { mutableStateOf(false) }
                SectionTitle("Настройки уведомлений", ErpIconGlyph.Bell)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Браузерные push-уведомления (нейтральный текст)", style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Switch(
                        checked = browserNotifyEnabled,
                        onCheckedChange = { browserNotifyEnabled = it }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Звуковое оповещение при новых сообщениях", style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Switch(
                        checked = soundNotifyEnabled,
                        onCheckedChange = { soundNotifyEnabled = it }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Уведомлять только об @упоминаниях", style = MaterialTheme.typography.bodyMedium)
                    androidx.compose.material3.Switch(
                        checked = mentionOnlyNotify,
                        onCheckedChange = { mentionOnlyNotify = it }
                    )
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                SectionTitle("Безопасность и смена пароля", ErpIconGlyph.Shield)
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Пароль должен содержать не менее 6 символов. После смены пароля все активные сеансы будут завершены, потребуется войти заново.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                passwordChangeError?.let {
                    ErrorMessageCard(error = it)
                    Spacer(Modifier.height(10.dp))
                }
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = currentPassword,
                        onValueChange = {
                            currentPassword = it
                            if (passwordChangeError != null) onClearPasswordError()
                        },
                        label = { Text("Текущий пароль") },
                        singleLine = true,
                        enabled = !passwordChangeLoading,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = {
                            newPassword = it
                            if (passwordChangeError != null) onClearPasswordError()
                        },
                        label = { Text("Новый пароль (от 6 символов)") },
                        singleLine = true,
                        enabled = !passwordChangeLoading,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = {
                            confirmPassword = it
                            if (passwordChangeError != null) onClearPasswordError()
                        },
                        label = { Text("Подтверждение нового пароля") },
                        singleLine = true,
                        enabled = !passwordChangeLoading,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    GradientButton(
                        text = if (passwordChangeLoading) "Сохранение…" else "Сменить пароль",
                        onClick = { onChangePassword(currentPassword, newPassword, confirmPassword) },
                        enabled = !passwordChangeLoading && currentPassword.isNotBlank() && newPassword.isNotBlank() && confirmPassword.isNotBlank(),
                        leadingIcon = ErpIconGlyph.Shield
                    )
                }
            }

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                gradient = if (palette.isDark) null else listOf(
                    MaterialTheme.colorScheme.surfaceContainerLowest,
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.05f)
                )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Завершить сеанс",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Вы выйдете из системы на этом устройстве",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    SoftButton(
                        text = "Выйти",
                        onClick = onLogout,
                        leadingIcon = ErpIconGlyph.Logout,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, glyph: ErpIconGlyph) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(glyph, gradient = Gradients.Ocean, size = 30.dp, iconSize = 15.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}
