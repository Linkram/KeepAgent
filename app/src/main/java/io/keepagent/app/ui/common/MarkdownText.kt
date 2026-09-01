package io.keepagent.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.TileStone

/**
 * Lightweight markdown renderer (no dependencies): fenced code blocks with a
 * copy button, headings, bold/italic/`inline code`, links (display-only),
 * bullet/numbered lists, blockquotes, and horizontal rules.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    fontSize: TextUnit = 14.sp,
) {
    val blocks = remember(text) { Markdown.parse(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block.lang, block.content, color)
                is MdBlock.Heading -> {
                    val hSize = (fontSize.value + block.level * 1.5f).sp
                    Text(
                        text = renderInline(block.src, color, hSize),
                        fontSize = hSize,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                }
                is MdBlock.Quote -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(modifier = Modifier.width(3.dp).background(TextSecondary.copy(alpha = 0.4f)))
                    Text(text = renderInline(block.src, color.copy(alpha = 0.8f), fontSize), fontSize = fontSize)
                }
                is MdBlock.Hr -> HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f), thickness = 1.dp)
                is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEach { item ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(modifier = Modifier.width(10.dp))
                            Text(text = renderInline(item, color, fontSize), fontSize = fontSize)
                        }
                    }
                }
                is MdBlock.Para -> SelectionContainer {
                    Text(text = renderInline(block.src, color, fontSize), fontSize = fontSize)
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, content: String, color: Color) {
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TileStone, RoundedCornerShape(6.dp)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = lang.ifEmpty { "code" },
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary,
            )
            Box(modifier = Modifier.weight(1f))
            TextButton(onClick = { clipboard.setText(AnnotatedString(content)) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                Text("copy", fontSize = 10.sp, color = TextSecondary)
            }
        }
        HorizontalDivider(color = TextSecondary.copy(alpha = 0.15f), thickness = 1.dp)
        SelectionContainer {
            Text(
                text = content,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = color,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

// -- parser ------------------------------------------------------------------

sealed interface MdBlock {
    data class Para(val src: String) : MdBlock
    data class Heading(val level: Int, val src: String) : MdBlock
    data class Code(val lang: String, val content: String) : MdBlock
    data class Quote(val src: String) : MdBlock
    data class ListBlock(val items: List<String>) : MdBlock
    data object Hr : MdBlock
}

object Markdown {

    private val HeadingRe = Regex("^(#{1,6})\\s+(.*)$")
    private val HrRe = Regex("^(-{3,}|\\*{3,}|_{3,})\\s*$")
    private val BulletRe = Regex("^(?:[-*+]|\\d+\\.)\\s+(.*)$")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").split("\n")
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            when {
                trimmed.startsWith("```") -> {
                    val lang = trimmed.removePrefix("```").trim()
                    val buf = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                        buf.append(lines[i]).append('\n')
                        i++
                    }
                    blocks += MdBlock.Code(lang, buf.toString().trimEnd())
                    i++
                }
                HeadingRe.matches(trimmed) -> {
                    val m = HeadingRe.matchEntire(trimmed)!!
                    blocks += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2].trim())
                    i++
                }
                HrRe.matches(trimmed) -> {
                    blocks += MdBlock.Hr
                    i++
                }
                trimmed.startsWith(">") -> {
                    val buf = StringBuilder()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                        buf.append(lines[i].trimStart().removePrefix(">").trimStart()).append('\n')
                        i++
                    }
                    val content = buf.toString().trim()
                    if (content.isNotEmpty()) blocks += MdBlock.Quote(content)
                }
                BulletRe.matches(trimmed) -> {
                    val items = mutableListOf<String>()
                    while (i < lines.size) {
                        val m = BulletRe.matchEntire(lines[i].trimStart()) ?: break
                        items += m.groupValues[1]
                        i++
                    }
                    blocks += MdBlock.ListBlock(items)
                }
                line.isBlank() -> i++
                else -> {
                    val buf = StringBuilder(line)
                    i++
                    while (i < lines.size && lines[i].isNotBlank() &&
                        !isBlockStart(lines[i].trimStart())
                    ) {
                        buf.append('\n').append(lines[i])
                        i++
                    }
                    blocks += MdBlock.Para(buf.toString())
                }
            }
        }
        return blocks
    }

    private fun isBlockStart(trimmed: String): Boolean =
        trimmed.startsWith("```") ||
            HeadingRe.matches(trimmed) ||
            HrRe.matches(trimmed) ||
            trimmed.startsWith(">") ||
            BulletRe.matches(trimmed)
}

/** Inline spans: **bold**, *italic*, `code`, [link](url). Returns an AnnotatedString. */
fun renderInline(src: String, base: Color, baseSize: TextUnit = 14.sp): AnnotatedString =
    buildAnnotatedString {
        appendInlineSpan(this, src, base, baseSize)
    }

private fun appendInlineSpan(
    builder: AnnotatedString.Builder,
    src: String,
    base: Color,
    baseSize: TextUnit,
) {
    var i = 0
    while (i < src.length) {
        when {
            src.startsWith("**", i) -> {
                val end = src.indexOf("**", i + 2)
                if (end > i) {
                    builder.pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                    appendInlineSpan(builder, src.substring(i + 2, end), base, baseSize)
                    builder.pop()
                    i = end + 2
                } else {
                    builder.append("**")
                    i += 2
                }
            }
            src[i] == '`' -> {
                val end = src.indexOf('`', i + 1)
                if (end > i) {
                    builder.pushStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = (baseSize.value * 0.88f).sp,
                            background = base.copy(alpha = 0.12f),
                        ),
                    )
                    builder.append(src.substring(i + 1, end))
                    builder.pop()
                    i = end + 1
                } else {
                    builder.append('`')
                    i++
                }
            }
            src[i] == '*' -> {
                val end = src.indexOf('*', i + 1)
                if (end > i) {
                    builder.pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    appendInlineSpan(builder, src.substring(i + 1, end), base, baseSize)
                    builder.pop()
                    i = end + 1
                } else {
                    builder.append('*')
                    i++
                }
            }
            src[i] == '[' -> {
                val close = src.indexOf(']', i + 1)
                if (close > i && close + 1 < src.length && src[close + 1] == '(') {
                    val urlEnd = src.indexOf(')', close + 2)
                    if (urlEnd > close) {
                        builder.pushStyle(
                            SpanStyle(textDecoration = TextDecoration.Underline, color = base.copy(alpha = 0.85f)),
                        )
                        builder.append(src.substring(i + 1, close))
                        builder.pop()
                        i = urlEnd + 1
                    } else {
                        builder.append('[')
                        i++
                    }
                } else {
                    builder.append('[')
                    i++
                }
            }
            else -> {
                builder.append(src[i])
                i++
            }
        }
    }
}
