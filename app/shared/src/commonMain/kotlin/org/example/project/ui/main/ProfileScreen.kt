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
import org.example.project.ui.theme.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
                var userOptions by remember { mutableStateOf<List<org.example.project.dto.UserOptionDto>>(emptyList()) }
                var selectedDelegate by remember { mutableStateOf<org.example.project.dto.UserOptionDto?>(null) }
                var delegateMenuOpen by remember { mutableStateOf(false) }
                var endsAtInput by remember { mutableStateOf("2026-12-31T23:59:59Z") }
                var delReason by remember { mutableStateOf("") }
                var delMsg by remember { mutableStateOf<String?>(null) }
                androidx.compose.runtime.LaunchedEffect(Unit) {
                    val res = extrasRepo.listDelegations()
                    if (res is ApiResult.Success) delegations = res.data
                    val usersRes = userRepository.listOptions()
                    if (usersRes is ApiResult.Success) {
                        userOptions = usersRes.data.filter { it.id != user.id }
                    }
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
                            "${d.delegatorName} → ${d.delegateName} (до ${org.example.project.ui.theme.ErpFormat.dateTimeRu(d.endsAt)}, ${if (d.active) "активно" else "отозвано"})",
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
                    Box(modifier = Modifier.weight(1f)) {
                        SoftButton(
                            text = selectedDelegate?.fullName ?: "Выбрать заместителя…",
                            onClick = { delegateMenuOpen = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                        androidx.compose.material3.DropdownMenu(
                            expanded = delegateMenuOpen,
                            onDismissRequest = { delegateMenuOpen = false }
                        ) {
                            userOptions.forEach { opt ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(opt.fullName) },
                                    onClick = {
                                        selectedDelegate = opt
                                        delegateMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    org.example.project.ui.theme.ErpDateField(
                        value = endsAtInput.substringBefore('T'),
                        onValueChange = { endsAtInput = it },
                        label = "Действует до",
                        modifier = Modifier.weight(1.2f)
                    )
                    SoftButton(
                        text = "Назначить",
                        enabled = selectedDelegate != null,
                        onClick = {
                            val targetId = selectedDelegate?.id ?: return@SoftButton
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
                                        selectedDelegate = null
                                        delMsg = "Делегирование создано"
                                    }
                                    is ApiResult.Error -> delMsg = res.error.userText()
                                }
                            }
                        }
                    )
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                val prefs by UserPreferencesStore.state.collectAsState()
                var browserNotifyEnabled by remember { mutableStateOf(true) }
                SectionTitle("Настройки уведомлений и звука", ErpIconGlyph.Bell)
                Spacer(Modifier.height(8.dp))
                SettingSwitchRow(
                    title = "Браузерные push-уведомления (нейтральный текст)",
                    checked = browserNotifyEnabled,
                    onCheckedChange = { browserNotifyEnabled = it }
                )
                SettingSwitchRow(
                    title = "Звуковое оповещение при новых сообщениях",
                    checked = prefs.soundNotifications,
                    onCheckedChange = { v -> UserPreferencesStore.update { it.copy(soundNotifications = v) } }
                )
                SettingSwitchRow(
                    title = "Уведомлять только об @упоминаниях",
                    checked = prefs.mentionOnlyNotifications,
                    onCheckedChange = { v -> UserPreferencesStore.update { it.copy(mentionOnlyNotifications = v) } }
                )
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                val prefs by UserPreferencesStore.state.collectAsState()
                SectionTitle("Настройки мессенджера (Чат)", ErpIconGlyph.Send)
                Spacer(Modifier.height(8.dp))
                SettingSwitchRow(
                    title = "Отправлять сообщение по клавише Enter (Shift+Enter — новая строка)",
                    checked = prefs.sendMessageOnEnter,
                    onCheckedChange = { v -> UserPreferencesStore.update { it.copy(sendMessageOnEnter = v) } }
                )
                SettingSwitchRow(
                    title = "Группировать подряд идущие сообщения одного автора",
                    checked = prefs.groupChatMessages,
                    onCheckedChange = { v -> UserPreferencesStore.update { it.copy(groupChatMessages = v) } }
                )
                SettingSwitchRow(
                    title = "Показывать статус присутствия (Онлайн / Не беспокоить) на аватарах",
                    checked = prefs.showOnlinePresence,
                    onCheckedChange = { v -> UserPreferencesStore.update { it.copy(showOnlinePresence = v) } }
                )
                SettingSwitchRow(
                    title = "Автоматически открывать первый активный чат на широком экране",
                    checked = prefs.autoOpenFirstChat,
                    onCheckedChange = { v -> UserPreferencesStore.update { it.copy(autoOpenFirstChat = v) } }
                )
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                val prefs by UserPreferencesStore.state.collectAsState()
                SectionTitle("Интерфейс, задачи и таблицы", ErpIconGlyph.Grid)
                Spacer(Modifier.height(8.dp))
                SettingSwitchRow(
                    title = "Фильтр «Мои задачи» включён по умолчанию",
                    checked = prefs.tasksMineOnlyByDefault,
                    onCheckedChange = { v -> UserPreferencesStore.update { it.copy(tasksMineOnlyByDefault = v) } }
                )
                SettingSwitchRow(
                    title = "Показывать просроченные задачи вверху списка",
                    checked = prefs.tasksOverdueFirst,
                    onCheckedChange = { v -> UserPreferencesStore.update { it.copy(tasksOverdueFirst = v) } }
                )
                SettingSwitchRow(
                    title = "Компактная плотность таблиц",
                    checked = prefs.compactTables,
                    onCheckedChange = { v -> UserPreferencesStore.update { it.copy(compactTables = v) } }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Стартовый раздел при открытии",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "HOME" to "Главная",
                        "TASKS" to "Задачи",
                        "PROJECTS" to "Проекты",
                        "CHAT" to "Чат",
                        "WMS" to "Склад",
                        "DEMANDS" to "Снабжение"
                    ).forEach { (key, label) ->
                        androidx.compose.material3.FilterChip(
                            selected = prefs.startDestination == key,
                            onClick = { UserPreferencesStore.update { it.copy(startDestination = key) } },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    SoftButton(
                        text = "Сбросить настройки по умолчанию",
                        onClick = { UserPreferencesStore.reset() },
                        leadingIcon = ErpIconGlyph.Refresh
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
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 12.dp))
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
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
