package io.keepagent.app.ui.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.keepagent.app.Holder
import io.keepagent.app.phoneui.PhoneUiAccessCard
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.ThemeState
import io.keepagent.app.ui.theme.TileStone
import io.keepagent.app.ui.theme.UserBubble
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import io.keepagent.app.update.AppUpdateControl
import io.keepagent.core.agent.ApprovalMode
import io.keepagent.core.settings.FileAccess
import io.keepagent.core.settings.SettingsStore

/** The one place to find app configuration. Expert screens stay available one tap away. */
@Composable
fun SettingsTab(onNavigate: (Int) -> Unit, onDocs: () -> Unit) {
    val app = Holder.app
    var approval by remember { mutableStateOf(ApprovalMode.from(app.settingsStore.getString(SettingsStore.NS_GENERAL, "approvalMode"))) }
    var fileAccess by remember { mutableStateOf(FileAccess.from(app.settingsStore.getString(SettingsStore.NS_GENERAL, "fileAccess"))) }
    var approvalMenu by remember { mutableStateOf(false) }
    var fileMenu by remember { mutableStateOf(false) }
    var confirmFullTrust by remember { mutableStateOf(false) }
    var confirmFullFiles by remember { mutableStateOf(false) }

    fun saveApproval(value: ApprovalMode) {
        app.settingsStore.setString(SettingsStore.NS_GENERAL, "approvalMode", value.name)
        app.approvalGate.clearMemory()
        approval = value
    }

    fun saveFileAccess(value: FileAccess) {
        app.settingsStore.setString(SettingsStore.NS_GENERAL, "fileAccess", value.name)
        app.fileService.setMode(value)
        fileAccess = value
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        Text("Set up the agent, manage access, and keep the app current.", color = TextSecondary)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Appearance", style = MaterialTheme.typography.titleLarge)
                Text("Choose a palette. Tap Surprise me again for a new one.", color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall)
                ThemeState.choices.forEach { (id, label) ->
                    val active = ThemeState.selected == id
                    val preview = ThemeState.preview(id)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(if (active) MaterialTheme.colorScheme.primaryContainer else TileStone)
                            .clickable { ThemeState.choose(id, app.settingsStore) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            listOf(preview.wall, preview.selected, preview.user,
                                preview.amber, preview.accent3).forEach { color ->
                                Box(Modifier.size(14.dp).clip(RoundedCornerShape(4.dp)).background(color))
                            }
                        }
                        Text(label, modifier = Modifier.weight(1f))
                        if (active) Text(if (id == ThemeState.RANDOM) "Reroll ↻" else "Selected",
                            color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (ThemeState.selected == ThemeState.RANDOM) {
                    Text("Current mix: ${ThemeState.harmonyName()}",
                        color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("App updates", style = MaterialTheme.typography.titleLarge)
                Text("Check GitHub Releases and install a verified update.", color = TextSecondary)
                AppUpdateControl()
            }
        }

        SettingsLink(
            title = "AI model",
            detail = app.connections.active()?.name ?: "Connect a model to start chatting",
            onClick = { onNavigate(5) },
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Agent permissions", style = MaterialTheme.typography.titleLarge)
                Text("Control when the agent asks before using tools and where it may read or write files.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Box {
                    OutlinedButton(onClick = { approvalMenu = true }) {
                        Text("Tool approvals: ${when (approval) {
                            ApprovalMode.ASK -> "Ask each time"
                            ApprovalMode.AUTO_ALLOW -> "Allow trusted tools"
                            ApprovalMode.NEVER_ASK -> "Full trust"
                        }}  ▾")
                    }
                    DropdownMenu(expanded = approvalMenu, onDismissRequest = { approvalMenu = false }) {
                        listOf(
                            ApprovalMode.ASK to "Ask each time",
                            ApprovalMode.AUTO_ALLOW to "Allow trusted tools",
                            ApprovalMode.NEVER_ASK to "Full trust",
                        ).forEach { (mode, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                approvalMenu = false
                                if (mode == ApprovalMode.NEVER_ASK) confirmFullTrust = true else saveApproval(mode)
                            })
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { fileMenu = true }) {
                        Text("Files: ${if (fileAccess == FileAccess.WORKSPACE) "Active project only" else "Full device access"}  ▾")
                    }
                    DropdownMenu(expanded = fileMenu, onDismissRequest = { fileMenu = false }) {
                        DropdownMenuItem(text = { Text("Active project only") }, onClick = {
                            fileMenu = false; saveFileAccess(FileAccess.WORKSPACE)
                        })
                        DropdownMenuItem(text = { Text("Full device access") }, onClick = {
                            fileMenu = false; confirmFullFiles = true
                        })
                    }
                }
                Text("Phone app control has its own permission below.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }

        PhoneUiAccessCard()

        Text("More capabilities", style = MaterialTheme.typography.titleMedium)
        SettingsLink("Tools & add-ons", "See and manage installed extensions", { onNavigate(4) })
        SettingsLink("Local & desktop tools", "Phone runtimes and optional computer pairing", { onNavigate(7) })
        GeneralSection(app)

        Text("Support", style = MaterialTheme.typography.titleMedium)
        SettingsLink("Activity log", "See what the agent did", { onNavigate(3) })
        SettingsLink("Help", "How KeepAgent works", onDocs)
    }
    if (confirmFullTrust) {
        AlertDialog(onDismissRequest = { confirmFullTrust = false },
            title = { Text("Allow tools without asking?") },
            text = { Text("The agent may run tools without a prompt. Other phone apps still use the separate access setting below.") },
            confirmButton = { Button(onClick = { saveApproval(ApprovalMode.NEVER_ASK); confirmFullTrust = false }) { Text("Allow full trust") } },
            dismissButton = { TextButton(onClick = { confirmFullTrust = false }) { Text("Cancel") } })
    }
    if (confirmFullFiles) {
        AlertDialog(onDismissRequest = { confirmFullFiles = false },
            title = { Text("Allow files outside the project?") },
            text = { Text("The agent's file tools may access files beyond the active project. You can restore project-only access here.") },
            confirmButton = { Button(onClick = { saveFileAccess(FileAccess.FULL); confirmFullFiles = false }) { Text("Allow full file access") } },
            dismissButton = { TextButton(onClick = { confirmFullFiles = false }) { Text("Cancel") } })
    }
}

@Composable
private fun SettingsLink(title: String, detail: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
            Text("›", style = MaterialTheme.typography.titleLarge)
        }
    }
}
