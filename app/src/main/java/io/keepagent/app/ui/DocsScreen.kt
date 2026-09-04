package io.keepagent.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.keepagent.app.R
import io.keepagent.app.ui.common.MarkdownText
import io.keepagent.app.ui.theme.BarStone
import io.keepagent.app.ui.theme.BevelLight
import io.keepagent.app.ui.theme.TileStone
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.WallBase

/**
 * In-app documentation (2026-09-03): the bundled markdown docs rendered with
 * the same lightweight renderer the chat uses, so the reference material is
 * reachable from the phone itself. Opened from the "docs" button in the
 * chat-history sidebar. Status-bar insets are applied by the shell overlay
 * that hosts this screen.
 */
@Composable
fun DocsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var openIndex by remember { mutableIntStateOf(-1) }
    val docs = remember {
        listOf(
            DocEntry("user guide", "use KeepAgent: chat, workspaces, approvals", R.raw.ka_docs_user_guide),
            DocEntry("developer guide", "build, run, and develop KeepAgent", R.raw.ka_docs_readme),
            DocEntry("addon api", "build add-ons against the versioned API", R.raw.ka_docs_addon_api),
            DocEntry("roadmap", "what ships in M1 / M2 / M3", R.raw.ka_docs_roadmap),
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BarStone)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (openIndex >= 0) openIndex = -1 else onBack() }, modifier = Modifier.size(36.dp)) {
                Text("←", fontSize = 18.sp, color = TextPrimary)
            }
            Text(
                text = if (openIndex >= 0) docs[openIndex].title else "docs",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            if (openIndex >= 0) {
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "back to list",
                    fontSize = 10.sp,
                    color = TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { openIndex = -1 }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        HorizontalDivider(color = BevelLight, thickness = 1.dp)

        if (openIndex >= 0) {
            val entry = docs[openIndex]
            val text = remember(entry.rawRes) {
                context.resources.openRawResource(entry.rawRes).bufferedReader().use { it.readText() }
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                MarkdownText(text, fontSize = 13.sp)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                docs.forEachIndexed { index, doc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(TileStone)
                            .clickable { openIndex = index }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(doc.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                            Text(doc.blurb, fontSize = 10.sp, color = TextSecondary)
                        }
                        Text("open", fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }
        }
    }
}

private data class DocEntry(val title: String, val blurb: String, val rawRes: Int)
