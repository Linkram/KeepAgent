package io.keepagent.app.ui.tabs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.keepagent.app.ApiConnection
import io.keepagent.app.Holder
import io.keepagent.app.R
import io.keepagent.app.ui.theme.AmberStatus
import io.keepagent.app.ui.theme.BevelLight
import io.keepagent.app.ui.theme.LinkRead
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.TileStone
import io.keepagent.app.ui.theme.UserBubble
import io.keepagent.core.llm.OpenAiCompatibleClient
import io.keepagent.core.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                text = "API connections",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
            )
            Text(
                text = "OpenAI-compatible endpoints — OpenRouter, a local server, or any custom base URL. " +
                    "The active connection powers the model profile used by Chat.",
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
                Text("Add connection")
            }
        }

        GeneralSection(app.settingsStore)

        if (list.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No connections yet. Add one to configure the model.",
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
            onSave = { name, baseUrl, apiKey, model ->
                store.add(name, baseUrl, apiKey, model)
                refresh()
            },
            onDelete = null,
            onDismiss = { creating = false },
        )
    }
    if (editing != null) {
        ConnectionDialog(
            existing = editing!!,
            onSave = { name, baseUrl, apiKey, model ->
                store.update(
                    editing!!.copy(
                        name = name,
                        baseUrl = baseUrl,
                        apiKey = apiKey,
                        model = model,
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
                text = "model: ${conn.model.ifBlank { "—" }}",
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

@Composable
private fun ConnectionDialog(
    existing: ApiConnection?,
    onSave: (String, String, String, String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl ?: "") }
    var apiKey by remember { mutableStateOf(existing?.apiKey ?: "") }
    var model by remember { mutableStateOf(existing?.model ?: "") }
    var testResult by remember { mutableStateOf<String?>(null) }
    var fetchingModels by remember { mutableStateOf(false) }
    var fetchedModels by remember { mutableStateOf<List<String>?>(null) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val canSave = name.isNotBlank() && baseUrl.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New connection" else "Edit connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. OpenRouter") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(onClick = {
                        if (name.isBlank()) name = "OpenRouter"
                        baseUrl = "https://openrouter.ai/api/v1"
                    }) {
                        Text("OpenRouter", fontSize = 11.sp)
                    }
                    TextButton(onClick = {
                        if (name.isBlank()) name = "Local"
                        baseUrl = "http://localhost:11434/v1"
                    }) {
                        Text("Local", fontSize = 11.sp)
                    }
                    TextButton(
                        onClick = {
                            val b = baseUrl.trim()
                            val k = apiKey.trim()
                            testResult = "testing…"
                            scope.launch {
                                testResult = probeEndpoint(b, k)
                            }
                        },
                        enabled = baseUrl.isNotBlank(),
                    ) {
                        Text("Test", fontSize = 11.sp)
                    }
                }
                val tr = testResult
                if (tr != null) {
                    Text(
                        text = tr,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = if (tr.startsWith("OK")) UserBubble else LinkRead,
                        maxLines = 3,
                    )
                }
                TextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    placeholder = { Text("https://openrouter.ai/api/v1") },
                    singleLine = true,
                )
                TextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    placeholder = { Text("sk-…") },
                    singleLine = true,
                )
                TextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    placeholder = { Text("e.g. openai/gpt-4o-mini") },
                    singleLine = true,
                )
                TextButton(
                    onClick = {
                        fetchingModels = true
                        fetchError = null
                        fetchedModels = null
                        scope.launch {
                            val r = runCatching {
                                fetchModelIds(baseUrl.trim(), apiKey.trim())
                            }
                            if (r.isSuccess) {
                                fetchedModels = r.getOrThrow()
                            } else {
                                fetchError = r.exceptionOrNull()?.message ?: "fetch failed"
                            }
                            fetchingModels = false
                        }
                    },
                    enabled = baseUrl.isNotBlank() && !fetchingModels,
                ) {
                    Text(
                        text = if (fetchingModels) "fetching…" else "Fetch models for this endpoint",
                        fontSize = 11.sp,
                    )
                }
                val ferr = fetchError
                if (ferr != null) {
                    Text(
                        text = ferr,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = LinkRead,
                        maxLines = 2,
                    )
                }
                val fms = fetchedModels
                if (fms != null) {
                    if (fms.isEmpty()) {
                        Text(
                            text = "endpoint reported no models — type the model ID in the field above",
                            fontSize = 10.sp,
                            color = TextSecondary,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            fms.take(30).forEach { id ->
                                Text(
                                    text = id,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (id == model) UserBubble else TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .clickable { model = id }
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                            if (fms.size > 30) {
                                Text(
                                    text = "… ${fms.size - 30} more — type the exact ID in the field above",
                                    fontSize = 9.sp,
                                    color = TextSecondary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(name.trim(), baseUrl.trim(), apiKey.trim(), model.trim())
                    onDismiss()
                },
                enabled = canSave,
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("Delete", color = LinkRead)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
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
private fun GeneralSection(store: SettingsStore) {
    var open by remember { mutableStateOf(false) }
    val sendOnEnter = store.getString(SettingsStore.NS_GENERAL, "sendOnEnter") == "true"
    val verbose = store.getString(SettingsStore.NS_GENERAL, "llmLogVerbose") == "true"
    val maxRetries = store.getString(SettingsStore.NS_MODEL, "maxRetries") ?: "1"
    val timeoutSec = store.getString(SettingsStore.NS_MODEL, "timeoutSeconds") ?: "600"
    val contextLimit = store.getString(SettingsStore.NS_MODEL, "contextLimit") ?: "128000"

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
            NumberRow(
                label = "context limit tokens",
                value = contextLimit,
                onCommit = { store.setString(SettingsStore.NS_MODEL, "contextLimit", it) },
            )
        }
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

/** Fetches model IDs from `GET /models` without touching the model profile. */
private suspend fun fetchModelIds(baseUrl: String, apiKey: String): List<String> {
    val client = OpenAiCompatibleClient(baseUrl, apiKey)
    return try {
        withContext(Dispatchers.IO) { client.fetchModels().map { it.id } }
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
    runCatching { fetchModelIds(baseUrl, apiKey) }
        .map { "OK — ${it.size} models" }
        .getOrElse { "failed: ${it.message ?: it.javaClass.simpleName}" }
