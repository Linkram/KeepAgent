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
 * copy button, headings, bold/italic/strikethrough/`inline code`, links
 * (display-only), bullet/numbered lists (with nesting by indent), pipe
 * tables, blockquotes, and horizontal rules.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    fontSize: TextUnit = 16.sp,
) {
    val blocks = remember(text) { Markdown.parse(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Code -> CodeBlock(block.lang, block.content, color)
                is MdBlock.Heading -> {
                    val hSize = (fontSize.value + (7 - block.level.coerceIn(1, 6)) * 1.5f).sp
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
                    Text(
                        text = renderInline(block.src, color.copy(alpha = 0.8f), fontSize),
                        fontSize = fontSize,
                        color = color.copy(alpha = 0.8f),
                    )
                }
                is MdBlock.Hr -> HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f), thickness = 1.dp)
                is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    block.items.forEach { item ->
                        ListItemRow(item, color, fontSize)
                    }
                }
                is MdBlock.Table -> TableBlock(block.header, block.rows, color, fontSize)
                is MdBlock.Para -> SelectionContainer {
                    Text(text = renderInline(block.src, color, fontSize), fontSize = fontSize, color = color)
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

/** One list row: indent by depth, then the glyph (bullet or number), then the text. */
@Composable
private fun ListItemRow(item: ListItem, color: Color, fontSize: TextUnit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(modifier = Modifier.width((10 + item.depth * 16).dp))
        val glyph = if (item.ordered) "${item.number}. " else "•  "
        Text(
            text = buildAnnotatedString {
                pushStyle(SpanStyle(color = TextSecondary, fontWeight = if (item.ordered) FontWeight.Bold else FontWeight.Normal))
                append(glyph)
                pop()
                appendInlineSpan(this, item.text, color, fontSize)
            },
            fontSize = fontSize,
            color = color,
        )
    }
}

/**
 * Pipe table: header row (bold) + one row per data row; every column takes an
 * equal share of the width (simple, stable on a phone).
 */
@Composable
private fun TableBlock(header: List<String>, rows: List<List<String>>, color: Color, fontSize: TextUnit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TileStone, RoundedCornerShape(6.dp))
            .padding(vertical = 4.dp),
    ) {
        TableRow(header, headerRow = true, color = color, fontSize = fontSize)
        HorizontalDivider(color = TextSecondary.copy(alpha = 0.25f), thickness = 1.dp)
        rows.forEachIndexed { index, row ->
            TableRow(row, headerRow = false, color = color, fontSize = fontSize)
            if (index < rows.size - 1) {
                HorizontalDivider(color = TextSecondary.copy(alpha = 0.12f), thickness = 1.dp)
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, headerRow: Boolean, color: Color, fontSize: TextUnit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp)) {
        cells.forEach { cell ->
            Box(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                Text(
                    text = renderInline(cell, color, (fontSize.value - 2f).sp),
                    fontSize = (fontSize.value - 2f).sp,
                    fontWeight = if (headerRow) FontWeight.Bold else FontWeight.Normal,
                    color = color,
                )
            }
        }
    }
}

// -- parser ------------------------------------------------------------------

sealed interface MdBlock {
    data class Para(val src: String) : MdBlock
    data class Heading(val level: Int, val src: String) : MdBlock
    data class Code(val lang: String, val content: String) : MdBlock
    data class Quote(val src: String) : MdBlock
    data class ListBlock(val items: List<ListItem>) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Hr : MdBlock
}

/** One row of a [MdBlock.ListBlock]: glyph (bullet or number) + text + depth. */
data class ListItem(val text: String, val ordered: Boolean, val number: Int, val depth: Int)

object Markdown {

    private val HeadingRe = Regex("^(#{1,6})\\s+(.*)$")
    private val HrRe = Regex("^(-{3,}|\\*{3,}|_{3,})\\s*$")
    // Indent-capturing: depth comes from leading spaces (2 per level).
    private val ListRe = Regex("^(\\s*)(?:[-*+]|(\\d+)[.)])\\s+(.*)$")
    private val TableSeparatorCellRe = Regex("^:?-{2,}:?$")

    fun parse(text: String): List<MdBlock> {
        val lines = text.replace("\r\n", "\n").split("\n")
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trimStart()
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
                ListRe.matchEntire(lines[i]) != null &&
                    !HrRe.matches(trimmed) -> {
                    val items = mutableListOf<ListItem>()
                    while (i < lines.size) {
                        val m = ListRe.matchEntire(lines[i]) ?: break
                        val indent = m.groupValues[1]
                        val num = m.groupValues[2]
                        val depth = (indent.length / 2).coerceIn(0, 4)
                        items += ListItem(
                            text = m.groupValues[3].trim(),
                            ordered = num.isNotEmpty(),
                            number = num.toIntOrNull() ?: items.count { it.ordered } + 1,
                            depth = depth,
                        )
                        i++
                    }
                    blocks += MdBlock.ListBlock(items)
                }
                isTableStart(trimmed) && isTableSeparatorAt(lines, i + 1) -> {
                    val header = splitTableRow(trimmed)
                    var j = i + 2
                    val rows = mutableListOf<List<String>>()
                    while (j < lines.size && isTableStart(lines[j].trimStart())) {
                        rows += splitTableRow(lines[j].trimStart())
                        j++
                    }
                    val n = header.size
                    val norm = { cells: List<String> ->
                        val taken = cells.take(n)
                        if (taken.size < n) taken + List(n - taken.size) { "" } else taken
                    }
                    blocks += MdBlock.Table(header, rows.map { norm(it) })
                    i = j
                }
                lines[i].isBlank() -> i++
                else -> {
                    val buf = StringBuilder(lines[i])
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

    /** A line that can begin a pipe table: starts with `|` and has >= 2 pipes. */
    private fun isTableStart(trimmed: String): Boolean =
        trimmed.startsWith("|") && trimmed.count { it == '|' } >= 2

    /** The line right after a table header is a separator (|---|:--:|...). */
    private fun isTableSeparatorAt(lines: List<String>, idx: Int): Boolean {
        if (idx >= lines.size) return false
        val t = lines[idx].trim()
        if (!isTableStart(t)) return false
        val cells = splitTableRow(t)
        return cells.isNotEmpty() && cells.all { it.isEmpty() || TableSeparatorCellRe.matches(it) }
    }

    private fun splitTableRow(line: String): List<String> {
        var l = line.trim()
        if (l.startsWith("|")) l = l.substring(1)
        if (l.endsWith("|")) l = l.substring(0, l.length - 1)
        return l.split("|").map { it.trim() }
    }

    private fun isBlockStart(trimmed: String): Boolean =
        trimmed.startsWith("```") ||
            HeadingRe.matches(trimmed) ||
            HrRe.matches(trimmed) ||
            trimmed.startsWith(">") ||
            ListRe.matchEntire(trimmed) != null ||
            isTableStart(trimmed)
}

/** Inline spans: **bold**, *italic*, ~~strike~~, `code`, [link](url). Returns an AnnotatedString. */
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
            src.startsWith("~~", i) -> {
                val end = src.indexOf("~~", i + 2)
                if (end > i) {
                    builder.pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                    appendInlineSpan(builder, src.substring(i + 2, end), base, baseSize)
                    builder.pop()
                    i = end + 2
                } else {
                    builder.append("~~")
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
