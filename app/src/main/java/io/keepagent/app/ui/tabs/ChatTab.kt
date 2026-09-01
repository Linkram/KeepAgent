package io.keepagent.app.ui.tabs

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.keepagent.addonsapi.llm.ImagePart
import io.keepagent.app.Holder
import io.keepagent.app.R
import io.keepagent.core.agent.AgentRun
import io.keepagent.core.agent.ApprovalMode
import io.keepagent.core.agent.ApprovalRequest
import io.keepagent.core.agent.ToolLine
import io.keepagent.core.settings.FileAccess
import io.keepagent.core.settings.SettingsStore
import io.keepagent.app.ui.theme.AgentBubble
import io.keepagent.app.ui.theme.AmberStatus
import io.keepagent.app.ui.theme.BevelLight
import io.keepagent.app.ui.theme.LinkRead
import io.keepagent.app.ui.theme.LinkSearch
import io.keepagent.app.ui.theme.LinkWrite
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.TileStone
import io.keepagent.app.ui.theme.UserBubble
import io.keepagent.app.ui.theme.UserBubbleText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Chat tab (M1, F-001/F-003/F-004): streaming chat with thinking blocks,
 * compact tool lines, approval cards, a model selector, and a model
 * profile editor. One active session; multi-session lands in M2.
 */
@Composable
fun ChatTab() {
    val app = Holder.app
    val controller = app.chatController
    val turns by controller.turns.collectAsState()
    val models by controller.models.collectAsState()
    val modelsError by controller.modelsError.collectAsState()
    val pendingApproval by app.approvalGate.pending.collectAsState()

    var input by remember { mutableStateOf("") }
    val pendingImages by controller.pendingImages.collectAsState()

    val baseUrl = app.settingsStore.getString(SettingsStore.NS_MODEL, "baseUrl")
    val modelId = app.currentModelId()
    LaunchedEffect(baseUrl, modelId) {
        controller.refreshModels(force = baseUrl != null)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ChatHeader(
            modelLabel = modelId ?: "no model configured",
            models = models,
            currentModel = modelId,
            modelsError = modelsError,
            onModelSelected = { id ->
                app.settingsStore.setString(SettingsStore.NS_MODEL, "model", id)
                app.connections.syncActiveFromProfile()
            },
            onRetryModels = { controller.refreshModels(force = true) },
            approvalMode = ApprovalMode.from(
                app.settingsStore.getString(SettingsStore.NS_GENERAL, "approvalMode"),
            ),
            onApprovalMode = { mode ->
                app.settingsStore.setString(SettingsStore.NS_GENERAL, "approvalMode", mode.name)
            },
            fileAccess = FileAccess.from(
                app.settingsStore.getString(SettingsStore.NS_GENERAL, "fileAccess"),
            ),
            onFileAccess = { access ->
                app.settingsStore.setString(SettingsStore.NS_GENERAL, "fileAccess", access.name)
                app.fileService.setMode(access)
            },
        )
        HorizontalDivider(color = TextSecondary.copy(alpha = 0.2f), thickness = 1.dp)

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (turns.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = if (modelId == null)
                            "No model selected — add an endpoint in the Connections tab, then pick a model in the chip above."
                        else
                            "Describe what to build. The agent can read, write, and search files in the workspace.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 24.dp, horizontal = 6.dp),
                    )
                }
            }
            items(turns, key = { it.run.id }) { turn ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UserBubble(turn.userText, turn.images)
                    RunView(turn.run)
                }
            }
            if (pendingApproval != null) {
                item(key = "approval") {
                    ApprovalCard(pendingApproval!!, onDecide = controller::decideApproval)
                }
            }
        }

        InputBar(
            value = input,
            onValueChange = { input = it },
            enabled = !controller.busy,
            onSend = {
                controller.send(input)
                input = ""
            },
            pendingImages = pendingImages,
            onRemoveImage = controller::removePendingImage,
        )
    }
}

// -- header ------------------------------------------------------------------

@Composable
private fun ChatHeader(
    modelLabel: String,
    models: List<io.keepagent.addonsapi.llm.LlmModel>,
    currentModel: String?,
    modelsError: String?,
    onModelSelected: (String) -> Unit,
    onRetryModels: () -> Unit,
    approvalMode: ApprovalMode,
    onApprovalMode: (ApprovalMode) -> Unit,
    fileAccess: FileAccess,
    onFileAccess: (FileAccess) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ModelChip(modelLabel, models, currentModel, modelsError, onModelSelected, onRetryModels)
        ModeChip(
            label = "approval: ${approvalMode.name.lowercase().replace('_', '-')}",
            options = ApprovalMode.entries.map { it.name.lowercase().replace('_', '-') },
            onPick = { s ->
                onApprovalMode(
                    ApprovalMode.entries.first { it.name.lowercase().replace('_', '-') == s },
                )
            },
        )
        ModeChip(
            label = "files: ${fileAccess.name.lowercase()}",
            options = FileAccess.entries.map { it.name.lowercase() },
            onPick = { s ->
                onFileAccess(FileAccess.entries.first { it.name.lowercase() == s })
            },
        )
    }
}

@Composable
private fun ModelChip(
    modelLabel: String,
    models: List<io.keepagent.addonsapi.llm.LlmModel>,
    currentModel: String?,
    modelsError: String?,
    onModelSelected: (String) -> Unit,
    onRetry: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    Box {
        Chip(
            text = "model: $modelLabel",
            onClick = {
                typing = false
                typed = currentModel ?: ""
                open = true
            },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            // The endpoint's full list from the active connection's /models;
            // the configured model stays selectable even when the fetch
            // failed or the endpoint doesn't list it.
            val knownIds = models.map { it.id }
            val entries = buildList<Pair<String, String>> {
                if (currentModel != null && currentModel !in knownIds) {
                    add(currentModel!! to currentModel!!)
                }
                addAll(models.map { it.name to it.id })
            }
            entries.forEach { (label, id) ->
                DropdownMenuItem(
                    text = { Text(label, fontSize = 12.sp) },
                    onClick = {
                        onModelSelected(id)
                        open = false
                    },
                )
            }
            if (modelsError != null) {
                DropdownMenuItem(
                    text = { Text(modelsError, fontSize = 11.sp, color = LinkRead) },
                    onClick = {},
                )
                DropdownMenuItem(
                    text = { Text("Retry fetch", fontSize = 12.sp) },
                    onClick = {
                        onRetry()
                        open = false
                    },
                )
            }
            if (models.isEmpty()) {
                if (!typing) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Model ID not listed — type it",
                                fontSize = 12.sp,
                                color = TextSecondary,
                            )
                        },
                        onClick = { typing = true },
                    )
                } else {
                    DropdownMenuItem(
                        text = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextField(
                                    value = typed,
                                    onValueChange = { typed = it },
                                    label = { Text("model ID", fontSize = 10.sp) },
                                    singleLine = true,
                                    modifier = Modifier.width(200.dp),
                                )
                                TextButton(
                                    onClick = {
                                        val v = typed.trim()
                                        if (v.isNotBlank()) {
                                            onModelSelected(v)
                                            open = false
                                        }
                                    },
                                ) {
                                    Text("set", fontSize = 11.sp)
                                }
                            }
                        },
                        onClick = {},
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(text = label, onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, fontSize = 12.sp) },
                    onClick = {
                        onPick(option)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(TileStone)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(text, fontSize = 11.sp, color = TextPrimary)
    }
}

// -- turn rendering ----------------------------------------------------------

@Composable
private fun RunView(run: AgentRun) {
    val text by run.text.collectAsState()
    val thinking by run.thinking.collectAsState()
    val toolLines by run.toolLines.collectAsState()
    val status by run.status.collectAsState()
    val error by run.error.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (thinking.isNotEmpty()) {
            ThinkingBlock(
                label = if (status == AgentRun.Status.RUNNING) "Thinking…" else "Thought (tap to view)",
                content = thinking,
            )
        }
        toolLines.forEach { line ->
            ToolLineView(line)
        }
        when {
            text.isNotEmpty() -> AgentBubble(text)
            status == AgentRun.Status.RUNNING -> AgentBubble("…")
        }
        if (error != null) {
            Text(
                text = "Error: $error",
                fontSize = 12.sp,
                color = LinkRead,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun UserBubble(text: String, images: List<ImagePart>) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp, 4.dp, 12.dp, 12.dp))
                .background(UserBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text, color = UserBubbleText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                images.forEach { part ->
                    ImagePartView(part, Modifier.height(72.dp))
                }
            }
        }
    }
}

/** A decoded image attachment (e.g. a Test-tab screenshot) inside a message. */
@Composable
private fun ImagePartView(part: ImagePart, modifier: Modifier = Modifier) {
    var bitmap by remember(part.dataBase64) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(part.dataBase64) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Base64.decode(part.dataBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = "image attachment",
            modifier = modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp)),
        )
    } else {
        Text("… decoding image", fontSize = 10.sp, color = TextSecondary)
    }
}

@Composable
private fun AgentBubble(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp, 12.dp, 12.dp, 4.dp))
                .background(AgentBubble)
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .padding(end = 36.dp),
        ) {
            SelectionContainer {
                Text(text, color = TextPrimary, fontSize = 14.sp)
            }
        }
    }
}

/** F-003: thinking block — collapsed by default, tap to expand. */
@Composable
private fun ThinkingBlock(label: String, content: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TileStone)
            .clickable(onClick = { expanded = !expanded })
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(label, fontSize = 12.sp, color = TextSecondary)
        if (expanded) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = content,
                fontSize = 11.sp,
                color = TextSecondary,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/** F-004: compact tool line with status color. */
@Composable
private fun ToolLineView(line: ToolLine) {
    val color = when {
        line.status == ToolLine.Status.ERROR -> LinkRead
        line.status == ToolLine.Status.DENIED -> AmberStatus
        line.name == "read" -> LinkRead
        line.name == "grep" || line.name == "glob" -> LinkSearch
        line.name == "write" || line.name == "edit" -> LinkWrite
        else -> TextSecondary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(line.summary, fontSize = 12.sp, color = color)
        val detail = line.detail
        if (detail != null && line.status != ToolLine.Status.RUNNING) {
            Text(detail, fontSize = 11.sp, color = TextSecondary, maxLines = 2)
        }
    }
}

/** The approval gate's pending decision (spec §10). */
@Composable
private fun ApprovalCard(request: ApprovalRequest, onDecide: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TileStone)
            .border(width = 1.dp, color = AmberStatus.copy(alpha = 0.6f), shape = RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Approval required", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AmberStatus)
        Text(
            text = "${request.toolName} — ${request.permission.name.lowercase()} class",
            fontSize = 12.sp,
            color = TextPrimary,
        )
        Text(request.summary, fontSize = 12.sp, color = TextSecondary)
        Text(
            text = request.argsJson,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = TextSecondary,
            maxLines = 4,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(onClick = { onDecide(true) }) { Text("Allow") }
            OutlinedButton(onClick = { onDecide(false) }) { Text("Deny") }
        }
    }
}

// -- input + settings --------------------------------------------------------

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    onSend: () -> Unit,
    pendingImages: List<ImagePart>,
    onRemoveImage: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (pendingImages.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${pendingImages.size} attached:",
                    fontSize = 10.sp,
                    color = TextSecondary,
                )
                pendingImages.forEachIndexed { index, part ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(TileStone)
                            .border(
                                width = 1.dp,
                                color = BevelLight,
                                shape = RoundedCornerShape(6.dp),
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ImagePartView(part, Modifier.height(28.dp))
                        IconButton(
                            onClick = { onRemoveImage(index) },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_delete),
                                contentDescription = "remove attachment",
                                tint = AmberStatus,
                                modifier = Modifier.size(11.dp),
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(TileStone)
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                placeholder = { Text("Type anything here…", fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = TileStone,
                    unfocusedContainerColor = TileStone,
                    disabledContainerColor = TileStone,
                    focusedIndicatorColor = BevelLight,
                    unfocusedIndicatorColor = BevelLight,
                ),
            )
            Button(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                Text("Send")
            }
        }
    }
}


