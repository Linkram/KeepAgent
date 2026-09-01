package io.keepagent.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.keepagent.app.Holder
import io.keepagent.app.R
import io.keepagent.app.ui.tabs.AddonsTab
import io.keepagent.app.ui.tabs.ChatTab
import io.keepagent.app.ui.tabs.ConnectionsTab
import io.keepagent.app.ui.tabs.ConsoleTab
import io.keepagent.app.ui.tabs.TestTab
import io.keepagent.app.ui.tabs.WorkspacesTab
import io.keepagent.app.ui.theme.AmberStatus
import io.keepagent.app.ui.theme.BarStone
import io.keepagent.app.ui.theme.BevelLight
import io.keepagent.app.ui.theme.TileStone
import io.keepagent.app.ui.theme.TileStoneSelected
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.WallBase

/**
 * The shell: stone top bar (model header + 6 beveled tiles), then the active
 * tab over the subtle wall backdrop. Tab labels are the plain product names —
 * the theme is visual only (F-014).
 */
@Composable
fun KeepAgentShell() {
    val app = Holder.app
    var selected by remember { mutableIntStateOf(0) }

    // targetSdk 35 forces edge-to-edge: the app paints under the system bars
    // and the keyboard, so the bottom insets are applied here. imePadding
    // keeps the header/tiles fixed when the keyboard opens — only the content
    // above the input bar compresses; navigationBarsPadding keeps the input
    // bar above the three nav buttons.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .background(WallBase),
    ) {
        // Top bar — header row, then the 6 tab tiles (clears the status bar).
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .background(BarStone),
        ) {
            HeaderBar()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                TABS.forEachIndexed { index, (label, icon) ->
                    TabTile(
                        label = label,
                        iconRes = icon,
                        selected = index == selected,
                        onClick = { selected = index },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDivider(color = BevelLight, thickness = 1.dp)
        }

        // Active tab over the wall backdrop.
        Box(modifier = Modifier.fillMaxSize()) {
            CastleWallBackground(modifier = Modifier.fillMaxSize())
            when (selected) {
                0 -> ChatTab()
                1 -> TestTab(onGotoChat = { selected = 0 })
                2 -> WorkspacesTab()
                3 -> ConsoleTab(app.eventBus)
                4 -> AddonsTab(app.addonManager, app.eventBus)
                else -> ConnectionsTab()
            }
        }
    }
}

/** Tab sigils follow the mockup: bubble / play / folder / terminal / puzzle / plug. */
private val TABS = listOf(
    "Chat" to R.drawable.ic_tab_chat,
    "Test" to R.drawable.ic_tab_test,
    "Workspaces" to R.drawable.ic_tab_workspaces,
    "Console" to R.drawable.ic_tab_console,
    "Add-ons" to R.drawable.ic_tab_addons,
    "Connections" to R.drawable.ic_tab_connections,
)

@Composable
private fun HeaderBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val app = Holder.app
        val model = app.currentModelId()
        val workspace = runCatching { app.workspaceManager.activeName() }.getOrDefault("—")
        val fileAccess = runCatching {
            io.keepagent.core.settings.FileAccess.from(
                app.settingsStore.getString(
                    io.keepagent.core.settings.SettingsStore.NS_GENERAL,
                    "fileAccess",
                ),
            ).name.lowercase()
        }.getOrDefault("workspace")
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model ?: "no model configured",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Text(
                text = "workspace: $workspace · files: $fileAccess",
                fontSize = 10.sp,
                color = TextSecondary,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("Avg. Speed", fontSize = 8.sp, color = TextSecondary)
            Text("— t/s", fontSize = 10.sp, color = AmberStatus)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("Context used", fontSize = 8.sp, color = TextSecondary)
            Text("—/—", fontSize = 10.sp, color = AmberStatus)
        }
        Spacer(modifier = Modifier.width(8.dp))
        ContextGauge(fraction = 0.5f)
    }
}

/** Small context gauge ring, echoing the mockup header. */
@Composable
private fun ContextGauge(fraction: Float) {
    Box(modifier = Modifier.size(34.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 3.dp.toPx()
            val sweep = 360f * fraction.coerceIn(0f, 1f)
            drawArc(
                color = TileStone,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke),
            )
            drawArc(
                color = AmberStatus,
                startAngle = -90f,
                sweepAngle = sweep,
                useCenter = false,
                style = Stroke(width = stroke),
            )
        }
        Text("M1", fontSize = 8.sp, color = TextSecondary)
    }
}

@Composable
private fun TabTile(
    label: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(3.dp)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) TileStoneSelected else TileStone)
            .border(width = 1.dp, color = if (selected) BevelLight else Color.Transparent, shape = shape)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = if (selected) TextPrimary else TextSecondary,
            modifier = Modifier.size(17.dp),
        )
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) TextPrimary else TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
