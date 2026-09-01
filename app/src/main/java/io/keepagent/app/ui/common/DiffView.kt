package io.keepagent.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.TileStone

private val DiffAdd = Color(0xFF66BB6A)
private val DiffDel = Color(0xFFEF5350)
private val DiffAddBg = Color(0x2266BB6A)
private val DiffDelBg = Color(0x22EF5350)

/** Line-based diff (LCS). Returns null when the inputs are too large to diff in memory. */
fun diffLines(old: String, new: String, cap: Int = 1500): List<DiffLine>? {
    if (old == new) return emptyList()
    val a = old.split("\n")
    val b = new.split("\n")
    if (a.size > cap || b.size > cap) return null
    val n = a.size
    val m = b.size
    // LCS dynamic programming.
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    val out = mutableListOf<DiffLine>()
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            a[i] == b[j] -> {
                out += DiffLine(CTX, a[i])
                i++; j++
            }
            dp[i + 1][j] >= dp[i][j + 1] -> {
                out += DiffLine(DEL, a[i])
                i++
            }
            else -> {
                out += DiffLine(ADD, b[j])
                j++
            }
        }
    }
    while (i < n) { out += DiffLine(DEL, a[i]); i++ }
    while (j < m) { out += DiffLine(ADD, b[j]); j++ }
    return out
}

const val CTX = 0
const val ADD = 1
const val DEL = 2

data class DiffLine(val kind: Int, val text: String)

/**
 * Renders a line diff. Context lines are dimmed; additions green, deletions red.
 * Collapses long runs of context so the interesting parts are visible first.
 */
@Composable
fun DiffView(old: String, new: String, modifier: Modifier = Modifier) {
    val lines = remember(old, new) { diffLines(old, new) }
    Column(modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.Start) {
        if (lines == null) {
            Text("Diff too large to display (>1500 lines per side).", fontSize = 11.sp, color = TextSecondary)
            return@Column
        }
        if (lines.isEmpty()) {
            Text("No differences.", fontSize = 11.sp, color = TextSecondary)
            return@Column
        }
        // Collapse context runs longer than 6 to an elision marker.
        val shown = mutableListOf<DiffLine>()
        var run = 0
        for (line in lines) {
            if (line.kind == CTX) {
                run++
                if (run <= 6) shown += line
            } else {
                if (run > 6) shown += DiffLine(CTX, "⋯ ${run - 6} context lines hidden")
                run = 0
                shown += line
            }
        }
        if (run > 6) shown += DiffLine(CTX, "⋯ ${run - 6} context lines hidden")
        var adds = 0
        var dels = 0
        shown.forEach {
            when (it.kind) {
                ADD -> adds++
                DEL -> dels++
                else -> {}
            }
        }
        Text(
            text = "$adds added · $dels removed",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = TextSecondary,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        shown.forEach { line ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        when (line.kind) {
                            ADD -> DiffAddBg
                            DEL -> DiffDelBg
                            else -> Color.Transparent
                        },
                    )
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = when (line.kind) {
                        ADD -> "+"
                        DEL -> "−"
                        else -> " "
                    },
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = when (line.kind) {
                        ADD -> DiffAdd
                        DEL -> DiffDel
                        else -> TextSecondary
                    },
                    modifier = Modifier.width(10.dp),
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = line.text,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (line.kind == CTX) TextSecondary else TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.height(1.dp))
            }
        }
    }
}
