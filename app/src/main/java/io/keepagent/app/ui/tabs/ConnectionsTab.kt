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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.keepagent.app.ui.theme.LinkRead
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.TileStone

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

    fun refresh() {
        list = store.list()
        activeId = store.activeId()
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
                        onEdit = { editing = conn },
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
    onEdit: () -> Unit,
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
        }
        if (!isActive) {
            TextButton(onClick = onSetActive) {
                Text("Set active", fontSize = 11.sp)
            }
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
