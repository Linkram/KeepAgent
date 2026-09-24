package io.keepagent.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.ui.platform.LocalConfiguration
import androidx.activity.compose.BackHandler
import io.keepagent.app.ui.tabs.ToolsTab
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
import io.keepagent.app.ui.tabs.HistorySidebar
import io.keepagent.app.ui.tabs.ConnectionsTab
import io.keepagent.app.ui.tabs.ConsoleTab
import io.keepagent.app.ui.tabs.TestTab
import io.keepagent.app.ui.tabs.WorkspacesTab
import io.keepagent.app.ui.tabs.SettingsTab
import io.keepagent.app.ui.theme.AmberStatus
import io.keepagent.app.ui.theme.BarChip
import io.keepagent.app.ui.theme.BarDark
import io.keepagent.app.ui.theme.BarStone
import io.keepagent.app.ui.theme.BevelLight
import io.keepagent.app.ui.theme.TabBrown
import io.keepagent.app.ui.theme.TabBrownActive
import io.keepagent.app.ui.theme.TabGlyph
import io.keepagent.app.ui.theme.TabLabelOn
import io.keepagent.app.ui.theme.TileStone
import io.keepagent.app.ui.theme.TileStoneSelected
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.UserBubble
import io.keepagent.app.ui.theme.WallBase
import io.keepagent.app.ui.theme.WallBrick

/**
 * Four primary destinations; advanced screens are opened from Settings.
 * Project selection stays available from the header on every screen.
 */
@Composable
fun KeepAgentShell() {
    val app = Holder.app
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var projectChooserRequest by remember { mutableIntStateOf(0) }
    var activeProject by remember { mutableStateOf(app.workspaceManager.activeName()) }
    val savedPages = rememberSaveableStateHolder()
    val wide = LocalConfiguration.current.screenWidthDp >= 600
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
    // Chat-history sidebar (2026-09-03): state lives in the shell so the
    // panel can slide in over the FULL screen — header, tabs and all.
    var showChatHistory by remember { mutableStateOf(false) }
    // In-app docs (2026-09-03): full-screen markdown reference, opened from
    // the history sidebar's "docs" button.
    var showDocs by remember { mutableStateOf(false) }
    BackHandler(enabled=showDocs || showChatHistory || selected != 0) {
        when {
            showDocs -> showDocs = false
            showChatHistory -> showChatHistory = false
            selected in 3..5 || selected == 7 -> selected = 6
            else -> selected = 0
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(WallBase)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .navigationBarsPadding(),
        ) {
        Column(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .background(BarStone),
        ) {
            ProductHeader(
                selected = selected,
                running = isRunning,
                projectName = activeProject,
                onHistory = { showChatHistory = true },
                onProject = { projectChooserRequest++; selected = 2 },
            )
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
        if (onboardingVisible && selected == 0 &&
            (!app.modelConfigured() || app.workspaceManager.list().isEmpty())) {
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
        Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (wide) NavigationRail(containerColor=BarStone) {
                PRIMARY_DESTINATIONS.forEach { destination ->
                    NavigationRailItem(selected=destination.first == selected || (destination.first == 6 && selected in 3..5),
                        onClick={selected=destination.first},
                        icon={Icon(painterResource(destination.third), contentDescription=null)},
                        label={Text(destination.second)},
                        colors=NavigationRailItemDefaults.colors(
                            selectedIconColor=UserBubble, selectedTextColor=UserBubble,
                            indicatorColor=TileStoneSelected, unselectedIconColor=TextSecondary,
                            unselectedTextColor=TextSecondary,
                        ))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
            savedPages.SaveableStateProvider(selected) {
            when (selected) {
                0 -> ChatTab(onOpenFileInWorkspaces = { selected = 2 })
                1 -> TestTab(onGotoChat = { selected = 0 }, onGotoProjects = { selected = 2 })
                2 -> WorkspacesTab(chooserRequest = projectChooserRequest, onProjectChanged = { activeProject = it })
                3 -> ConsoleTab(app.eventBus, app.eventLog, onBack = { selected = 6 })
                4 -> AddonsTab(app.addonManager, app.eventBus, onBack = { selected = 6 })
                5 -> ConnectionsTab(onBack = { selected = 6 })
                7 -> ToolsTab(onNavigate={selected=it}, onDocs={showDocs=true})
                else -> SettingsTab(onNavigate={selected=it}, onDocs={showDocs=true})
            }
            }
            }
        }
        if (!wide) NavigationBar(containerColor=BarStone, windowInsets=WindowInsets(0,0,0,0)) {
            PRIMARY_DESTINATIONS.forEach { destination ->
                NavigationBarItem(selected=destination.first == selected || (destination.first == 6 && selected in 3..5),
                    onClick={selected=destination.first},
                    icon={Icon(painterResource(destination.third), contentDescription=null, modifier=Modifier.size(24.dp))},
                    label={Text(destination.second, maxLines=1)},
                    colors=NavigationBarItemDefaults.colors(
                        selectedIconColor=UserBubble, selectedTextColor=UserBubble,
                        indicatorColor=TileStoneSelected, unselectedIconColor=TextSecondary,
                        unselectedTextColor=TextSecondary,
                    ))
            }
        }
        }

        // Scrim fades in/out; the panel slides in from the left, full height
        // (2026-09-03: "goes all the way up").
        // The status-bar inset keeps the panel below the phone's top bar
        // (2026-09-03): it goes to the top of the app, not under the clock.
        AnimatedVisibility(
            visible = showChatHistory,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { showChatHistory = false },
            )
        }
        AnimatedVisibility(
            visible = showChatHistory,
            enter = slideInHorizontally(
                initialOffsetX = { -it },
                animationSpec = tween(280, easing = FastOutSlowInEasing),
            ),
            exit = slideOutHorizontally(
                targetOffsetX = { -it },
                animationSpec = tween(220, easing = FastOutSlowInEasing),
            ),
        ) {
            HistorySidebar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                currentSessionId = app.chatController.currentSessionId,
                onNewChat = {
                    app.chatController.newSession()
                    showChatHistory = false
                },
                onOpen = { id ->
                    app.chatController.openSession(id)
                    showChatHistory = false
                },
                onRename = app.chatController::renameSession,
                onDelete = app.chatController::deleteSession,
                onOpenDocs = {
                    showChatHistory = false
                    showDocs = true
                },
                onDismiss = { showChatHistory = false },
            )
        }

        // Docs overlay: an opaque full screen (status-bar inset so it sits
        // under the phone's top bar) that fades in over the shell.
        AnimatedVisibility(
            visible = showDocs,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(180)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .background(WallBase),
            ) {
                DocsScreen(onBack = { showDocs = false })
            }
        }
    }
}

/** Quiet masonry behind content: the keep identity without competing with controls. */
@Composable
private fun BrickWallBackdrop() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val row = 42.dp.toPx()
        val brick = 92.dp.toPx()
        val mortar = WallBrick.copy(alpha = 0.18f)
        var y = 0f
        var rowIndex = 0
        while (y <= size.height) {
            drawLine(mortar, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1.dp.toPx())
            var x = if (rowIndex % 2 == 0) 0f else -brick / 2f
            while (x <= size.width) {
                drawLine(mortar, androidx.compose.ui.geometry.Offset(x, y), androidx.compose.ui.geometry.Offset(x, y + row), 1.dp.toPx())
                x += brick
            }
            y += row
            rowIndex++
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
        if (!modelOk) StepRow(
            done = false,
            label = "connect a model",
            hint = if (modelOk) "ready" else "set base URL + model",
            onOpen = { onGotoTab(5) },
        )
        if (!wsOk) StepRow(
            done = false,
            label = "use a workspace",
            hint = if (wsOk) "ready" else "create one",
            onOpen = { onGotoTab(2) },
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

private val PRIMARY_DESTINATIONS = listOf(
    Triple(0, "Chat", R.drawable.ic_tab_chat),
    Triple(2, "Projects", R.drawable.ic_tab_workspaces),
    Triple(1, "Test", R.drawable.ic_tab_test),
    Triple(6, "Settings", R.drawable.ic_tune),
)

@Composable
private fun ProductHeader(selected: Int, running: Boolean, projectName: String,
    onHistory: () -> Unit, onProject: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(BarStone).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (selected == 0) {
            IconButton(onClick = onHistory, modifier = Modifier.size(44.dp)) {
                Icon(painterResource(R.drawable.ic_history), contentDescription = "Chat history", tint = TextPrimary)
            }
        }
        Column(Modifier.weight(1f)) {
            Text("KeepAgent", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(if (running) "Agent working" else "Ready to work", fontSize = 11.sp,
                color = if (running) AmberStatus else TextSecondary)
        }
        Text(
            text = projectName.ifBlank { "Choose project" } + "  ▾",
            maxLines = 1,
            fontSize = 12.sp,
            color = TextPrimary,
            modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(TileStone)
                .clickable(onClick = onProject).padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }
}

/** "1234" -> "1K", "999" -> "999" — compact token counts for the header chip. */
private fun fmtK(n: Int): String = if (n >= 1000) "${(n + 500) / 1000}K" else "$n"

/**
 * Compact status row. Model selection lives in Chat, so it is not repeated
 * here and the full width remains available for useful run telemetry.
 */
@Composable
private fun HeaderBar(historyVisible: Boolean, onHistory: () -> Unit) {
    val app = Holder.app
    val stats by app.chatController.stats.collectAsState()
    val tps = if (stats.elapsedMs > 0) stats.completionTokens * 1000f / stats.elapsedMs else 0f
    val fraction = if (stats.contextLimit > 0)
        (stats.lastPromptTokens / stats.contextLimit.toFloat()).coerceIn(0f, 1f)
    else 0f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BarStone)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Dedicated chat-history button (2026-09-03): top left of the status
        // strip, next to the speed readout — opens the full-height sidebar.
        if (historyVisible) {
            IconButton(onClick = onHistory, modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_history),
                    contentDescription = "chat history",
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Column(Modifier.weight(1f).padding(horizontal=8.dp)) {
            Text("KeepAgent", fontSize=16.sp, fontWeight=FontWeight.SemiBold, color=TextPrimary)
            Text(app.workspaceManager.activeName(), fontSize=12.sp, color=TextSecondary, maxLines=1,
                overflow=androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        // Speed health (2026-09-03): red < 14 t/s, yellow 14–29, green 30+;
        // neutral while no turn has run yet.
        val speedColor = when {
            tps <= 0f -> AmberStatus
            tps < 14f -> Color(0xFFE57373)
            tps < 30f -> AmberStatus
            else -> Color(0xFF8BC34A)
        }
        Text(
            text = if (tps > 0) "${tps.roundToInt()} t/s" else "Ready",
            fontSize = 12.sp,
            color = speedColor,
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
                text = "${fmtK(stats.lastPromptTokens)}/${fmtK(stats.contextLimit)}",
                fontSize = 12.sp,
                color = TextPrimary,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.width(6.dp))
            ContextGauge(fraction)
        }
    }
}

/** Gray merlons against a narrow navy night-sky strip. */
@Composable
private fun CastleBattlements() {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(13.dp)
            .background(BarDark),
    ) {
        val merlonWidth = 24.dp.toPx()
        val gapWidth = 14.dp.toPx()
        val baseHeight = 6.dp.toPx()
        drawRect(
            color = BarStone,
            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - baseHeight),
            size = androidx.compose.ui.geometry.Size(size.width, baseHeight),
        )
        var x = 0f
        while (x < size.width) {
            drawRect(
                color = BarStone,
                topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                size = androidx.compose.ui.geometry.Size(merlonWidth, size.height),
            )
            x += merlonWidth + gapWidth
        }
    }
}

/** Small context gauge ring, echoing the mockup header. */
@Composable
private fun ContextGauge(fraction: Float) {
    Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
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
        Text(
            text = "${(fraction * 100).roundToInt()}%",
            fontSize = 7.sp,
            color = TextPrimary,
            maxLines = 1,
        )
    }
}

@Composable
private fun TabTile(
    label: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    running: Boolean = false,
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
                .padding(vertical = 3.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = TabGlyph,
                modifier = Modifier.size(25.dp),
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = label,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) TabLabelOn else Color(0xFFF3EDE4),
                textAlign = TextAlign.Center,
                maxLines = 1,
                lineHeight = 12.sp,
            )
        }
    }
}
