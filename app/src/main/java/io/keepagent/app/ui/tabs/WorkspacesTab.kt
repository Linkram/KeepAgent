package io.keepagent.app.ui.tabs

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.core.content.FileProvider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.keepagent.app.Holder
import io.keepagent.app.git.GitService
import io.keepagent.app.handoff.HandoffExporter
import io.keepagent.app.ui.common.FileOpener
import io.keepagent.app.ui.common.Recents
import io.keepagent.core.fs.FileService
import io.keepagent.core.settings.FileAccess
import io.keepagent.core.workspace.WorkspaceInfo
import io.keepagent.core.workspace.WorkspaceManager
import io.keepagent.app.ui.theme.AmberStatus
import io.keepagent.app.ui.theme.BevelLight
import io.keepagent.app.ui.theme.LinkRead
import io.keepagent.app.ui.theme.TextPrimary
import io.keepagent.app.ui.theme.TextSecondary
import io.keepagent.app.ui.theme.TileStone
import io.keepagent.app.ui.theme.TileStoneSelected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Workspaces tab (M1, F-007): list, create, switch, and delete workspaces.
 * Switching re-points the file service's root (ADR-0002).
 */
@Composable
fun WorkspacesTab(chooserRequest: Int = 0, onProjectChanged: (String) -> Unit = {}) {
    val app = Holder.app
    var list by remember { mutableStateOf<List<WorkspaceInfo>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var cloneUrl by remember { mutableStateOf("") }
    // A workspace tab is primarily a file workspace, not a project chooser.
    // Open the active project immediately; the compact chooser is one tap away.
    var openWs by remember { mutableStateOf<String?>(app.workspaceManager.activeName()) }
    var deleteTarget by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var renameTarget by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var renameName by remember { mutableStateOf("") }
    var showProjectActions by remember { mutableStateOf(false) }
    var projectActionMode by remember { mutableStateOf("new") }

    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var importing by remember { mutableStateOf(false) }
    var cloning by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            scope.launch {
                var createdName: String? = null
                val result = runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    val suggested = android.provider.DocumentsContract.getTreeDocumentId(uri)
                        .substringAfterLast(':').substringAfterLast('/')
                    val projectName = name.ifBlank { suggested.ifBlank { "Phone folder" } }
                    val linked = app.workspaceManager.link(projectName, uri.toString())
                        ?: error("Choose a different project name.")
                    createdName = linked
                    withContext(Dispatchers.IO) { app.linkedProjectSync.sync(linked) }
                    linked
                }
                result.onSuccess { linked ->
                    app.workspaceManager.setActive(linked)
                    app.workspaceChanged()
                    onProjectChanged(linked)
                    name = ""
                    showProjectActions = false
                    openWs = linked
                    message = "Linked folder: $linked"
                    list = withContext(Dispatchers.IO) { app.workspaceManager.list() }
                }.onFailure {
                    createdName?.let { created -> app.workspaceManager.delete(created) }
                    message = "Could not link folder: ${it.message}"
                }
            }
        }
    }
    val importZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            val projectName = name
            scope.launch {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val stream = context.contentResolver.openInputStream(uri)
                            ?: error("Cannot open the selected archive.")
                        stream.use { app.workspaceManager.importZip(it, projectName) }
                    }
                    list = withContext(Dispatchers.IO) { app.workspaceManager.list() }
                    message = "Imported ${result.name}: ${result.files} files."
                    name = ""
                    showProjectActions = false
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = "Import failed: ${e.message}"
                } finally {
                    importing = false
                }
            }
        }
    }
    val active = app.workspaceManager.activeName()
    val fileAccess = FileAccess.from(
        app.settingsStore.getString(
            io.keepagent.core.settings.SettingsStore.NS_GENERAL,
            "fileAccess",
        ),
    )
    // Chat asked us to focus a file (F-012-style handoff): open its folder.
    val pendingFile = remember {
        val p = FileOpener.pendingPath
        if (p != null) FileOpener.pendingPath = null
        p
    }

    suspend fun refresh() {
        list = withContext(Dispatchers.IO) { app.workspaceManager.list() }
    }

    LaunchedEffect(Unit) {
        refresh()
        if (pendingFile != null) openWs = active
    }
    LaunchedEffect(chooserRequest) {
        if (chooserRequest > 0) openWs = null
    }

    // Opening a project never changes the agent's active project.
    val open = openWs
    BackHandler(enabled = open != null) { openWs = null }
    if (open != null) {
        WorkspaceExplorer(
            wsName = open,
            initialPath = pendingFile,
            onBack = { openWs = null },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Projects", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    Text("Choose the project the agent works in", fontSize = 12.sp, color = TextSecondary)
                }
                Text(
                    text = "${list.size} projects",
                    fontSize = 11.sp,
                    color = TextSecondary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(TileStone)
                        .padding(horizontal = 7.dp, vertical = 5.dp),
                )
            }
            active?.let {
                Text("Active: $it · ${if (fileAccess == FileAccess.WORKSPACE) "project-only files" else "all device files"}",
                    fontSize = 12.sp, color = AmberStatus)
            }
            OutlinedButton(onClick = { showProjectActions = !showProjectActions }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showProjectActions) "Hide project actions" else "Create or import a project")
            }
        }
        if (showProjectActions) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf("new" to "New", "folder" to "Phone folder", "git" to "Clone Git", "zip" to "Import ZIP").forEach { (id, label) ->
                FilterChip(selected = projectActionMode == id,
                    onClick = { projectActionMode = id }, label = { Text(label) })
            }
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
                placeholder = { Text("Project name", fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                colors = androidx.compose.material3.TextFieldDefaults.colors(
                    focusedContainerColor = TileStone,
                    unfocusedContainerColor = TileStone,
                    focusedIndicatorColor = BevelLight,
                    unfocusedIndicatorColor = BevelLight,
                ),
            )
            if (projectActionMode == "new") Button(
                enabled = name.isNotBlank() && !importing && !cloning,
                onClick = {
                val created = app.workspaceManager.create(name)
                message = if (created != null) {
                    app.workspaceManager.setActive(created)
                    app.workspaceChanged()
                    onProjectChanged(created)
                    openWs = created
                    "created: $created"
                } else {
                    "name unavailable or already exists"
                }
                name = ""
                if (created != null) showProjectActions = false
                scope.launch { refresh() }
                },
            ) { Text("+ Create") }
        }
        if (projectActionMode == "git") {
        TextField(
            value = cloneUrl,
            onValueChange = { cloneUrl = it },
            placeholder = { Text("https://github.com/owner/project.git", fontSize = 13.sp) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth(),
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = TileStone,
                unfocusedContainerColor = TileStone,
                focusedIndicatorColor = BevelLight,
                unfocusedIndicatorColor = BevelLight,
            ),
        )
        Button(
            onClick = {
                val projectName = name
                val url = cloneUrl
                cloning = true
                scope.launch {
                    try {
                        val result = withContext(Dispatchers.IO) {
                            GitService().clonePublic(app.workspaceManager, projectName, url)
                        }
                        list = withContext(Dispatchers.IO) { app.workspaceManager.list() }
                        app.workspaceManager.setActive(result.name)
                        app.workspaceChanged()
                        onProjectChanged(result.name)
                        openWs = result.name
                        message = "Cloned ${result.name}: ${result.files} files."
                        name = ""
                        cloneUrl = ""
                        showProjectActions = false
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = "Clone failed: ${e.message}"
                    } finally {
                        cloning = false
                    }
                }
            },
            enabled = name.isNotBlank() && cloneUrl.isNotBlank() && !importing && !cloning,
            modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(if (cloning) "Cloning repository…" else "Clone repository") }
        }
        if (projectActionMode == "zip") {
        OutlinedButton(
            onClick = { importZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            enabled = name.isNotBlank() && !importing && !cloning,
            modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(if (importing) "Importing project…" else "Import ZIP as new project") }
        }
        if (projectActionMode == "folder") {
            OutlinedButton(
                onClick = { pickFolder.launch(null) },
                enabled = !importing && !cloning,
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Choose an existing folder") }
        }
        Text(
            when (projectActionMode) {
                "git" -> "Use a public HTTP(S) Git URL. Git history is preserved."
                "zip" -> "Choose a ZIP file from your device. The project will be stored in KeepAgent."
                "folder" -> "Android will ask which folder KeepAgent may read and edit. Some protected folders cannot be chosen."
                else -> "Create an empty project and add files in the explorer."
            },
            fontSize = 12.sp, color = TextSecondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
        )
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
            if (list.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 36.dp, horizontal = 18.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("No projects yet", fontSize = 14.sp, color = TextPrimary)
                        Text(
                            "Create one above. The agent's file tools will be rooted there.",
                            fontSize = 11.sp,
                            color = TextSecondary,
                        )
                    }
                }
            }
            items(list, key = { it.name }) { ws ->
                WorkspaceRow(
                    info = ws,
                    active = ws.name == active,
                    onOpen = { openWs = ws.name },
                    onActivate = {
                        if (ws.name != active) {
                            app.workspaceManager.setActive(ws.name)
                            app.workspaceChanged()
                            onProjectChanged(ws.name)
                            scope.launch { refresh() }
                        }
                    },
                    onDelete = { deleteTarget = ws },
                    onRename = {
                        renameTarget = ws
                        renameName = ws.name
                    },
                )
            }
        }
    }
    deleteTarget?.let { ws ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete workspace?", fontSize = 14.sp) },
            text = {
                Text(
                    text = if (ws.linkedUri != null)
                        "Remove '${ws.name}' and its local working copy? The original phone folder stays untouched."
                    else "This permanently removes '${ws.name}' and its ${ws.fileCount} files. This cannot be undone.",
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                Button(onClick = {
                    deleteTarget = null
                    scope.launch {
                        val removed = withContext(Dispatchers.IO) {
                            val ok = app.workspaceManager.delete(ws.name)
                            if (ok && ws.linkedUri != null &&
                                app.workspaceManager.list().none { it.linkedUri == ws.linkedUri }) {
                                runCatching {
                                    context.contentResolver.releasePersistableUriPermission(
                                        android.net.Uri.parse(ws.linkedUri),
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                }
                            }
                            ok
                        }
                        message = if (removed) "removed: ${ws.name}" else "could not remove ${ws.name}"
                        refresh()
                    }
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }

    // Rename dialog (M1.4h) — re-points the file tools to the new root.
    renameTarget?.let { ws ->
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename workspace", fontSize = 14.sp) },
            text = {
                TextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    placeholder = { Text("new name", fontSize = 12.sp) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = TileStone,
                        unfocusedContainerColor = TileStone,
                        focusedIndicatorColor = BevelLight,
                        unfocusedIndicatorColor = BevelLight,
                    ),
                )
            },
            confirmButton = {
                Button(onClick = {
                    val new = app.workspaceManager.rename(ws.name, renameName)
                    message = if (new != null) "renamed to: $new" else "name unavailable"
                    renameTarget = null
                    if (new != null) {
                        if (app.workspaceManager.activeName() == new) app.workspaceChanged()
                        onProjectChanged(app.workspaceManager.activeName())
                        openWs = new
                    }
                    scope.launch { refresh() }
                }) {
                    Text("Rename")
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun WorkspaceRow(
    info: WorkspaceInfo,
    active: Boolean,
    onOpen: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
) {
    val shape = RoundedCornerShape(8.dp)
    var moreOpen by remember { mutableStateOf(false) }
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
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (info.linkedUri != null) "📁" else "▣", fontSize = 22.sp,
                modifier = Modifier.padding(end = 8.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(info.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (active) Text("  active", fontSize = 10.sp, color = AmberStatus)
                }
                Text((if (info.linkedUri != null) "Phone folder · " else "") +
                    "${info.fileCount} files · ${formatSize(info.sizeBytes)}",
                    fontSize = 10.sp, color = TextSecondary)
            }
            TextButton(onClick = onOpen) { Text("Open ›") }
        }
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth()) {
            if (!active) TextButton(onClick = onActivate) { Text("Set active") }
            androidx.compose.foundation.layout.Box {
                TextButton(onClick = { moreOpen = true }) { Text("More ···", color = TextSecondary) }
                androidx.compose.material3.DropdownMenu(
                    expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Rename") },
                        onClick = { moreOpen = false; onRename() })
                    if (!active) androidx.compose.material3.DropdownMenuItem(
                        text = { Text(if (info.linkedUri != null) "Remove link" else "Delete project") },
                        onClick = { moreOpen = false; onDelete() })
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.ROOT, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}

// -- file browser (M1.2, F-007 extension) ------------------------------------

/**
 * File browser for one workspace: folder tree, file viewing, and manual
 * editing. Paths are relative to the workspace root; [FileService] enforces
 * the access mode.
 */
@Composable
private fun WorkspaceExplorer(
    wsName: String,
    initialPath: String? = null,
    onBack: () -> Unit,
) {
    val app = Holder.app
    val fs = remember(wsName) {
        FileService(app.workspaceManager.rootFor(wsName) ?: app.workspaceManager.activeRoot(),
            app.eventBus, FileAccess.WORKSPACE)
    }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val git = remember { GitService() }

    var dir by remember { mutableStateOf("") }
    var openFile by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = openFile != null || dir.isNotEmpty()) {
        if (openFile != null) openFile = null
        else dir = dir.substringBeforeLast('/', "")
    }
    var entries by remember { mutableStateOf<List<FileService.DirEntry>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    // Search + sort + attach (M1.4h).
    var query by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf("name") } // name | size | date
    var sortMenuOpen by remember { mutableStateOf(false) }
    var gridView by rememberSaveable {
        mutableStateOf(app.settingsStore.getString(
            io.keepagent.core.settings.SettingsStore.NS_GENERAL, "projectGridView") == "true")
    }
    var newName by remember { mutableStateOf("") }
    var createOpen by remember { mutableStateOf(false) }

    // Git panel state.
    var gitOpen by remember { mutableStateOf(false) }
    var gitStatus by remember { mutableStateOf<GitService.StatusInfo?>(null) }
    var gitDiff by remember { mutableStateOf<String?>(null) }
    var gitDiffOpen by remember { mutableStateOf(false) }
    var gitLog by remember { mutableStateOf<List<GitService.CommitInfo>?>(null) }
    var gitLogOpen by remember { mutableStateOf(false) }
    var commitDialog by remember { mutableStateOf(false) }
    var commitMsg by remember { mutableStateOf("") }
    var gitBusy by remember { mutableStateOf(false) }

    fun refreshGit() {
        scope.launch(Dispatchers.IO) {
            val root = fs.root
            val st = if (git.isRepo(root)) git.status(root) else null
            val log = if (git.isRepo(root)) git.log(root, 15) else emptyList()
            withContext(Dispatchers.Main) {
                gitStatus = st
                gitLog = log
            }
        }
    }

    fun showDiff() {
        scope.launch(Dispatchers.IO) {
            val d = git.diffText(fs.root)
            withContext(Dispatchers.Main) {
                gitDiff = d
                gitDiffOpen = d != null
            }
        }
    }

    fun commitAll() {
        gitBusy = true
        scope.launch(Dispatchers.IO) {
            val h = git.commitAll(fs.root, commitMsg)
            withContext(Dispatchers.Main) {
                gitBusy = false
                commitDialog = false
                commitMsg = ""
                if (h != null) {
                    notice = "committed $h"
                    refreshGit()
                } else {
                    notice = "nothing to commit (or the workspace is not a repository)"
                }
            }
        }
    }

    /** Zips the whole workspace and hands it to the system share sheet. */
    fun shareZip() {
        notice = "zipping…"
        scope.launch(Dispatchers.IO) {
            val res = runCatching {
                val root = fs.root
                val zip = File(context.cacheDir, "keepagent-$wsName-${System.currentTimeMillis()}.zip")
                ZipOutputStream(zip.outputStream()).use { zos ->
                    root.walkTopDown().filter { it.isFile && !it.path.contains("/.git/") }.forEach { f ->
                        val relative = f.relativeTo(root).path.replace(File.separatorChar, '/')
                        if (relative == ".keepagent/handoff.json") return@forEach
                        zos.putNextEntry(ZipEntry(relative))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                    val handoff = HandoffExporter.create(
                        workspaceName = wsName,
                        root = root,
                        appVersion = context.packageManager
                            .getPackageInfo(context.packageName, 0).versionName ?: "unknown",
                        git = git.handoffState(root),
                        chat = app.chatController.handoffSnapshot(),
                    )
                    zos.putNextEntry(ZipEntry(".keepagent/handoff.json"))
                    zos.write(handoff.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                zip
            }
            withContext(Dispatchers.Main) {
                res.fold(
                    { zip ->
                        val uri = FileProvider.getUriForFile(
                            context,
                            "io.keepagent.app.fileprovider",
                            zip,
                        )
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(
                            Intent.createChooser(send, "share workspace as zip"),
                        )
                        notice = null
                    },
                    { e -> notice = "zip failed: ${e.message}" },
                )
            }
        }
    }

    fun loadDir() {
        val p = dir
        entries = null
        loadError = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { fs.browse(p) }
            if (r == null) {
                loadError = "cannot list this folder — blocked by file access"
            } else {
                entries = r
            }
        }
    }

    LaunchedEffect(dir) { loadDir() }
    LaunchedEffect(wsName) {
        if (app.workspaceManager.linkedUri(wsName) != null) {
            notice = "Syncing phone folder…"
            runCatching { withContext(Dispatchers.IO) { app.linkedProjectSync.sync(wsName) } }
                .onSuccess { notice = it; loadDir() }
                .onFailure { notice = "Folder sync failed: ${it.message}" }
        }
    }

    // Focus the file/folder the Chat tab handed over.
    LaunchedEffect(Unit) {
        val p = initialPath
        if (p != null) {
            if (File(fs.root, p).isFile) {
                val slash = p.lastIndexOf('/')
                if (slash >= 0) dir = p.substring(0, slash)
                openFile = p
            } else {
                val slash = p.lastIndexOf('/')
                dir = if (slash >= 0) p.substring(0, slash) else p
            }
        }
        refreshGit()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("Files in $wsName", fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            color = TextPrimary, modifier = Modifier.padding(start = 14.dp, top = 10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                text = "‹ Projects",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(TileStoneSelected)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 10.dp, vertical = 7.dp),
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val parts = dir.split("/").filter { it.isNotEmpty() }
                Text(
                    text = "⌂",
                    fontSize = 16.sp,
                    color = if (parts.isEmpty()) TextPrimary else TextSecondary,
                    modifier = Modifier.clickable { dir = ""; openFile = null }
                        .padding(horizontal = 5.dp),
                )
                if (parts.isNotEmpty()) {
                    parts.forEachIndexed { i, part ->
                        Text(
                            text = "/$part",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (i == parts.size - 1) TextPrimary else TextSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable {
                                    dir = parts.take(i + 1).joinToString("/")
                                    openFile = null
                                }
                                .padding(horizontal = 4.dp),
                        )
                    }
                }
            }
            Text(
                text = if (createOpen) "Cancel" else "+ New",
                fontSize = 11.sp,
                color = TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(TileStone)
                    .clickable { createOpen = !createOpen }
                    .padding(horizontal = 9.dp, vertical = 7.dp),
            )
        }

        notice?.let { n ->
            Text(
                text = n,
                fontSize = 10.sp,
                color = AmberStatus,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp),
            )
        }

        // Git panel — local repo only, no remotes.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(4.dp))
                .clickable { gitOpen = !gitOpen; if (gitOpen) refreshGit() }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (gitOpen) "▾" else "▸",
                fontSize = 10.sp,
                color = TextSecondary,
            )
            val chipSt = gitStatus
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = when {
                    chipSt == null && !gitOpen -> "Version history"
                    chipSt == null -> "Version history"
                    chipSt.clean -> "Version history · clean"
                    else -> "Version history · ${chipSt.count} changed"
                },
                fontSize = 12.sp,
                color = if (chipSt != null && !chipSt.clean) AmberStatus else TextSecondary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "Export ZIP",
                fontSize = 11.sp,
                color = TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { shareZip() }
                    .heightIn(min = 44.dp)
                    .padding(horizontal = 6.dp, vertical = 14.dp),
            )
        }

        if (gitOpen) {
            val st = gitStatus
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (st == null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "no git repository in this workspace.",
                            fontSize = 10.sp,
                            color = TextSecondary,
                        )
                        Button(onClick = {
                            scope.launch(Dispatchers.IO) {
                                git.init(fs.root)
                                withContext(Dispatchers.Main) { refreshGit() }
                            }
                        }) {
                            Text("initialize", fontSize = 10.sp)
                        }
                    }
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = if (st.clean) "clean — nothing changed" else "${st.count} changed",
                            fontSize = 10.sp,
                            color = TextSecondary,
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "refresh",
                            fontSize = 9.sp,
                            color = TextSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { refreshGit() }
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                        Text(
                            text = "diff",
                            fontSize = 9.sp,
                            color = TextSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { showDiff() }
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                        Text(
                            text = "log",
                            fontSize = 9.sp,
                            color = TextSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { gitLogOpen = !gitLogOpen }
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        )
                        if (!st.clean) {
                            Text(
                                text = "commit all",
                                fontSize = 9.sp,
                                color = TextPrimary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(TileStoneSelected)
                                    .clickable { commitDialog = true }
                                    .padding(horizontal = 5.dp, vertical = 2.dp),
                            )
                        }
                    }
                    val diffTextShown = gitDiff
                    if (gitDiffOpen && diffTextShown != null) {
                        SelectionContainer {
                            Text(
                                text = diffTextShown,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(TileStone)
                                    .padding(8.dp),
                            )
                        }
                    }
                    if (gitLogOpen) {
                        val log = gitLog
                        if (log == null) {
                            Text("no commits yet", fontSize = 10.sp, color = TextSecondary)
                        } else {
                            log.forEach { c ->
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = c.hash,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = LinkRead,
                                    )
                                    Text(
                                        text = c.whenText,
                                        fontSize = 9.sp,
                                        color = TextSecondary,
                                    )
                                    Text(
                                        text = c.message,
                                        fontSize = 9.sp,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Commit dialog with a message line.
        if (commitDialog) {
            AlertDialog(
                onDismissRequest = { commitDialog = false },
                title = { Text("commit all changes", fontSize = 13.sp) },
                text = {
                    TextField(
                        value = commitMsg,
                        onValueChange = { commitMsg = it },
                        placeholder = { Text("commit message", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = TileStone,
                            unfocusedContainerColor = TileStone,
                            focusedIndicatorColor = BevelLight,
                            unfocusedIndicatorColor = BevelLight,
                        ),
                    )
                },
                confirmButton = {
                    Button(onClick = { commitAll() }, enabled = !gitBusy) {
                        Text(if (gitBusy) "committing…" else "commit", fontSize = 11.sp)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { commitDialog = false }) { Text("cancel") }
                },
            )
        }

        val file = openFile
        if (file != null) {
            Recents.add(file)
            FileEditor(
                fs = fs,
                relPath = file,
                onBack = { openFile = null },
                onSaved = {
                    loadDir()
                    if (app.workspaceManager.linkedUri(wsName) != null) scope.launch(Dispatchers.IO) {
                        runCatching { app.linkedProjectSync.sync(wsName) }
                            .onFailure { withContext(Dispatchers.Main) { notice = "Folder sync failed: ${it.message}" } }
                    }
                },
            )
        } else {
            // Search + sort row (M1.4h).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search this folder", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = TileStone,
                        unfocusedContainerColor = TileStone,
                        focusedIndicatorColor = BevelLight,
                        unfocusedIndicatorColor = BevelLight,
                    ),
                )
                androidx.compose.foundation.layout.Box {
                    TextButton(onClick = { sortMenuOpen = true }) { Text("Sort: $sortBy") }
                    androidx.compose.material3.DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        listOf("name", "size", "date").forEach { option ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(option.replaceFirstChar { it.uppercase() }) },
                                onClick = { sortBy = option; sortMenuOpen = false },
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("View", color = TextSecondary, fontSize = 12.sp)
                FilterChip(selected = !gridView, onClick = {
                    gridView = false
                    app.settingsStore.setString(io.keepagent.core.settings.SettingsStore.NS_GENERAL,
                        "projectGridView", "false")
                }, label = { Text("☰  Folder list") })
                FilterChip(selected = gridView, onClick = {
                    gridView = true
                    app.settingsStore.setString(io.keepagent.core.settings.SettingsStore.NS_GENERAL,
                        "projectGridView", "true")
                }, label = { Text("▦  File grid") })
            }

            // Create file/folder row (M1.4h fix — empty workspaces had no way in).
            fun createEntry(isDir: Boolean) {
                val n = newName.trim()
                if (n.isEmpty()) {
                    notice = "give the " + (if (isDir) "folder" else "file") + " a name first"
                    return
                }
                if (n.contains("/") || n == "." || n == "..") {
                    notice = "one level only: no slashes in the name"
                    return
                }
                val rel = if (dir.isEmpty()) n else "$dir/$n"
                scope.launch(Dispatchers.IO) {
                    val r = if (isDir) fs.createDir(rel) else fs.createFile(rel, "")
                    if (r.ok && app.workspaceManager.linkedUri(wsName) != null) {
                        runCatching { app.linkedProjectSync.sync(wsName) }
                            .onFailure { withContext(Dispatchers.Main) {
                                notice = "Created locally, but folder sync failed: ${it.message}"
                            } }
                    }
                    withContext(Dispatchers.Main) {
                        notice = if (r.ok) r.text else r.error
                        if (r.ok) {
                            newName = ""
                            createOpen = false
                        }
                        loadDir()
                    }
                }
            }
            if (createOpen) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "new:",
                    fontSize = 10.sp,
                    color = TextSecondary,
                )
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("File or folder name", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = TileStone,
                        unfocusedContainerColor = TileStone,
                        focusedIndicatorColor = BevelLight,
                        unfocusedIndicatorColor = BevelLight,
                    ),
                )
                TextButton(onClick = { createEntry(isDir = false) }) { Text("File") }
                TextButton(onClick = { createEntry(isDir = true) }) { Text("Folder") }
            }

            // Recently opened files (M1.4h).
            if (dir.isEmpty()) {
                val rec = Recents.files
                if (rec.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "recent:",
                            fontSize = 10.sp,
                            color = TextSecondary,
                        )
                        rec.forEach { p ->
                            val leaf = p.substringAfterLast('/')
                            Text(
                                text = leaf,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = LinkRead,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(TileStone)
                                    .clickable {
                                        val slash = p.lastIndexOf('/')
                                        if (slash >= 0) dir = p.substring(0, slash)
                                        openFile = p
                                    }
                                    .padding(horizontal = 7.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            val shown = remember(entries, query, sortBy) {
                val list = entries ?: return@remember null
                val q = query.trim().lowercase()
                val filtered = if (q.isEmpty()) list
                else list.filter { it.name.lowercase().contains(q) }
                when (sortBy) {
                    "size" -> filtered.sortedWith(
                        compareByDescending<FileService.DirEntry> { it.isDirectory }.thenByDescending { it.sizeBytes },
                    )
                    "date" -> filtered.sortedWith(
                        compareByDescending<FileService.DirEntry> { it.isDirectory }.thenByDescending { it.lastModifiedMillis },
                    )
                    else -> filtered
                }
            }
            fun openEntry(entry: FileService.DirEntry) {
                if (entry.isDirectory) {
                    dir = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
                    openFile = null
                } else {
                    openFile = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
                }
            }
            if (gridView && !shown.isNullOrEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 104.dp),
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    gridItems(shown!!, key = { it.name }) { entry ->
                        ExplorerTile(entry, onOpen = { openEntry(entry) })
                    }
                }
            } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val list = shown
                when {
                    list == null -> item(key = "loading") {
                        val err = loadError
                        Text(
                            text = err ?: "loading…",
                            fontSize = 12.sp,
                            color = if (err != null) AmberStatus else TextSecondary,
                        )
                    }
                    list.isEmpty() -> item(key = "empty") {
                        Text(
                            text = if (query.isNotEmpty()) "No matching files in this folder" else "This folder is empty. Tap + New to add a file or folder.",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    else -> items(list, key = { it.name }) { entry ->
                        ExplorerRow(
                            entry = entry,
                            path = dir,
                            onOpen = { openEntry(entry) },
                            onAttach = {
                                val rel = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
                                app.chatController.attachFile(
                                    io.keepagent.app.ChatController.AttachedFile(rel, false, ""),
                                )
                                notice = "attached $rel to the next message (chat tab)"
                            },
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun ExplorerRow(
    entry: FileService.DirEntry,
    path: String,
    onOpen: () -> Unit,
    onAttach: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(TileStone)
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(if (entry.isDirectory) "📁" else "📄", fontSize = 25.sp)
        Column(Modifier.weight(1f)) {
            Text(entry.name, fontSize = 14.sp, color = TextPrimary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(
                text = (if (path.isEmpty()) "Project root" else path) +
                    " · " + (if (entry.isDirectory) "Folder" else formatSize(entry.sizeBytes)),
                fontSize = 10.sp, color = TextSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!entry.isDirectory) {
            Text(
                text = formatSize(entry.sizeBytes),
                fontSize = 10.sp,
                color = TextSecondary,
            )
            TextButton(onClick = onAttach) { Text("Attach") }
        } else {
            Text("›", fontSize = 20.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun ExplorerTile(entry: FileService.DirEntry, onOpen: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().heightIn(min = 112.dp)
            .clip(RoundedCornerShape(12.dp)).background(TileStone)
            .clickable(onClick = onOpen).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(if (entry.isDirectory) "📁" else "📄", fontSize = 32.sp)
        Text(entry.name, fontSize = 12.sp, color = TextPrimary, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
        Text(if (entry.isDirectory) "Folder" else formatSize(entry.sizeBytes),
            fontSize = 10.sp, color = TextSecondary)
    }
}

/** Text viewer/editor for one workspace file. */
@Composable
private fun FileEditor(fs: FileService, relPath: String, onBack: () -> Unit, onSaved: () -> Unit) {
    val app = Holder.app
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    var text by remember { mutableStateOf("") }
    var savedText by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    // Editor upgrades (M1.4h): view mode with line numbers, wrap, find/replace,
    // revert. Undo in edit mode comes from the on-screen keyboard.
    var mode by remember { mutableStateOf("edit") } // edit | view
    var wrap by remember { mutableStateOf(false) }
    var findOpen by remember { mutableStateOf(false) }
    var find by remember { mutableStateOf("") }
    var replaceText by remember { mutableStateOf("") }

    /** Hands the file to the system share sheet via FileProvider. */
    fun shareFile() {
        scope.launch(Dispatchers.IO) {
            val f = runCatching { fs.resolve(relPath) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (f == null || !f.exists()) {
                    status = "share failed: no such file"
                    return@withContext
                }
                val uri = FileProvider.getUriForFile(
                    context,
                    "io.keepagent.app.fileprovider",
                    f,
                )
                val mime = when {
                    relPath.endsWith(".html") || relPath.endsWith(".htm") -> "text/html"
                    relPath.endsWith(".png") -> "image/png"
                    relPath.endsWith(".txt") || relPath.endsWith(".md") ||
                        relPath.endsWith(".log") || relPath.endsWith(".json") -> "text/plain"
                    else -> "*/*"
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "share file"))
                status = null
            }
        }
    }

    LaunchedEffect(relPath) {
        val r = withContext(Dispatchers.IO) { fs.read(relPath, 1_000_000) }
        if (r.ok) {
            text = r.text
            savedText = r.text
            error = null
            status = null
        } else {
            error = r.error
            text = ""
            savedText = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = "← files",
                fontSize = 12.sp,
                color = LinkRead,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            SelectionContainer {
                Text(
                    text = relPath,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = TextPrimary,
                    maxLines = 1,
                )
            }
            status?.let {
                Text(text = it, fontSize = 10.sp, color = AmberStatus)
            }
        }
        val err = error
        if (err != null) {
            Text(
                text = err,
                fontSize = 12.sp,
                color = AmberStatus,
                modifier = Modifier.padding(14.dp),
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            ) {
                // Mode / wrap / find / revert toolbar (M1.4h).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val matchCount =
                        if (find.isEmpty()) 0 else text.split(find, ignoreCase = true).size - 1
                    listOf("edit", "view", "wrap", "find", "revert").forEach { chip ->
                        val active =
                            when (chip) {
                                "edit" -> mode == "edit"
                                "view" -> mode == "view"
                                "wrap" -> wrap
                                "find" -> findOpen
                                else -> false
                            }
                        Text(
                            text = when {
                                chip == "find" && findOpen && matchCount > 0 -> "$chip · $matchCount"
                                else -> chip
                            },
                            fontSize = 10.sp,
                            color = if (active) TextPrimary else TextSecondary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (active) TileStoneSelected else Color.Transparent)
                                .clickable {
                                    when (chip) {
                                        "edit" -> mode = "edit"
                                        "view" -> mode = "view"
                                        "wrap" -> wrap = !wrap
                                        "find" -> findOpen = !findOpen
                                        "revert" -> {
                                            val st = savedText
                                            if (st != null) {
                                                text = st
                                                status = "reverted to last saved version"
                                            }
                                        }
                                    }
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                        )
                    }
                }
                if (findOpen) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        TextField(
                            value = find,
                            onValueChange = { find = it },
                            placeholder = { Text("find", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = TileStone,
                                unfocusedContainerColor = TileStone,
                                focusedIndicatorColor = BevelLight,
                                unfocusedIndicatorColor = BevelLight,
                            ),
                        )
                        TextField(
                            value = replaceText,
                            onValueChange = { replaceText = it },
                            placeholder = { Text("replace with", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = TileStone,
                                unfocusedContainerColor = TileStone,
                                focusedIndicatorColor = BevelLight,
                                unfocusedIndicatorColor = BevelLight,
                            ),
                        )
                        Text(
                            text = "replace all",
                            fontSize = 10.sp,
                            color = TextPrimary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(TileStoneSelected)
                                .clickable {
                                    val f = find
                                    if (f.isNotEmpty()) {
                                        val n = text.split(f, ignoreCase = true).size - 1
                                        text = text.replace(f, replaceText, ignoreCase = true)
                                        status = "replaced $n occurrence(s)"
                                    }
                                }
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                        )
                    }
                }
                if (mode == "view") {
                    // Numbered, read-only view with find highlighting (M1.4h).
                    val lines = remember(text) { text.split("\n") }
                    val f = find
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(TileStone)
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        lines.forEachIndexed { i, line ->
                            val hit = f.isNotEmpty() && line.contains(f, ignoreCase = true)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (hit) AmberStatus.copy(alpha = 0.18f)
                                        else Color.Transparent,
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            ) {
                                Text(
                                    text = "${i + 1}",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = if (hit) AmberStatus else TextSecondary,
                                    modifier = Modifier.width(34.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = line,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = TextPrimary,
                                    softWrap = wrap,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                } else {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextPrimary,
                            lineHeight = 14.sp,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(TileStone)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val dirty = savedText != null && text != savedText
                    if (dirty) {
                        Text(
                            text = "unsaved changes",
                            fontSize = 10.sp,
                            color = AmberStatus,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                    OutlinedButton(onClick = { shareFile() }) {
                        Text("share", fontSize = 11.sp)
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                val r = withContext(Dispatchers.IO) { fs.write(relPath, text) }
                                if (r.ok) {
                                    savedText = text
                                    status = "saved — ${r.text}"
                                    onSaved()
                                } else {
                                    status = "save failed: ${r.error}"
                                }
                            }
                        },
                        enabled = dirty,
                    ) {
                        Text("Save")
                    }
                }
            }
        }
    }
}
