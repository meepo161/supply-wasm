package org.example.project.ui.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.example.project.dto.ApiError
import org.example.project.ui.technicalDetail
import org.example.project.ui.theme.ErpIcon
import org.example.project.ui.theme.ErpIconGlyph
import org.example.project.ui.userText
import org.example.project.ui.userTitle

/**
 * Карточка ошибки: понятный заголовок и пояснение на русском (UX-01),
 * исходный текст сервера — мелко, для обращения в поддержку. Текст можно
 * выделить и скопировать.
 */
@Composable
fun ErrorMessageCard(
    error: ApiError,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(scheme.errorContainer)
            .border(1.dp, scheme.error.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ErpIcon(
            glyph = ErpIconGlyph.Alert,
            tint = scheme.error,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        SelectionContainer {
            Column {
                Text(
                    text = error.userTitle(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.error
                )
                Text(
                    text = error.userText(),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onErrorContainer
                )
                error.technicalDetail()?.let { detail ->
                    Text(
                        text = "Подробности: ${error.code} · $detail",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onErrorContainer.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
