package io.keepagent.app.ui.tabs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.keepagent.core.events.EventBus
import io.keepagent.core.host.AddonManager
import io.keepagent.core.host.AddonRecord
import io.keepagent.core.host.AddonStatus
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.TileStone
import io.keepagent.app.ui.theme.UserBubble

/**
 * Add-ons tab: discovered add-ons with status, plus a direct "run tool"
 * demo path that exercises the full Tier-2 loop (manifest → validate →
 * init → invoke) without an LLM in the loop (spec §13 M0).
 */
@Composable
fun AddonsTab(manager: AddonManager, eventBus: EventBus) {
    val records by manager.recordsFlow.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Tier-2 sandbox: quickjs-ng (in-process for M0) · Add-on API v1",
            fontSize = 10.sp,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
        if (records.isEmpty()) {
            Text(
                text = "Discovering add-ons…",
                fontSize = 13.sp,
                color = TextSecondary,
                modifier = Modifier.padding(14.dp),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 10.dp)) {
            items(records, key = { it.dirName }) { record ->
                AddonCard(record)
            }
        }
    }
}

@Composable
private fun AddonCard(record: AddonRecord) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(TileStone.copy(alpha = 0.5f)),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.manifest?.name ?: record.dirName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                Text(
                    text = record.id + "  v" + (record.manifest?.version ?: "—") + "  ·  tier " + (record.manifest?.tier ?: "—"),
                    fontSize = 10.sp,
                    color = TextSecondary,
                )
            }
            StatusChip(record.status)
        }
        record.statusDetail?.let {
            Text(
                text = it,
                fontSize = 10.sp,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, bottom = 4.dp),
            )
        }
        if (record.tools.isNotEmpty()) {
            Text(
                text = "tools: ${record.tools.joinToString(", ")}",
                fontSize = 10.sp,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 12.dp, bottom = 6.dp),
            )
        }
        if (record.status == AddonStatus.INITIALIZED && "hello" in record.tools) {
            HelloToolDemo(record.id)
        }
        if (record.validationErrors.isNotEmpty()) {
            record.validationErrors.forEach { err ->
                Text(
                    text = "✗ $err",
                    fontSize = 10.sp,
                    color = Color(0xFFE57373),
                    modifier = Modifier.padding(horizontal = 12.dp, bottom = 4.dp),
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun StatusChip(status: AddonStatus) {
    val (label, color) = when (status) {
        AddonStatus.INITIALIZED -> "initialized" to Color(0xFF8BC34A)
        AddonStatus.ENABLED -> "enabled" to Color(0xFF8BC34A)
        AddonStatus.VALID -> "valid" to Color(0xFFE0B84C)
        AddonStatus.INVALID, AddonStatus.FAILED -> "failed" to Color(0xFFE57373)
        AddonStatus.UNAVAILABLE -> "unavailable" to Color(0xFFE57373)
        else -> "discovered" to TextSecondary
    }
    Text(label.uppercase(), fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold)
}

/**
 * Direct demo of the Tier-2 loop: invoke the `hello` tool straight from the
 * UI (spec §13: "hello-tool add-on … invoked end-to-end from the UI").
 */
@Composable
private fun HelloToolDemo(addonId: String) {
    val manager = io.keepagent.app.KeepAgentApp.Holder.app.addonManager
    var result by remember { mutableStateOf<String?>(null) }

    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Button(
            onClick = {
                result = manager.invokeTool("hello", """{"name":"KeepAgent"}""")
            },
        ) {
            Text("Run tool: hello", fontSize = 12.sp)
        }
    }
    result?.let {
        Text(
            text = it,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = TextPrimary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .background(UserBubble.copy(alpha = 0.08f)),
        )
    }
}
