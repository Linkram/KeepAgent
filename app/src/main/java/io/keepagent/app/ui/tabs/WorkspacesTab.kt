package io.keepagent.app.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.keepagent.app.Holder
import io.keepagent.core.settings.FileAccess
import io.keepagent.core.workspace.WorkspaceInfo
import io.keepagent.core.workspace.WorkspaceManager
import io.keepagent.app.ui.theme.AmberStatus
import io.keepagent.app.ui.theme.BevelLight
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.TileStone
import io.keepagent.app.ui.theme.TileStoneSelected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Workspaces tab (M1, F-007): list, create, switch, and delete workspaces.
 * Switching re-points the file service's root (ADR-0002).
 */
@Composable
fun WorkspacesTab() {
    val app = Holder.app
    var list by remember { mutableStateOf<List<WorkspaceInfo>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
    val active = app.workspaceManager.activeName()
    val fileAccess = FileAccess.from(
        app.settingsStore.getString(
            io.keepagent.core.settings.SettingsStore.NS_GENERAL,
            "fileAccess",
        ),
    )

    suspend fun refresh() {
        list = withContext(Dispatchers.IO) { app.workspaceManager.list() }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Workspaces", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(
                text = "File tools run against the active workspace. " +
                    "File access: ${fileAccess.name.lowercase()} " +
                    "(Workspace = confined to root; Full = whole storage).",
                fontSize = 11.sp,
                color = TextSecondary,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("workspace name", fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = TileStone,
                    unfocusedContainerColor = TileStone,
                    focusedIndicatorColor = BevelLight,
                    unfocusedIndicatorColor = BevelLight,
                ),
            )
            Button(onClick = {
                val created = app.workspaceManager.create(name)
                message = if (created != null) {
                    "created: $created"
                } else {
                    "name unavailable or already exists"
                }
                name = ""
                scope.launch { refresh() }
            }) {
                Text("Create")
            }
        }
        message?.let { msg ->
            Text(
                text = msg,
                fontSize = 11.sp,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(list, key = { it.name }) { ws ->
                WorkspaceRow(
                    info = ws,
                    active = ws.name == active,
                    onSelect = {
                        if (ws.name != active) {
                            app.workspaceManager.setActive(ws.name)
                            app.fileService.setRoot(app.workspaceManager.activeRoot())
                            scope.launch { refresh() }
                        }
                    },
                    onDelete = {
                        if (ws.name != active) {
                            app.workspaceManager.delete(ws.name)
                            scope.launch { refresh() }
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun WorkspaceRow(
    info: WorkspaceInfo,
    active: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (active) TileStoneSelected else TileStone)
            .border(
                width = 1.dp,
                color = if (active) BevelLight else androidx.compose.ui.graphics.Color.Transparent,
                shape = shape,
            )
            .clickable(onClick = onSelect)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = info.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            if (active) {
                Text(
                    text = "  active",
                    fontSize = 11.sp,
                    color = AmberStatus,
                )
            }
        }
        Text(
            text = "${info.fileCount} files · ${formatSize(info.sizeBytes)}",
            fontSize = 11.sp,
            color = TextSecondary,
        )
        SelectionContainer {
            Text(
                text = info.root,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary,
            )
        }
        if (!active) {
            Text(
                text = "delete",
                fontSize = 11.sp,
                color = AmberStatus,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onDelete)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
