package io.keepagent.app.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.keepagent.core.events.AgentEvent
import io.keepagent.core.events.EventBus
import io.keepagent.core.events.EventKind
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Console tab (F-008): live view of the append-only event stream. The bus
 * replays recent events to late subscribers; JSONL persistence backs reload.
 */
@Composable
fun ConsoleTab(eventBus: EventBus) {
    var events by remember { mutableStateOf(emptyList<AgentEvent>()) }
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }

    LaunchedEffect(eventBus) {
        eventBus.events.collect { e ->
            events = (events + e).takeLast(400)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Event stream — append-only, JSONL on disk",
            fontSize = 10.sp,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF1B1B1B))
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            items(events, key = { it.seq }) { e ->
                EventRow(e, timeFormat)
            }
        }
    }
}

@Composable
private fun EventRow(event: AgentEvent, timeFormat: SimpleDateFormat) {
    val color = when (event.kind) {
        EventKind.ERROR -> Color(0xFFE57373)
        EventKind.ADDON, EventKind.TOOL -> Color(0xFF8BC34A)
        EventKind.SYSTEM -> TextSecondary
        else -> TextPrimary
    }
    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            text = "${timeFormat.format(Date(event.timestamp))}  ${event.kind.name.padEnd(8)}  ${event.source.padEnd(34).take(34)}  ${event.summary}",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = color,
        )
    }
}
