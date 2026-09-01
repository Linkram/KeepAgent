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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
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
import io.keepagent.app.ui.theme.BarChip
import io.keepagent.app.ui.theme.BarDark
import io.keepagent.app.ui.theme.BevelLight
import io.keepagent.app.ui.theme.TabBrown
import io.keepagent.app.ui.theme.TabBrownActive
import io.keepagent.app.ui.theme.TabGlyph
import io.keepagent.app.ui.theme.TabLabelOn
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
    val isRunning by app.chatController.isRunning.collectAsState()

    // A share-in file just landed in the workspace: jump to Chat, where the
    // attachment chip is visible in the input bar.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val p = io.keepagent.app.ui.common.Pending.sharedImport
        if (p != null) {
            io.keepagent.app.ui.common.Pending.sharedImport = null
            selected = 0
        }
    }

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
                .background(BarDark),
        ) {
            HeaderBar()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                TABS.forEachIndexed { index, (label, icon) ->
                    TabTile(
                        label = label,
                        iconRes = icon,
                        selected = index == selected,
                        running = index == 0 && isRunning,
                        onClick = { selected = index },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDivider(color = BevelLight, thickness = 1.dp)
        }

        // First-run checklist (M1.4) — shown until dismissed. Local state is
        // the source of truth so "done" actually removes the card; the
        // settings flag remembers the choice across restarts.
        var onboardingVisible by remember {
            mutableStateOf(
                app.settingsStore.getString(
                    io.keepagent.core.settings.SettingsStore.NS_GENERAL,
                    "onboardingDone",
                ) != "true",
            )
        }
        if (onboardingVisible) {
            OnboardingCard(onGotoTab = { selected = it }, onDone = {
                app.settingsStore.setString(
                    io.keepagent.core.settings.SettingsStore.NS_GENERAL,
                    "onboardingDone",
                    "true",
                )
                onboardingVisible = false
            })
        }

        // Active tab over the wall backdrop.
        Box(modifier = Modifier.fillMaxSize()) {
            CastleWallBackground(modifier = Modifier.fillMaxSize())
            when (selected) {
                0 -> ChatTab(onOpenFileInWorkspaces = { selected = 2 })
                1 -> TestTab(onGotoChat = { selected = 0 })
                2 -> WorkspacesTab()
                3 -> ConsoleTab(app.eventBus, app.eventLog)
                4 -> AddonsTab(app.addonManager, app.eventBus)
                else -> ConnectionsTab()
            }
        }
    }
}

/**
 * First-run checklist: connect a model, use a workspace, send a first
 * message. Steps light up as their conditions become true; dismissible
 * permanently via the "done" button.
 */
@Composable
private fun OnboardingCard(onGotoTab: (Int) -> Unit, onDone: () -> Unit) {
    val app = Holder.app
    val modelOk = app.modelConfigured()
    val wsOk = runCatching { app.workspaceManager.list().isNotEmpty() }.getOrDefault(false)
    val sentOk = app.settingsStore.getString(
        io.keepagent.core.settings.SettingsStore.NS_GENERAL,
        "firstMessageSent",
    ) == "true"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(TileStone)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "get started",
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        StepRow(
            done = modelOk,
            label = "connect a model",
            hint = if (modelOk) "ready" else "set base URL + model",
            onOpen = { onGotoTab(5) },
        )
        StepRow(
            done = wsOk,
            label = "use a workspace",
            hint = if (wsOk) "ready" else "create one",
            onOpen = { onGotoTab(2) },
        )
        StepRow(
            done = sentOk,
            label = "send a first message",
            hint = if (sentOk) "done" else "chat with the agent",
            onOpen = { onGotoTab(0) },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = "done — hide this",
                fontSize = 10.sp,
                color = TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onDone)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun StepRow(done: Boolean, label: String, hint: String, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (done) "✓" else "○",
            fontSize = 11.sp,
            color = if (done) AmberStatus else TextSecondary,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = if (done) TextSecondary else TextPrimary,
        )
        Text(
            text = "  ·  $hint",
            fontSize = 10.sp,
            color = TextSecondary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "open",
            fontSize = 10.sp,
            color = TextPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(TileStoneSelected)
                .clickable(onClick = onOpen)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
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

/** "1234" -> "1K", "999" -> "999" — compact token counts for the header chip. */
private fun fmtK(n: Int): String = if (n >= 1000) "${(n + 500) / 1000}K" else "$n"

/**
 * Mockup header: model name on the left, two dark status chips on the
 * right (avg speed, context in/out/max + gauge).
 */
@Composable
private fun HeaderBar() {
    val app = Holder.app
    val model = app.currentModelId()
    val stats by app.chatController.stats.collectAsState()
    val tps = if (stats.elapsedMs > 0) stats.completionTokens * 1000f / stats.elapsedMs else 0f
    val fraction = if (stats.contextLimit > 0)
        (stats.lastPromptTokens / stats.contextLimit.toFloat()).coerceIn(0f, 1f)
    else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = model ?: "no model configured",
            fontSize = 15.sp,
            color = TextPrimary,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (tps > 0) "Avg. Speed: ${tps.roundToInt()} t/s" else "Avg. Speed: — t/s",
            fontSize = 9.sp,
            color = AmberStatus,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(BarChip)
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(BarChip)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Text(
                text = "Context: in:${fmtK(stats.lastPromptTokens)} " +
                    "out:${fmtK(stats.completionTokens)} Max:${fmtK(stats.contextLimit)}",
                fontSize = 9.sp,
                color = TextPrimary,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(6.dp))
            ContextGauge(fraction)
            Text(
                text = "${(fraction * 100).roundToInt()}%",
                fontSize = 9.sp,
                color = TextSecondary,
            )
        }
    }
}

/** Small context gauge ring, echoing the mockup header. */
@Composable
private fun ContextGauge(fraction: Float) {
    Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 2.5.dp.toPx()
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
    }
}

@Composable
private fun TabTile(
    label: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    running: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Mockup tiles: brown with a dark glyph; the active tile goes pale.
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) TabBrownActive else TabBrown)
            .clickable(onClick = onClick),
    ) {
        // Activity dot while the agent is mid-turn.
        if (running) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(5.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(AmberStatus),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = TabGlyph,
                modifier = Modifier.size(26.dp),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) TabLabelOn else Color(0xFFF3EDE4),
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}
