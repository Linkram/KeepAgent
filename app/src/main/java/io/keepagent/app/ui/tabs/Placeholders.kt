package io.keepagent.app.ui.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary

@Composable
fun TestTab() = PlaceholderScreen(
    title = "Test",
    body = "Runs generated programs against real targets — the browser runner " +
        "(F-006) and desktop-app emulation (F-013) land in M2. M0: shell only.",
)

@Composable
fun WorkspacesTab() = PlaceholderScreen(
    title = "Workspaces",
    body = "Workspaces scope file access (ADR-0002: Workspace / Full toggle). " +
        "Creating and selecting workspaces lands in M1 (F-007). M0: shell only.",
)

@Composable
fun ConnectionsTab() = PlaceholderScreen(
    title = "Connections",
    body = "Tier-3 endpoints — external processes (MCP-like) over loopback " +
        "(F-010) — land in M3. M0: shell only.",
)

@Composable
private fun PlaceholderScreen(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            fontSize = 13.sp,
            color = TextSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
