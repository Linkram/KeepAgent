package io.keepagent.app.ui.tabs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.keepagent.addonsapi.llm.ImagePart
import io.keepagent.app.ChatController
import io.keepagent.app.ChatController.AttachedFile
import io.keepagent.app.Holder
import io.keepagent.app.R
import io.keepagent.app.chat.ChatSession
import io.keepagent.app.ui.common.Clip
import io.keepagent.app.ui.common.DiffView
import io.keepagent.app.ui.common.FileOpener
import io.keepagent.app.ui.common.MarkdownText
import io.keepagent.core.agent.AgentRun
import io.keepagent.core.agent.ApprovalMode
import io.keepagent.core.agent.ApprovalRequest
import io.keepagent.core.agent.ToolLine
import io.keepagent.core.fs.FileService
import io.keepagent.core.settings.FileAccess
import io.keepagent.core.settings.SettingsStore
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import io.keepagent.app.ui.theme.AgentBubble
import io.keepagent.app.ui.theme.AmberStatus
import io.keepagent.app.ui.theme.BevelLight
import io.keepagent.app.ui.theme.LinkRead
import io.keepagent.app.ui.theme.LinkSearch
import io.keepagent.app.ui.theme.InputStone
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
fun ChatTab(onOpenFileInWorkspaces: (String) -> Unit = {}) {
    val app = Holder.app
    val controller = app.chatController
    val turns by controller.turns.collectAsState()
    val models by controller.models.collectAsState()
    val modelsError by controller.modelsError.collectAsState()
    val pendingApproval by app.approvalGate.pending.collectAsState()
    val isRunning by controller.isRunning.collectAsState()
    val sendOnEnter = remember {
        app.settingsStore.getString(SettingsStore.NS_GENERAL, "sendOnEnter") == "true"
    }

    // Draft (unsent text) is restored and persisted through the controller.
    var input by remember { mutableStateOf(controller.draftText.value) }
    LaunchedEffect(input) { controller.setDraft(input) }
    val pendingImages by controller.pendingImages.collectAsState()
    val pendingFiles by controller.pendingFiles.collectAsState()
    val sessionTitle by controller.sessionTitle.collectAsState()

    var showHistory by remember { mutableStateOf(false) }
    var showFilePicker by remember { mutableStateOf(false) }
    var zoomImage by remember { mutableStateOf<ImagePart?>(null) }
    val snack = remember { SnackbarHostState() }

    val context = LocalContext.current
    val cScope = rememberCoroutineScope()

    fun copyText(text: String) {
        Clip.copy(context, text)
        cScope.launch { snack.showSnackbar("Copied to clipboard") }
    }

    // Device document picker → copied into the workspace under imported/.
    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            cScope.launch(Dispatchers.IO) {
                val result = runCatching {
                    val name = queryDocumentName(context, uri)
                        .replace(Regex("[^A-Za-z0-9._-]"), "_")
                        .take(80)
                        .ifEmpty { "document" }
                    val dir = File(app.fileService.root, "imported")
                    dir.mkdirs()
                    val target = File(dir, name)
                    val stream = context.contentResolver.openInputStream(uri)
                        ?: error("could not open document stream")
                    target.writeBytes(stream.use { it.readBytes() })
                    controller.attachFile(AttachedFile("imported/$name", false, ""))
                    name
                }
                withContext(Dispatchers.Main) {
                    result.onSuccess { n -> snack.showSnackbar("Imported $n") }
                        .onFailure { e -> snack.showSnackbar("Import failed: ${e.message}") }
                }
            }
        }
    }
    // Photo picker (system UI on API 33+, legacy fallback below) → ImagePart.
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null) {
            cScope.launch {
                val part = withContext(Dispatchers.IO) { decodePickedImage(context, uri) }
                if (part != null) controller.attachImage(part)
            }
        }
    }

    val baseUrl = app.settingsStore.getString(SettingsStore.NS_MODEL, "baseUrl")
    val modelId = app.currentModelId()
    LaunchedEffect(baseUrl, modelId) {
        controller.refreshModels(force = baseUrl != null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
            sessionTitle = sessionTitle,
            onHistory = { showHistory = true },
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
            itemsIndexed(turns, key = { _, t -> t.run.id }) { index, turn ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    UserBubble(
                        turn = turn,
                        isLast = index == turns.lastIndex,
                        onCopy = ::copyText,
                        onEdit = { controller.editLast() },
                        onDelete = { controller.deleteTurn(index) },
                        onFork = { controller.forkFrom(index) },
                        onZoomImage = { zoomImage = it },
                        onOpenFile = { p -> FileOpener.open(p); onOpenFileInWorkspaces(p) },
                    )
                    RunView(
                        run = turn.run,
                        modelId = turn.modelId,
                        isLast = index == turns.lastIndex,
                        onRetry = { controller.retryLast() },
                        onCopy = ::copyText,
                        onOpenFile = { p -> FileOpener.open(p); onOpenFileInWorkspaces(p) },
                    )
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
            isRunning = isRunning,
            onStop = { controller.stop() },
            sendOnEnter = sendOnEnter,
            onSend = {
                controller.send(input)
                input = ""
            },
            pendingImages = pendingImages,
            onRemoveImage = controller::removePendingImage,
            pendingFiles = pendingFiles,
            onRemoveFile = controller::removePendingFile,
            onAttachImage = {
                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onAttachFile = { showFilePicker = true },
            onAttachDocument = { docPicker.launch(arrayOf("*/*")) },
        )

        if (showHistory) {
            HistoryDialog(
                currentSessionId = controller.currentSessionId,
                onNewChat = {
                    controller.newSession()
                    showHistory = false
                },
                onOpen = { id ->
                    controller.openSession(id)
                    showHistory = false
                },
                onRename = controller::renameSession,
                onDelete = controller::deleteSession,
                onDismiss = { showHistory = false },
            )
        }
        if (showFilePicker) {
            AttachFilePicker(
                fs = app.fileService,
                onPickFile = { path, preview ->
                    controller.attachFile(AttachedFile(path, false, preview))
                    showFilePicker = false
                },
                onPickFolder = { path, listing ->
                    controller.attachFile(AttachedFile(path, true, listing))
                    showFilePicker = false
                },
                onDismiss = { showFilePicker = false },
            )
        }
        }
        SnackbarHost(snack, modifier = Modifier.align(Alignment.BottomStart))
        zoomImage?.let { part -> ZoomImageDialog(part, onDismiss = { zoomImage = null }) }
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
    sessionTitle: String,
    onHistory: () -> Unit,
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
        Chip(
            text = "history: ${sessionTitle.take(24)}",
            onClick = onHistory,
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
private fun RunView(
    run: AgentRun,
    modelId: String?,
    isLast: Boolean,
    onRetry: () -> Unit,
    onCopy: (String) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    val text by run.text.collectAsState()
    val thinking by run.thinking.collectAsState()
    val toolLines by run.toolLines.collectAsState()
    val status by run.status.collectAsState()
    val error by run.error.collectAsState()
    val usage = run.usage
    val elapsed = run.elapsedMs

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (thinking.isNotEmpty()) {
            ThinkingBlock(
                label = if (status == AgentRun.Status.RUNNING) "Thinking…" else "Thought (tap to view)",
                content = thinking,
            )
        }
        toolLines.forEach { line ->
            ToolLineView(line, onOpenFile = onOpenFile)
        }
        // Mockup-style header inside the reply bubble.
        val bubbleHeader = when {
            thinking.isNotEmpty() -> "Thought for ${shortElapsed(elapsed)}"
            status == AgentRun.Status.DONE && elapsed > 0 -> "Worked for ${shortElapsed(elapsed)}"
            else -> null
        }
        if (text.isNotEmpty()) {
            AgentBubble(text, onCopy, bubbleHeader)
        } else if (status == AgentRun.Status.RUNNING) {
            AgentBubble(text = "…", onCopy = {}, header = "Working…")
        }
        if (status == AgentRun.Status.CANCELED) {
            Text(
                text = "stopped by user",
                fontSize = 10.sp,
                color = TextSecondary,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (error != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Error: $error",
                    fontSize = 12.sp,
                    color = LinkRead,
                    modifier = Modifier.weight(1f),
                )
                if (isLast) {
                    TextButton(onClick = onRetry) { Text("Retry", fontSize = 12.sp) }
                }
            }
        }
        if (status != AgentRun.Status.RUNNING &&
            (usage.promptTokens + usage.completionTokens > 0 || elapsed > 0)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (elapsed > 0) {
                    Text(
                        text = formatElapsed(elapsed),
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                    )
                }
                if (usage.promptTokens + usage.completionTokens > 0) {
                    Text(
                        text = "${usage.promptTokens}+${usage.completionTokens} tok",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                    )
                }
                if (modelId != null) {
                    Text(
                        text = modelId,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun formatElapsed(ms: Long): String =
    if (ms < 60_000) "${ms / 1000} s" else "${ms / 3_600_000} m ${ms % 60_000 / 1000} s"

@Composable
private fun UserBubble(
    turn: ChatController.Turn,
    isLast: Boolean,
    onCopy: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onFork: () -> Unit,
    onZoomImage: (ImagePart) -> Unit,
    onOpenFile: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var showPrompt by remember { mutableStateOf(false) }
    val text = turn.userText

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(horizontalArrangement = Arrangement.End) {
            Text(
                text = "⋮",
                fontSize = 15.sp,
                color = TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { menuOpen = !menuOpen }
                    .padding(horizontal = 10.dp, vertical = 1.dp),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Copy", fontSize = 12.sp) },
                onClick = { onCopy(text.ifEmpty { "(no text)" }); menuOpen = false },
            )
            if (isLast && text.isNotBlank()) {
                DropdownMenuItem(
                    text = { Text("Edit", fontSize = 12.sp) },
                    onClick = { onEdit(); menuOpen = false },
                )
            }
            DropdownMenuItem(
                text = { Text("Fork from here", fontSize = 12.sp) },
                onClick = { onFork(); menuOpen = false },
            )
            DropdownMenuItem(
                text = { Text("Delete", fontSize = 12.sp, color = LinkRead) },
                onClick = { onDelete(); menuOpen = false },
            )
        }
        // Mockup bubble: wide sage block with a speech tail at bottom-right.
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(horizontalAlignment = Alignment.End) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.82f)
                        .clip(RoundedCornerShape(12.dp, 4.dp, 12.dp, 12.dp))
                        .background(UserBubble)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (text.isNotBlank()) {
                            Text(text, color = UserBubbleText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    turn.images.forEach { part ->
                        ImagePartView(part, Modifier.height(72.dp).clickable { onZoomImage(part) })
                    }
                    turn.files.forEach { f ->
                        Text(
                            text = "attached ${if (f.isFolder) "folder" else "file"}: ${f.path}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = UserBubbleText.copy(alpha = 0.75f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onOpenFile(f.path) },
                        )
                    }
                    }
                }
                BubbleTail(color = UserBubble, tailEnd = true, modifier = Modifier.padding(start = 14.dp))
            }
        }
        if (turn.sentPrompt.isNotBlank() && turn.sentPrompt != text) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Text(
                    text = if (showPrompt) "▾ prompt sent" else "▸ prompt sent",
                    fontSize = 9.sp,
                    color = UserBubbleText.copy(alpha = 0.6f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { showPrompt = !showPrompt }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
                if (showPrompt) {
                    SelectionContainer {
                        Text(
                            text = turn.sentPrompt,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = UserBubbleText.copy(alpha = 0.85f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(UserBubble)
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Full-screen-ish image zoom for message attachments. */
@Composable
private fun ZoomImageDialog(part: ImagePart, onDismiss: () -> Unit) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(part.dataBase64) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = Base64.decode(part.dataBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", fontSize = 12.sp) }
        },
        text = {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp,
                    contentDescription = "attachment",
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Text("decoding…", fontSize = 12.sp, color = TextSecondary)
            }
        },
        containerColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.85f),
        shape = RoundedCornerShape(12.dp),
    )
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

/** Agent reply rendered as markdown; tap copies, long-press selects. */
@Composable
private fun AgentBubble(text: String, onCopy: (String) -> Unit, header: String? = null) {
    // Mockup bubble: wide dark block, italic "worked/thought for …" header,
    // speech tail at bottom-left.
    Column {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .clip(RoundedCornerShape(4.dp, 12.dp, 12.dp, 4.dp))
                .background(AgentBubble)
                .clickable { onCopy(text) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (header != null) {
                    Text(
                        text = header,
                        fontSize = 10.sp,
                        fontStyle = FontStyle.Italic,
                        color = TextSecondary,
                    )
                }
                SelectionContainer {
                    MarkdownText(text, color = TextPrimary, fontSize = 14.sp)
                }
            }
        }
        BubbleTail(color = AgentBubble, tailEnd = false, modifier = Modifier.padding(end = 14.dp))
    }
}

/** Speech tail under a chat bubble (mockup): small slanted triangle. */
@Composable
private fun BubbleTail(color: Color, tailEnd: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(width = 22.dp, height = 10.dp)) {
        val path = Path().apply {
            if (tailEnd) {
                moveTo(size.width - 18f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width - 4f, size.height)
            } else {
                moveTo(0f, 0f)
                lineTo(18f, 0f)
                lineTo(4f, size.height)
            }
            close()
        }
        drawPath(path, color)
    }
}

/** Compact elapsed time for bubble headers: "3s" / "12m" / "1h". */
private fun shortElapsed(ms: Long): String = when {
    ms < 60_000 -> "${ms / 1000}s"
    ms < 3_600_000 -> "${ms / 60_000}m"
    else -> "${ms / 3_600_000}h"
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

/** F-004: compact tool line — tap to expand detail, open the file, or view a diff. */
@Composable
private fun ToolLineView(line: ToolLine, onOpenFile: (String) -> Unit) {
    var expanded by remember { mutableStateOf(line.status == ToolLine.Status.ERROR) }
    var showDiff by remember { mutableStateOf(false) }
    val dotColor = when {
        line.status == ToolLine.Status.ERROR -> LinkRead
        line.status == ToolLine.Status.DENIED -> AmberStatus
        line.name == "read" -> LinkRead
        line.name == "grep" || line.name == "glob" -> LinkSearch
        line.name == "write" || line.name == "edit" -> LinkWrite
        else -> TextSecondary
    }
    val hasDiff = line.name in setOf("write", "edit") &&
        line.extra["old"] != null && line.extra["new"] != null
    val path = line.extra["path"]

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(line.summary, fontSize = 12.sp, color = dotColor, modifier = Modifier.weight(1f))
            Text(
                text = if (expanded) "▾" else "▸",
                fontSize = 10.sp,
                color = TextSecondary,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(TileStone)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                line.detail?.let {
                    Text(it, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = TextSecondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (path != null) {
                        Text(
                            text = "open file",
                            fontSize = 10.sp,
                            color = LinkWrite,
                            modifier = Modifier.clickable { onOpenFile(path) },
                        )
                    }
                    if (hasDiff) {
                        Text(
                            text = "view diff",
                            fontSize = 10.sp,
                            color = LinkWrite,
                            modifier = Modifier.clickable { showDiff = true },
                        )
                    }
                }
            }
        }
        if (showDiff) {
            DiffDialog(
                title = "${line.name} — ${path ?: "file"}",
                oldText = line.extra["old"].orEmpty(),
                newText = line.extra["new"].orEmpty(),
                onDismiss = { showDiff = false },
            )
        }
    }
}

@Composable
private fun DiffDialog(title: String, oldText: String, newText: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", fontSize = 12.sp) }
        },
        title = {
            Text(
                text = title,
                fontSize = 12.sp,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            DiffView(oldText, newText, modifier = Modifier.heightIn(max = 400.dp))
        },
    )
}

/** The approval gate's pending decision — Allow / Allow-always (this session) / Deny. */
@Composable
private fun ApprovalCard(request: ApprovalRequest, onDecide: (Boolean, Boolean) -> Unit) {
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
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) {
            OutlinedButton(onClick = { onDecide(false, false) }, modifier = Modifier.weight(1f)) {
                Text("Deny", fontSize = 12.sp)
            }
            Button(onClick = { onDecide(true, false) }, modifier = Modifier.weight(1f)) {
                Text("Allow", fontSize = 12.sp)
            }
            OutlinedButton(onClick = { onDecide(true, true) }, modifier = Modifier.weight(1.4f)) {
                Text("Always this session", fontSize = 10.sp)
            }
        }
    }
}

// -- input + settings --------------------------------------------------------

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    isRunning: Boolean,
    onStop: () -> Unit,
    sendOnEnter: Boolean,
    onSend: () -> Unit,
    pendingImages: List<ImagePart>,
    onRemoveImage: (Int) -> Unit,
    pendingFiles: List<AttachedFile> = emptyList(),
    onRemoveFile: (Int) -> Unit = {},
    onAttachImage: () -> Unit = {},
    onAttachFile: () -> Unit = {},
    onAttachDocument: () -> Unit = {},
) {
    var attachMenu by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (pendingImages.isNotEmpty() || pendingFiles.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${pendingImages.size + pendingFiles.size} attached:",
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
                pendingFiles.forEachIndexed { index, f ->
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
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = f.path.substringAfterLast('/'),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp, end = 0.dp),
                        )
                        IconButton(
                            onClick = { onRemoveFile(index) },
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
        // Mockup input: one flat stone strip, attach icon left, field, send.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(InputStone)
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                IconButton(onClick = { attachMenu = true }, modifier = Modifier.size(34.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_attach),
                        contentDescription = "attach",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("image from device", fontSize = 12.sp) },
                        onClick = {
                            attachMenu = false
                            onAttachImage()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("file or folder from workspace", fontSize = 12.sp) },
                        onClick = {
                            attachMenu = false
                            onAttachFile()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("document from device", fontSize = 12.sp) },
                        onClick = {
                            attachMenu = false
                            onAttachDocument()
                        },
                    )
                }
            }
            TextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                placeholder = { Text("Type anything here…", fontSize = 14.sp) },
                modifier = Modifier.weight(1f),
                // Enter inserts a line break by default; "send on Enter" is an
                // opt-in setting (General, Connections tab) — default OFF.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = if (sendOnEnter) ImeAction.Send else ImeAction.None,
                ),
                keyboardActions = KeyboardActions(onSend = {
                    if (sendOnEnter) onSend()
                }),
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = InputStone,
                    unfocusedContainerColor = InputStone,
                    disabledContainerColor = InputStone,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
            )
            if (isRunning) {
                // Stop: square icon inside the usual send-button footprint.
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AgentBubble)
                        .clickable(onClick = onStop)
                        .padding(2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(UserBubbleText),
                    )
                }
            } else {
                Button(
                    onClick = onSend,
                    enabled = enabled &&
                        (value.isNotBlank() || pendingImages.isNotEmpty() || pendingFiles.isNotEmpty()),
                ) {
                    Text("Send")
                }
            }
        }
    }
}

// -- chat history (M1.3) ------------------------------------------------------

/** Lists saved chats: open (continue), rename, delete (with confirm), new chat. */
@Composable
private fun HistoryDialog(
    currentSessionId: String?,
    onNewChat: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val controller = Holder.app.chatController
    val cScope = rememberCoroutineScope()
    val sessions = remember { mutableStateOf<List<ChatSession>?>(null) }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deletingId by remember { mutableStateOf<String?>(null) }
    // Multi-select delete (M1.4h).
    var multiSelect by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmMulti by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        sessions.value = withContext(Dispatchers.IO) { controller.listSessions() }
    }

    fun reload() {
        cScope.launch {
            sessions.value = withContext(Dispatchers.IO) { controller.listSessions() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Chats", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
        },
        text = {
            val list = sessions.value
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(onClick = {
                        onNewChat()
                        reload()
                    }) {
                        Text("new chat")
                    }
                    TextButton(onClick = {
                        multiSelect = !multiSelect
                        selectedIds = emptySet()
                        confirmMulti = false
                    }) {
                        Text(
                            if (multiSelect) "done selecting" else "select several",
                            fontSize = 11.sp,
                            color = TextSecondary,
                        )
                    }
                }
                if (multiSelect) {
                    TextButton(
                        onClick = { confirmMulti = true },
                        enabled = selectedIds.isNotEmpty(),
                    ) {
                        Text(
                            "delete selected (${selectedIds.size})",
                            fontSize = 11.sp,
                            color = AmberStatus,
                        )
                    }
                }
                var query by remember { mutableStateOf("") }
                if (list != null) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("search chats…", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = TileStone,
                            unfocusedContainerColor = TileStone,
                            focusedIndicatorColor = BevelLight,
                            unfocusedIndicatorColor = BevelLight,
                        ),
                    )
                }
                if (confirmMulti) {
                    // Multi-delete confirmation replaces the list (M1.4h).
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Delete ${selectedIds.size} chat(s)?",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                        )
                        Text(
                            "This cannot be undone.",
                            fontSize = 12.sp,
                            color = TextSecondary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                confirmMulti = false
                                selectedIds.forEach { onDelete(it) }
                                selectedIds = emptySet()
                                multiSelect = false
                                reload()
                            }) {
                                Text("delete")
                            }
                            TextButton(onClick = { confirmMulti = false }) {
                                Text("cancel")
                            }
                        }
                    }
                } else {
                val del = list?.firstOrNull { it.id == deletingId }
                if (del != null) {
                    // Delete confirmation replaces the list.
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            "Delete this chat?",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary,
                        )
                        Text(
                            "'${del.title}' — ${del.turns.size} messages. This cannot be undone.",
                            fontSize = 12.sp,
                            color = TextSecondary,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                deletingId = null
                                onDelete(del.id)
                                reload()
                            }) {
                                Text("delete")
                            }
                            TextButton(onClick = { deletingId = null }) {
                                Text("cancel")
                            }
                        }
                    }
                } else if (list == null) {
                    Text("loading…", fontSize = 12.sp, color = TextSecondary)
                } else if (list.isEmpty()) {
                    Text(
                        "no saved chats yet — a chat is saved once it gets a reply.",
                        fontSize = 12.sp,
                        color = TextSecondary,
                    )
                } else {
                    val visible = list.filter { s ->
                        query.isBlank() ||
                            s.title.contains(query, true) ||
                            s.turns.any { t -> t.userText.contains(query, true) }
                    }
                    if (visible.isEmpty()) {
                        Text("no matches.", fontSize = 12.sp, color = TextSecondary)
                    }
                    visible.forEach { s ->
                        val isCurrent = s.id == currentSessionId
                        if (renamingId == s.id) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                TextField(
                                    value = renameText,
                                    onValueChange = { renameText = it },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                                        focusedContainerColor = TileStone,
                                        unfocusedContainerColor = TileStone,
                                        focusedIndicatorColor = BevelLight,
                                        unfocusedIndicatorColor = BevelLight,
                                    ),
                                )
                                TextButton(onClick = {
                                    renamingId = null
                                    onRename(s.id, renameText)
                                    reload()
                                }) {
                                    Text("save")
                                }
                                TextButton(onClick = { renamingId = null }) {
                                    Text("cancel")
                                }
                            }
                        } else {
                            val isSel = s.id in selectedIds
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (multiSelect && isSel) TileStone
                                        else androidx.compose.ui.graphics.Color.Transparent,
                                    )
                                    .clickable {
                                        if (multiSelect) {
                                            selectedIds =
                                                if (isSel) selectedIds - s.id else selectedIds + s.id
                                        } else {
                                            onOpen(s.id)
                                        }
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    if (multiSelect) {
                                        Text(
                                            text = if (isSel) "☑" else "☐",
                                            fontSize = 12.sp,
                                            color = if (isSel) TextPrimary else TextSecondary,
                                        )
                                    }
                                    Text(
                                        text = s.title,
                                        fontSize = 12.sp,
                                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (isCurrent) LinkSearch else TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (!multiSelect) {
                                        IconButton(
                                            onClick = {
                                                renamingId = s.id
                                                renameText = s.title
                                            },
                                            modifier = Modifier.size(22.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_edit),
                                                contentDescription = "rename",
                                                tint = TextSecondary,
                                                modifier = Modifier.size(11.dp),
                                            )
                                        }
                                        IconButton(
                                            onClick = { deletingId = s.id },
                                            modifier = Modifier.size(22.dp),
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_delete),
                                                contentDescription = "delete",
                                                tint = AmberStatus,
                                                modifier = Modifier.size(11.dp),
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "${timeAgo(s.updatedAt)} · ${s.turns.size} messages",
                                    fontSize = 10.sp,
                                    color = TextSecondary,
                                )
                            }
                        }
                    }
                }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("close")
            }
        },
    )
}

// -- workspace file/folder picker (M1.3) --------------------------------------

/**
 * Drill-down picker over the active workspace. Tapping a file attaches it
 * (with a preview snippet); tapping a folder drills in, and "attach this
 * folder" attaches a file listing instead.
 */
@Composable
private fun AttachFilePicker(
    fs: FileService,
    onPickFile: (path: String, preview: String) -> Unit,
    onPickFolder: (path: String, listing: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var dir by remember { mutableStateOf(".") }
    val entries = remember(dir) { mutableStateOf<List<FileService.DirEntry>?>(null) }
    LaunchedEffect(dir) {
        entries.value = null
        entries.value = withContext(Dispatchers.IO) { fs.browse(dir) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (dir != ".") {
                    TextButton(onClick = { dir = parentOf(dir) }) {
                        Text("up", fontSize = 11.sp, color = TextSecondary)
                    }
                }
                Text(
                    text = "workspace: $dir",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        },
        text = {
            val list = entries.value
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (dir != ".") {
                    TextButton(onClick = {
                        onPickFolder(dir, folderListing(fs, dir))
                    }) {
                        Text("attach this folder", fontSize = 12.sp)
                    }
                }
                if (list == null) {
                    Text("loading…", fontSize = 12.sp, color = TextSecondary)
                } else {
                    list.forEach { e ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    if (e.isDirectory) {
                                        dir = joinPath(dir, e.name)
                                    } else {
                                        onPickFile(joinPath(dir, e.name), filePreview(fs, joinPath(dir, e.name)))
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = if (e.isDirectory) "${e.name}/" else e.name,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (e.isDirectory) LinkSearch else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (!e.isDirectory) {
                                Text("${e.sizeBytes} B", fontSize = 10.sp, color = TextSecondary)
                            }
                        }
                    }
                    if (list.isEmpty()) {
                        Text("(empty folder)", fontSize = 11.sp, color = TextSecondary)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("cancel")
            }
        },
    )
}

// -- attach helpers (M1.3) -----------------------------------------------------

private fun joinPath(base: String, name: String): String =
    if (base == ".") name else "$base/$name"

private fun parentOf(path: String): String =
    path.substringBeforeLast('/', ".")

/** First ~8k chars of a text file, or a note for binary/blocked files. */
private fun filePreview(fs: FileService, path: String): String =
    fs.read(path, 8_000).takeIf { it.ok }?.text?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "(binary or unreadable file)"

/** A capped, newline-joined file listing of a folder, for the model prompt. */
private fun folderListing(fs: FileService, dir: String): String {
    val pattern = if (dir == ".") "**/*" else "$dir/**/*"
    val files = fs.glob(pattern, 100)
        .takeIf { it.ok }?.text
        ?.lineSequence()
        ?.filter { it.isNotBlank() }
        ?.toList()
        ?: emptyList()
    if (files.isEmpty()) return "(empty folder)"
    return files.take(60).joinToString("\n") +
        (if (files.size > 60) "\n… (${files.size} files total)" else "")
}

/** Picks a photo from the device: decode, downscale to 1280px max edge, JPEG q80. */
private fun decodePickedImage(context: Context, uri: Uri): ImagePart? = runCatching {
    val input = context.contentResolver.openInputStream(uri) ?: return@runCatching null
    val original = BitmapFactory.decodeStream(input)
    input.close()
    if (original == null) return@runCatching null
    val w = original.width
    val h = original.height
    val maxEdge = 1280
    val scale = if (max(w, h) > maxEdge) maxEdge.toFloat() / max(w, h) else 1f
    val bmp = if (scale < 1f) {
        val m = Matrix()
        m.preScale(scale, scale)
        Bitmap.createBitmap(original, 0, 0, w, h, m, true)
    } else {
        original
    }
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
    ImagePart("image/jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
}.getOrNull()

/** Display name for a document-picked URI (falls back to "document"). */
private fun queryDocumentName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (idx >= 0 && c.moveToFirst()) c.getString(idx) else "document"
    } ?: "document"
}.getOrDefault("document")

private fun timeAgo(ts: Long): String {
    val s = (System.currentTimeMillis() - ts) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}


