package org.example.project.ui.tasks

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

private val EXTERNAL_URL = Regex("https?://[^\\s]+", RegexOption.IGNORE_CASE)

internal data class TextLink(val range: IntRange, val url: String)

internal fun findTextLinks(text: String): List<TextLink> = EXTERNAL_URL.findAll(text).mapNotNull { match ->
    val url = match.value.trimEnd('.', ',', ';', ':', '!', '?')
    if (url.isEmpty()) null else TextLink(match.range.first until match.range.first + url.length, url)
}.toList()

@Composable
internal fun LinkifiedText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Color.Unspecified
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val content = remember(text, linkColor) {
        buildAnnotatedString {
            var cursor = 0
            findTextLinks(text).forEach { link ->
                append(text.substring(cursor, link.range.first))
                withLink(LinkAnnotation.Url(link.url)) {
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                        append(text.substring(link.range.first, link.range.last + 1))
                    }
                }
                cursor = link.range.last + 1
            }
            append(text.substring(cursor))
        }
    }
    Text(text = content, modifier = modifier, style = style, color = color)
}
