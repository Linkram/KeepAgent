package io.keepagent.app.ui.tabs

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.FileProvider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.keepagent.app.Holder
import io.keepagent.app.ui.common.Clip
import io.keepagent.app.ui.theme.AmberStatus
import io.keepagent.app.ui.theme.BevelLight
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.TileStone
import io.keepagent.app.ui.theme.UserBubble
import io.keepagent.core.events.AgentEvent
import io.keepagent.core.events.EventBus
import io.keepagent.core.events.EventKind
import io.keepagent.core.events.EventLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Console tab (F-008, M1.4): live view of the append-only event stream with
 * a filter box, kind chips, copy, clear and reload-from-disk. The JSONL log
 * on disk is the source of truth for reload.
 */
@Composable
fun ConsoleTab(eventBus: EventBus, eventLog: EventLog, onBack: () -> Unit = {}) {
    var events by remember { mutableStateOf(emptyList<AgentEvent>()) }
    var filter by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf<EventKind?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(eventBus) {
        eventBus.events.collect { e ->
            events = (events + e).takeLast(400)
        }
    }

    fun copyAll() {
        val shown = visible(events, filter, kind)
        Clip.copy(context, shown.joinToString("\n"))
        notice = "copied ${shown.size} lines"
    }

    fun reloadFromDisk() {
        scope.launch {
            val tail = withContext(Dispatchers.IO) { eventLog.tail(500) }
            events = tail
            notice = "reloaded ${tail.size} lines from disk"
        }
    }

    /** Writes the visible lines to reports/ and hands them to the share sheet (M1.4h). */
    fun exportVisible() {
        val lines = visible(events, filter, kind).joinToString("\n") { e ->
            "${timeFormat.format(Date(e.timestamp))}  ${e.kind.name}  ${e.source}  ${e.summary}" +
                if (e.detail.isNotEmpty()) "\n    detail: " + e.detail.entries.joinToString("; ") { "${it.key}=${it.value}" }
                else ""
        }
        scope.launch(Dispatchers.IO) {
            val res = runCatching {
                val dir = File(Holder.app.fileService.root, "reports")
                dir.mkdirs()
                File(dir, "console-export-${System.currentTimeMillis()}.txt").apply { writeText(lines) }
            }
            withContext(Dispatchers.Main) {
                res.fold(
                    { f ->
                        val uri = FileProvider.getUriForFile(
                            context,
                            "io.keepagent.app.fileprovider",
                            f,
                        )
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                "share console export",
                            ),
                        )
                        notice = "exported ${f.name}"
                    },
                    { e -> notice = "export failed: ${e.message}" },
                )
            }
        }
    }

    val shown = visible(events, filter, kind)

    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) { Text("‹ Settings") }
        Text(
            text = "Activity log · recent agent actions",
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )

        // Filter row: search box + kind chips + actions.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                TextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("filter text…", fontSize = 10.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = TileStone,
                        unfocusedContainerColor = TileStone,
                        focusedIndicatorColor = BevelLight,
                        unfocusedIndicatorColor = BevelLight,
                    ),
                )
                ActionChip("copy", onClick = { copyAll() })
                ActionChip("export", onClick = { exportVisible() })
                ActionChip("clear", onClick = { events = emptyList(); notice = "cleared view" })
                ActionChip("reload", onClick = { reloadFromDisk() })
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KindChip("all", selected = kind == null, onClick = { kind = null })
                EventKind.values().forEach { k ->
                    KindChip(k.name.lowercase(), selected = kind == k, onClick = {
                        kind = if (kind == k) null else k
                    })
                }
            }
            notice?.let { n ->
                Text(
                    text = n,
                    fontSize = 9.sp,
                    color = AmberStatus,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1B1B1B))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            if (shown.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (events.isEmpty()) "(no events yet)" else "(no matches)",
                        fontSize = 10.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(shown, key = { it.seq }) { e ->
                EventRow(
                    event = e,
                    timeFormat = timeFormat,
                    expanded = e.seq in expanded,
                    onToggle = {
                        expanded = if (e.seq in expanded) expanded - e.seq else expanded + e.seq
                    },
                )
            }
        }
    }
}

/** Formats the visible events for the copy action. */
private fun visible(
    events: List<AgentEvent>,
    filter: String,
    kind: EventKind?,
): List<AgentEvent> {
    val q = filter.trim()
    return events.filter { e ->
        (kind == null || e.kind == kind) &&
            (q.isEmpty() || (e.kind.name + " " + e.source + " " + e.summary)
                .contains(q, ignoreCase = true))
    }
}

@Composable
private fun ActionChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 9.sp,
        color = TextPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(TileStone)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        fontSize = 9.sp,
        color = if (selected) UserBubble else TextSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) TileStone else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
private fun EventRow(
    event: AgentEvent,
    timeFormat: SimpleDateFormat,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val color = when (event.kind) {
        EventKind.ERROR -> Color(0xFFE57373)
        EventKind.ADDON, EventKind.TOOL -> Color(0xFF8BC34A)
        EventKind.SYSTEM -> TextSecondary
        else -> TextPrimary
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle),
        ) {
            Text(
                text = if (expanded) "▾ " else "▸ ",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TextSecondary,
            )
            Text(
                text = "${timeFormat.format(Date(event.timestamp))}  ${event.kind.name.padEnd(8)}  ${event.source.padEnd(34).take(34)}  ${event.summary}",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = color,
                modifier = Modifier.weight(1f),
            )
        }
        // Detail map, expanded on tap (M1.4h).
        if (expanded && event.detail.isNotEmpty()) {
            event.detail.forEach { (k, v) ->
                Text(
                    text = "      $k = $v",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = TextSecondary,
                )
            }
        }
    }
}
