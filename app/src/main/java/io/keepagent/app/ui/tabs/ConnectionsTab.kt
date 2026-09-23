package io.keepagent.app.ui.tabs

import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.content.FileProvider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.keepagent.app.ApiConnection
import io.keepagent.app.ApiModelConfig
import io.keepagent.app.Holder
import io.keepagent.app.KeepAgentApp
import io.keepagent.app.R
import io.keepagent.app.update.AppUpdateControl
import io.keepagent.app.ui.theme.AmberStatus
import io.keepagent.app.ui.theme.BevelLight
import io.keepagent.app.ui.theme.LinkRead
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.TileStone
import io.keepagent.app.ui.theme.UserBubble
import io.keepagent.addonsapi.llm.LlmModel
import io.keepagent.core.llm.OpenAiCompatibleClient
import io.keepagent.core.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.net.ssl.SSLException

/**
 * Connections tab (M1.1): named OpenAI-compatible API connections —
 * OpenRouter, a local server, or any custom base URL. The active
 * connection powers the model profile the provider and chat read.
 */
@Composable
fun ConnectionsTab() {
    val app = Holder.app
    val store = app.connections
    var list by remember { mutableStateOf(store.list()) }
    var activeId by remember { mutableStateOf(store.activeId()) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ApiConnection?>(null) }
    var testResults by remember { mutableStateOf(emptyMap<String, String>()) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        list = store.list()
        activeId = store.activeId()
        app.chatController.refreshContextLimit()
    }

    fun runTest(key: String, baseUrl: String, apiKey: String) {
        if (baseUrl.isBlank()) return
        testResults = testResults + (key to "testing…")
        scope.launch {
            testResults = testResults + (key to probeEndpoint(baseUrl, apiKey))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Model providers",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Text(
                text = "Connect an OpenAI-compatible endpoint, then choose which models to make available in Chat. " +
                    "Context length and future model settings are stored per model.",
                fontSize = 11.sp,
                color = TextSecondary,
            )
            OutlinedButton(onClick = { creating = true }) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = null,
                    modifier = Modifier
                        .width(14.dp)
                        .height(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add provider")
            }
        }

        GeneralSection(app)

        if (list.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No providers yet. Add one to choose your models.",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(list, key = { it.id }) { conn ->
                    ConnectionRow(
                        conn = conn,
                        isActive = conn.id == activeId,
                        testResult = testResults[conn.id],
                        onEdit = { editing = conn },
                        onTest = { runTest(conn.id, conn.baseUrl, conn.apiKey) },
                        onSetActive = {
                            store.setActive(conn.id)
                            refresh()
                        },
                        onDelete = {
                            store.remove(conn.id)
                            refresh()
                        },
                    )
                }
            }
        }
    }

    if (creating) {
        ConnectionDialog(
            existing = null,
            onSave = { name, baseUrl, apiKey, models, activeModel ->
                store.add(name, baseUrl, apiKey, models, activeModel)
                refresh()
            },
            onDelete = null,
            onDismiss = { creating = false },
        )
    }
    if (editing != null) {
        ConnectionDialog(
            existing = editing!!,
            onSave = { name, baseUrl, apiKey, models, activeModel ->
                store.update(
                    editing!!.copy(
                        name = name,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = activeModel,
                        models = models,
                    ),
                )
                refresh()
            },
            onDelete = {
                store.remove(editing!!.id)
                editing = null
                refresh()
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ConnectionRow(
    conn: ApiConnection,
    isActive: Boolean,
    testResult: String?,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onSetActive: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(TileStone)
            .border(
                1.dp,
                if (isActive) AmberStatus else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onEdit)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conn.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isActive) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        painter = painterResource(R.drawable.ic_check),
                        contentDescription = null,
                        tint = AmberStatus,
                        modifier = Modifier
                            .width(12.dp)
                            .height(12.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "active",
                        fontSize = 10.sp,
                        color = AmberStatus,
                    )
                }
            }
            Text(
                text = conn.baseUrl,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${conn.models.size} model${if (conn.models.size == 1) "" else "s"}" +
                    (conn.model.takeIf { it.isNotBlank() }?.let { " · active: $it" } ?: ""),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "context: ${formatTokens(conn.effectiveContextLimit)}" + when {
                    conn.activeModelConfig?.contextLength != null -> " (custom)"
                    conn.activeModelConfig?.providerContextLength != null -> " (provider maximum)"
                    else -> " (metadata unavailable)"
                },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (testResult != null) {
                Text(
                    text = testResult,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (testResult.startsWith("OK")) UserBubble else LinkRead,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!isActive) {
            TextButton(onClick = onSetActive) {
                Text("Set active", fontSize = 11.sp)
            }
        }
        TextButton(onClick = onTest) {
            Text("Test", fontSize = 11.sp)
        }
        IconButton(onClick = onDelete) {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = "Delete",
                tint = LinkRead,
                modifier = Modifier
                    .width(14.dp)
                    .height(14.dp),
            )
        }
    }
}

private data class ModelDraft(
    val id: String,
    val providerContextLength: Int?,
    val contextLength: String = "",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionDialog(
    existing: ApiConnection?,
    onSave: (String, String, String, List<ApiModelConfig>, String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(existing?.apiKey ?: "") }
    var configured by remember {
        mutableStateOf(
            existing?.models.orEmpty().map {
                ModelDraft(it.id, it.providerContextLength, it.contextLength?.toString().orEmpty())
            },
        )
    }
    var fetchedModels by remember { mutableStateOf<List<LlmModel>>(emptyList()) }
    var selectedModelId by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var fetchingModels by remember { mutableStateOf(false) }
    var fetchStatus by remember { mutableStateOf("Enter a base URL to load models automatically.") }
    val scope = rememberCoroutineScope()

    suspend fun loadModels(automatic: Boolean) {
        if (baseUrl.isBlank() || fetchingModels) return
        fetchingModels = true
        fetchStatus = if (automatic) "Loading models automatically…" else "Fetching model list…"
        val result = runCatching {
            fetchModels(baseUrl.trim(), apiKey.trim(), existing?.model)
        }
        result.onSuccess { models ->
            fetchedModels = models
            val byId = models.associateBy { it.id }
            configured = configured.map { draft ->
                draft.copy(
                    providerContextLength = byId[draft.id]?.contextWindow
                        ?: draft.providerContextLength,
                )
            }
            if (selectedModelId !in models.map { it.id }) {
                selectedModelId = models.firstOrNull { candidate ->
                    configured.none { it.id == candidate.id }
                }?.id.orEmpty()
            }
            fetchStatus = when {
                models.isEmpty() -> "The provider returned no models."
                automatic -> "${models.size} models loaded automatically."
                else -> "${models.size} models fetched."
            }
        }.onFailure {
            fetchStatus = modelFetchError(it)
        }
        fetchingModels = false
    }

    // Debouncing avoids a request for every keystroke while still making a
    // completed endpoint/key immediately useful without another tap.
    LaunchedEffect(baseUrl, apiKey) {
        if (baseUrl.isBlank()) {
            fetchedModels = emptyList()
            fetchStatus = "Enter a base URL to load models automatically."
            return@LaunchedEffect
        }
        delay(750)
        loadModels(automatic = true)
    }

    val invalidModels = configured.filter { draft ->
        !validContextLength(draft.contextLength)
    }
    val canSave = name.isNotBlank() && baseUrl.isNotBlank() &&
        configured.isNotEmpty() && invalidModels.isEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add model provider" else "Edit model provider") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Provider", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Provider name") },
                    placeholder = { Text("e.g. OpenRouter") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        if (name.isBlank()) name = "OpenRouter"
                        baseUrl = "https://openrouter.ai/api/v1"
                    }) { Text("OpenRouter", fontSize = 11.sp) }
                    TextButton(onClick = {
                        if (name.isBlank()) name = "Local"
                        baseUrl = "http://localhost:11434/v1"
                    }) { Text("Local", fontSize = 11.sp) }
                }
                TextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://openrouter.ai/api/v1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                TextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    placeholder = { Text("Optional for local providers") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Models", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    OutlinedButton(
                        onClick = { scope.launch { loadModels(automatic = false) } },
                        enabled = baseUrl.isNotBlank() && !fetchingModels,
                    ) {
                        Text(if (fetchingModels) "Fetching…" else "Fetch model list", fontSize = 11.sp)
                    }
                }
                Text(
                    text = fetchStatus,
                    fontSize = 10.sp,
                    color = if (fetchStatus.startsWith("Could not")) LinkRead else TextSecondary,
                )

                ExposedDropdownMenuBox(
                    expanded = menuOpen,
                    onExpandedChange = { if (fetchedModels.isNotEmpty()) menuOpen = !menuOpen },
                ) {
                    TextField(
                        value = selectedModelId,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Available models") },
                        placeholder = { Text("Fetch models to choose") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menuOpen) },
                        modifier = Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                        enabled = fetchedModels.isNotEmpty(),
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        modifier = Modifier.heightIn(max = 300.dp),
                    ) {
                        fetchedModels.forEach { model ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            model.id + (model.contextWindow?.let { " · max ${formatTokens(it)}" } ?: ""),
                                            fontSize = 10.sp,
                                            color = TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                onClick = {
                                    selectedModelId = model.id
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
                OutlinedButton(
                    onClick = {
                        val model = fetchedModels.firstOrNull { it.id == selectedModelId }
                            ?: return@OutlinedButton
                        if (configured.none { it.id == model.id }) {
                            configured = configured + ModelDraft(model.id, model.contextWindow)
                        }
                        selectedModelId = fetchedModels.firstOrNull { candidate ->
                            configured.none { it.id == candidate.id }
                        }?.id.orEmpty()
                    },
                    enabled = selectedModelId.isNotBlank() && configured.none { it.id == selectedModelId },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Add selected model")
                }

                if (configured.isEmpty()) {
                    Text(
                        "Choose at least one model from the fetched list.",
                        fontSize = 10.sp,
                        color = TextSecondary,
                    )
                } else {
                    Text("Configured models", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    configured.forEach { draft ->
                        val index = configured.indexOfFirst { it.id == draft.id }
                        val valueValid = validContextLength(draft.contextLength)
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(TileStone)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    draft.id,
                                    modifier = Modifier.weight(0.45f),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextPrimary,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                TextField(
                                    value = draft.contextLength,
                                    onValueChange = { changed ->
                                        val digits = changed.filter(Char::isDigit)
                                        configured = configured.toMutableList().also {
                                            it[index] = draft.copy(contextLength = digits)
                                        }
                                    },
                                    label = { Text("Context length", fontSize = 10.sp) },
                                    placeholder = {
                                        Text(
                                            draft.providerContextLength?.let { "Auto: ${formatTokens(it)}" }
                                                ?: "Auto",
                                            fontSize = 10.sp,
                                        )
                                    },
                                    isError = !valueValid,
                                    modifier = Modifier.weight(0.55f),
                                    singleLine = true,
                                )
                                IconButton(
                                    onClick = { configured = configured.filterNot { it.id == draft.id } },
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = "Remove ${draft.id}",
                                        tint = LinkRead,
                                    )
                                }
                            }
                            Text(
                                when {
                                    !valueValid -> "Enter at least 1,000 tokens, or leave blank."
                                    draft.contextLength.isNotBlank() -> "Custom context length"
                                    draft.providerContextLength != null ->
                                        "Blank uses provider maximum: ${formatTokens(draft.providerContextLength)} tokens"
                                    else -> "Blank uses provider metadata when available"
                                },
                                fontSize = 9.sp,
                                color = if (valueValid) TextSecondary else LinkRead,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val models = configured.map {
                        ApiModelConfig(
                            id = it.id,
                            providerContextLength = it.providerContextLength,
                            contextLength = it.contextLength.toIntOrNull(),
                        )
                    }
                    val activeModel = existing?.model?.takeIf { id -> models.any { it.id == id } }
                        ?: models.firstOrNull()?.id.orEmpty()
                    onSave(name.trim(), baseUrl.trim(), apiKey.trim(), models, activeModel)
                    onDismiss()
                },
                enabled = canSave,
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Delete provider", color = LinkRead) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * General settings (M1.4), surfaced on the Connections tab. Values go to
 * the shared SettingsStore — the provider addon, chat input bar and the
 * context gauge read the same keys.
 */
@Composable
private fun GeneralSection(app: KeepAgentApp) {
    val store = app.settingsStore
    var open by remember { mutableStateOf(false) }
    val sendOnEnter = store.getString(SettingsStore.NS_GENERAL, "sendOnEnter") == "true"
    val verbose = store.getString(SettingsStore.NS_GENERAL, "llmLogVerbose") == "true"
    val maxRetries = store.getString(SettingsStore.NS_MODEL, "maxRetries") ?: "1"
    val timeoutSec = store.getString(SettingsStore.NS_MODEL, "timeoutSeconds") ?: "600"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .clickable { open = !open }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = if (open) "▾" else "▸", fontSize = 10.sp, color = TextSecondary)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "general", fontSize = 11.sp, color = TextSecondary)
    }

    if (open) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ToggleRow(
                label = "send on enter (default: line break)",
                on = sendOnEnter,
                onToggle = {
                    store.setString(
                        SettingsStore.NS_GENERAL,
                        "sendOnEnter",
                        if (sendOnEnter) "false" else "true",
                    )
                },
            )
            ToggleRow(
                label = "verbose LLM log (console tab)",
                on = verbose,
                onToggle = {
                    store.setString(
                        SettingsStore.NS_GENERAL,
                        "llmLogVerbose",
                        if (verbose) "false" else "true",
                    )
                },
            )
            NumberRow(
                label = "max retries (0–5)",
                value = maxRetries,
                onCommit = { store.setString(SettingsStore.NS_MODEL, "maxRetries", it) },
            )
            NumberRow(
                label = "request timeout s (30–3600)",
                value = timeoutSec,
                onCommit = { store.setString(SettingsStore.NS_MODEL, "timeoutSeconds", it) },
            )
            BackupRow(app)
            AppUpdateControl()
        }
    }
}

/**
 * Backup export/import (M1.4h): settings + saved chats + event log packed as
 * a zip. Export shares it; import restores from a picked zip.
 */
@Composable
private fun BackupRow(app: KeepAgentApp) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var notice by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun doExport() {
        busy = true
        scope.launch(Dispatchers.IO) {
            val res = runCatching {
                val zip = File(context.cacheDir, "keepagent-backup-${System.currentTimeMillis()}.zip")
                ZipOutputStream(zip.outputStream()).use { zos ->
                    listOf(
                        SettingsStore.NS_GENERAL,
                        SettingsStore.NS_SESSIONS,
                        SettingsStore.NS_ADDONS,
                        SettingsStore.NS_MODEL,
                        SettingsStore.NS_CONNECTIONS,
                    ).forEach { ns ->
                        zos.putNextEntry(java.util.zip.ZipEntry("settings/$ns.json"))
                        zos.write(app.settingsStore.get(ns).toString().toByteArray())
                        zos.closeEntry()
                    }
                    File(app.filesDir, "keepagent/chats").listFiles()
                        ?.filter { it.isFile }
                        ?.forEach { f ->
                            zos.putNextEntry(java.util.zip.ZipEntry("chats/${f.name}"))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    val ev = File(app.storage.root, "events/stream.jsonl")
                    if (ev.exists()) {
                        zos.putNextEntry(java.util.zip.ZipEntry("events/stream.jsonl"))
                        ev.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
                zip
            }
            withContext(Dispatchers.Main) {
                busy = false
                res.fold(
                    { zip ->
                        val uri = FileProvider.getUriForFile(
                            context,
                            "io.keepagent.app.fileprovider",
                            zip,
                        )
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                },
                                "share backup",
                            ),
                        )
                        notice = "backup exported: settings + chats + event log"
                    },
                    { e -> notice = "export failed: ${e.message}" },
                )
            }
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val res = runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("could not open backup file")
                var settingsCount = 0
                var chatCount = 0
                ZipInputStream(bytes.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val data = zis.readBytes()
                            val name = entry.name
                            if (name.startsWith("settings/") && name.endsWith(".json")) {
                                val ns = name.removePrefix("settings/").removeSuffix(".json")
                                app.settingsStore.put(
                                    ns,
                                    kotlinx.serialization.json.Json.parseToJsonElement(String(data, Charsets.UTF_8)).jsonObject,
                                )
                                settingsCount++
                            } else if (name.startsWith("chats/")) {
                                val base = name.removePrefix("chats/")
                                if (!base.contains("..") && !base.contains("/")) {
                                    val dir = File(app.filesDir, "keepagent/chats")
                                    dir.mkdirs()
                                    File(dir, base).writeBytes(data)
                                    chatCount++
                                }
                            } else if (name == "events/stream.jsonl") {
                                val evDir = File(app.storage.root, "events")
                                evDir.mkdirs()
                                File(evDir, "stream.jsonl").writeBytes(data)
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
                "settings: $settingsCount · chats: $chatCount"
            }
            withContext(Dispatchers.Main) {
                busy = false
                notice = res.fold(
                    { r -> "imported ($r) — restart the app to be safe" },
                    { e -> "import failed: ${e.message}" },
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "backup",
            fontSize = 10.sp,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (busy) "working…" else "export",
            fontSize = 10.sp,
            color = TextPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(TileStone)
                .clickable(enabled = !busy) { doExport() }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "import",
            fontSize = 10.sp,
            color = TextPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(TileStone)
                .clickable(enabled = !busy) { importPicker.launch(arrayOf("application/zip", "*/*")) }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
    notice?.let { n ->
        Text(
            text = n,
            fontSize = 9.sp,
            color = AmberStatus,
        )
    }
}

@Composable
private fun ToggleRow(label: String, on: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (on) "on" else "off",
            fontSize = 10.sp,
            color = if (on) UserBubble else TextSecondary,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun NumberRow(label: String, value: String, onCommit: (String) -> Unit) {
    var text by remember { mutableStateOf(value) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 10.sp,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        TextField(
            value = text,
            onValueChange = {
                val digits = it.filter { c -> c.isDigit() }
                text = digits
                if (digits.isNotEmpty()) onCommit(digits)
            },
            singleLine = true,
            modifier = Modifier
                .width(90.dp)
                .height(34.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = TileStone,
                unfocusedContainerColor = TileStone,
                focusedIndicatorColor = BevelLight,
                unfocusedIndicatorColor = BevelLight,
            ),
        )
    }
}

/** Fetches model metadata from `GET /models` without touching the model profile. */
private suspend fun fetchModels(
    baseUrl: String,
    apiKey: String,
    selectedModel: String? = null,
): List<LlmModel> {
    val client = OpenAiCompatibleClient(baseUrl, apiKey)
    return try {
        withContext(Dispatchers.IO) {
            val models = client.fetchModels()
            val index = models.indexOfFirst { it.id == selectedModel && it.contextWindow == null }
            if (index < 0) return@withContext models
            val detail = client.fetchModelDetails(selectedModel!!)?.takeIf { it.contextWindow != null }
                ?: return@withContext models
            models.toMutableList().also { it[index] = models[index].copy(contextWindow = detail.contextWindow) }
        }
    } finally {
        try {
            client.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Connection probe: `GET /models` against the given endpoint with the given
 * key, without touching the model profile. Returns a short human-readable
 * result string ("OK — N models" or "failed: …").
 */
private suspend fun probeEndpoint(baseUrl: String, apiKey: String): String =
    runCatching { fetchModels(baseUrl, apiKey) }
        .map { "OK — ${it.size} models" }
        .getOrElse { "failed: ${it.message ?: it.javaClass.simpleName}" }

private fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 && tokens % 1_000_000 == 0 -> "${tokens / 1_000_000}M"
    tokens >= 1_000 && tokens % 1_000 == 0 -> "${tokens / 1_000}K"
    else -> tokens.toString()
}

private fun validContextLength(value: String): Boolean =
    value.isBlank() || value.toIntOrNull()?.let { it >= 1_000 } == true

private fun modelFetchError(error: Throwable): String {
    val causes = generateSequence(error as Throwable?) { it.cause }
    return if (causes.any { it is SSLException }) {
        "Secure connection failed. Check the provider certificate and device date."
    } else {
        "Could not fetch models: ${error.message ?: "unknown error"}".take(180)
    }
}
