package io.keepagent.app.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
fun AddonsTab(manager: AddonManager, eventBus: EventBus, onBack: () -> Unit = {}) {
    val records by manager.recordsFlow.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) { Text("‹ Settings") }
        Text(
            text = "Tools & add-ons",
            style = MaterialTheme.typography.headlineSmall,
            color = TextPrimary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        )
        Text(
            text = "Installed extensions make more actions available to the agent. Turn off any you don't want it to use.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
                AddonCard(record, manager)
            }
        }
    }
}

@Composable
private fun AddonCard(record: AddonRecord, manager: AddonManager) {
    var details by remember(record.id) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
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
            }
            StatusChip(record.status)
        }
        // Enable/disable toggle (M1.4): disabled add-ons stay listed but are
        // not initialized, so their tools are not registered.
        if (record.status != AddonStatus.INVALID && record.status != AddonStatus.UNAVAILABLE) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val enabledNow = record.status != AddonStatus.DISABLED
                Text(
                    text = if (enabledNow) "enabled" else "disabled",
                    fontSize = 10.sp,
                    color = TextSecondary,
                )
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { manager.setAddonEnabled(record.id, !enabledNow) },
                ) {
                    Text(
                        text = if (enabledNow) "Disable" else "Enable",
                        fontSize = 11.sp,
                    )
                }
            }
        }
        if (record.tools.isNotEmpty()) {
            Text(
                text = "Tools: ${record.tools.joinToString(", ")}",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
            )
        }
        TextButton(onClick = { details = !details }, modifier = Modifier.padding(start = 4.dp)) {
            Text(if (details) "Hide details" else "Details")
        }
        if (details) {
            Text("${record.id} · v${record.manifest?.version ?: "unknown"} · tier ${record.manifest?.tier ?: "unknown"}",
                fontSize = 11.sp, color = TextSecondary, modifier = Modifier.padding(horizontal = 12.dp))
            record.statusDetail?.let {
                Text(
                    text = it,
                    fontSize = 11.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp),
                )
            }
            record.validationErrors.forEach { err ->
                Text("✗ $err", fontSize = 11.sp, color = Color(0xFFE57373),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 4.dp))
            }
            if (record.status == AddonStatus.INITIALIZED && "hello" in record.tools) HelloToolDemo(record.id)
        }
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun StatusChip(status: AddonStatus) {
    val (label, color) = when (status) {
        AddonStatus.INITIALIZED -> "Ready" to Color(0xFF8BC34A)
        AddonStatus.ENABLED -> "On" to Color(0xFF8BC34A)
        AddonStatus.VALID -> "Valid" to Color(0xFFE0B84C)
        AddonStatus.INVALID, AddonStatus.FAILED -> "Needs attention" to Color(0xFFE57373)
        AddonStatus.UNAVAILABLE -> "Unavailable" to Color(0xFFE57373)
        AddonStatus.DISABLED -> "Off" to TextSecondary
        else -> "Found" to TextSecondary
    }
    Text(label, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
}

/**
 * Direct demo of the Tier-2 loop: invoke the `hello` tool straight from the
 * UI (spec §13: "hello-tool add-on … invoked end-to-end from the UI").
 */
@Composable
private fun HelloToolDemo(addonId: String) {
    val manager = io.keepagent.app.Holder.app.addonManager
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
