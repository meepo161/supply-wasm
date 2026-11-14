package org.example.project.ui.main

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.example.project.dto.UserDto
import org.example.project.ui.theme.Avatar
import org.example.project.ui.theme.Brand
import org.example.project.ui.theme.DetailRow
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.theme.GlassCard
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
    modifier: Modifier = Modifier
) {
    val theme = LocalThemeController.current
    val palette = LocalBrandPalette.current

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
                    .clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(Gradients.Sidebar))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(26.dp))
                    .padding(26.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(260.dp)
                        .align(Alignment.TopEnd)
                        .background(
                            Brush.radialGradient(
                                listOf(Brand.Violet.copy(alpha = 0.35f), Color.Transparent)
                            )
                        )
                )
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
                            PillBadge(
                                text = "@${user.login}",
                                accent = Brand.Cyan,
                                modifier = Modifier
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
                        DetailRow("Логин", user.login)
                        DetailRow("Полное имя", user.fullName)
                        user.email?.takeIf { it.isNotBlank() }?.let {
                            DetailRow("Email", it)
                        }
                    }
                    GlassCard(modifier = Modifier.weight(1f)) {
                        SectionTitle("Оформление", ErpIconGlyph.Sparkle)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (theme.isDark) "Включена тёмная тема — комфортно для вечерней работы."
                            else "Используется светлая тема с мягкими градиентами.",
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
