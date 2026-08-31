package io.keepagent.app.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.keepagent.app.ui.theme.AgentBubble
import io.keepagent.app.ui.theme.AmberStatus
import io.keepagent.app.ui.theme.LinkRead
import io.keepagent.app.ui.theme.LinkSearch
import io.keepagent.app.ui.theme.LinkWrite
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.TileStone
import io.keepagent.app.ui.theme.UserBubble
import io.keepagent.app.ui.theme.UserBubbleText

/**
 * Chat tab. M0 shows the mockup conversation as a static sample so the shell
 * is visually complete; live sessions (provider add-on + streaming) land in
 * M1 (F-004).
 */
@Composable
fun ChatTab() {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Sample conversation — live chat lands in M1",
            fontSize = 10.sp,
            color = TextSecondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
        HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f), thickness = 1.dp)

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(modifier = Modifier.height(6.dp))
            UserBubble("I need you to code something.")
            AgentBubble("What would you like to create?")
            UserBubble("A (XYZ) program with (XYZ) attributes — just imagine I have a good prompt.")
            ThinkingBlock("Thought for 3s (tap to view)")
            ActionLines(
                "Thinking…",
                "Read XYZ…", "Searched XYZ…", "Wrote to XYZ…",
            )
            AgentBubble(
                "I coded the first draft! Switch to the Test tab to try it out. " +
                    "Press the screenshot button to automatically send me a screenshot " +
                    "if you see something wrong.",
            )
        }

        InputBar()
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp, 4.dp, 12.dp, 12.dp))
                .background(UserBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text, color = UserBubbleText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun AgentBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp, 12.dp, 12.dp, 12.dp))
                .background(AgentBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .padding(end = 36.dp),
        ) {
            Text(text, color = TextPrimary, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ThinkingBlock(summary: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TileStone)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(summary, fontSize = 12.sp, color = TextSecondary)
    }
}

@Composable
private fun ActionLines(first: String, vararg rest: String) {
    val linkColors = listOf(LinkRead, LinkSearch, LinkWrite)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(first, fontSize = 12.sp, color = TextSecondary)
        rest.forEachIndexed { i, line ->
            Text(line, fontSize = 12.sp, color = linkColors[i % linkColors.size])
        }
    }
}

@Composable
private fun InputBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(TileStone)
            .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Type anything here…",
            modifier = Modifier.weight(1f),
            color = TextSecondary,
            fontSize = 14.sp,
        )
        // Attach buttons are inert in M0; attachments (F-005) land with the
        // real composer in M1.
        IconButton(onClick = {}) {
            Icon(Icons.Outlined.AttachFile, contentDescription = "Attach file", tint = TextSecondary)
        }
        IconButton(onClick = {}) {
            Icon(Icons.Outlined.AddAPhoto, contentDescription = "Attach image", tint = TextSecondary)
        }
    }
}
