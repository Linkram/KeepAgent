package io.keepagent.app.ui.tabs

import android.content.Intent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.core.content.FileProvider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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
    var openWs by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var renameTarget by remember { mutableStateOf<WorkspaceInfo?>(null) }
    var renameName by remember { mutableStateOf("") }

    val scope = rememberCoroutineScope()
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

    // Tapping a workspace makes it active (file tools re-point to its root)
    // and opens the file browser for it.
    val open = openWs
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
            Text("Workspaces", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Text(
                text = "File tools run against the active workspace. Tap a workspace to browse, view, and edit its files. " +
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
                        openWs = ws.name
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
                    text = "This permanently removes '${ws.name}' and its ${ws.fileCount} files. This cannot be undone.",
                    fontSize = 12.sp,
                )
            },
            confirmButton = {
                Button(onClick = {
                    app.workspaceManager.delete(ws.name)
                    message = "deleted: ${ws.name}"
                    deleteTarget = null
                    scope.launch { refresh() }
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
                        app.fileService.setRoot(app.workspaceManager.activeRoot())
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
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
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
        Row(
            modifier = Modifier.padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "rename",
                fontSize = 11.sp,
                color = TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onRename)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            if (!active) {
                Text(
                    text = "delete",
                    fontSize = 11.sp,
                    color = AmberStatus,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(onClick = onDelete)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
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
    val fs = app.fileService
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val git = remember { GitService() }

    var dir by remember { mutableStateOf("") }
    var openFile by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf<List<FileService.DirEntry>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    // Search + sort + attach (M1.4h).
    var query by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf("name") } // name | size | date

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
                        zos.putNextEntry(ZipEntry(f.relativeTo(root).path.replace(File.separatorChar, '/')))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = "← workspaces",
                fontSize = 12.sp,
                color = LinkRead,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = wsName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                )
                val parts = dir.split("/").filter { it.isNotEmpty() }
                if (parts.isEmpty()) {
                    Text(
                        text = "  (root)",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary,
                    )
                } else {
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
                    chipSt == null && !gitOpen -> "git · not initialized"
                    chipSt == null -> "git"
                    chipSt.clean -> "git · clean"
                    else -> "git · ${chipSt.count} changed"
                },
                fontSize = 10.sp,
                color = if (chipSt != null && !chipSt.clean) AmberStatus else TextSecondary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "share zip",
                fontSize = 9.sp,
                color = TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { shareZip() }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
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
                relPath = file,
                onBack = { openFile = null },
                onSaved = { loadDir() },
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
                    placeholder = { Text("search files here", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = TileStone,
                        unfocusedContainerColor = TileStone,
                        focusedIndicatorColor = BevelLight,
                        unfocusedIndicatorColor = BevelLight,
                    ),
                )
                listOf("name", "size", "date").forEach { s ->
                    Text(
                        text = s,
                        fontSize = 10.sp,
                        color = if (sortBy == s) TextPrimary else TextSecondary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (sortBy == s) TileStoneSelected else Color.Transparent)
                            .clickable { sortBy = s }
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                    )
                }
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
                else list.filter { it.name.lowercase().contains(q) || it.isDirectory }
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
                            text = if (query.isNotEmpty()) "(no matches)" else "(empty folder)",
                            fontSize = 12.sp,
                            color = TextSecondary,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    else -> items(list, key = { it.name }) { entry ->
                        ExplorerRow(
                            entry = entry,
                            onOpen = {
                                if (entry.isDirectory) {
                                    dir = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
                                    openFile = null
                                } else {
                                    openFile = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
                                }
                            },
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

@Composable
private fun ExplorerRow(
    entry: FileService.DirEntry,
    onOpen: () -> Unit,
    onAttach: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(TileStone)
            .clickable(onClick = onOpen)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (entry.isDirectory) entry.name + "/" else entry.name,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = if (entry.isDirectory) TextPrimary else TextSecondary,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        if (!entry.isDirectory) {
            Text(
                text = formatSize(entry.sizeBytes),
                fontSize = 10.sp,
                color = TextSecondary,
            )
            Text(
                text = "attach",
                fontSize = 10.sp,
                color = LinkRead,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onAttach)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Text viewer/editor for one workspace file. */
@Composable
private fun FileEditor(relPath: String, onBack: () -> Unit, onSaved: () -> Unit) {
    val app = Holder.app
    val fs = app.fileService
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
