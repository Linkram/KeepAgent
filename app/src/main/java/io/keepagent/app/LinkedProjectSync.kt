package io.keepagent.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import io.keepagent.core.workspace.WorkspaceManager
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Android's Storage Access Framework exposes document URIs, while the agent
 * runtimes require ordinary Files. This reconciles a private working copy
 * with the folder the user selected. A persisted hash baseline lets either
 * side change between syncs without silently overwriting concurrent edits.
 */
class LinkedProjectSync(
    private val context: Context,
    private val workspaces: WorkspaceManager,
) {
    private data class Document(val uri: Uri, val name: String, val mime: String)
    private data class Tree(
        val root: Uri,
        val files: MutableMap<String, Document> = mutableMapOf(),
        val dirs: MutableMap<String, Document> = mutableMapOf(),
    )
    private val resolver get() = context.contentResolver
    private val directoryMime = DocumentsContract.Document.MIME_TYPE_DIR

    @Synchronized
    fun sync(name: String): String {
        val uriText = workspaces.linkedUri(name) ?: return "Local project"
        val localRoot = workspaces.rootFor(name) ?: error("Project no longer exists")
        val treeUri = Uri.parse(uriText)
        val tree = scan(treeUri)
        val stateFile = File(context.filesDir, "linked-project-state/${uriText.sha256()}.json")
        val baseline = readState(stateFile)
        val canonicalRoot = localRoot.canonicalFile
        val local = localRoot.walkTopDown()
            .onEnter { it == localRoot || it.canonicalFile.toPath().startsWith(canonicalRoot.toPath()) }
            .filter { it.isFile && !isInternalArtifact(it.name) &&
                it.canonicalFile.toPath().startsWith(canonicalRoot.toPath()) }
            .associate { it.relativeTo(localRoot).invariantSeparatorsPath to it }
        val paths = (tree.files.keys + local.keys + baseline.keys).toSortedSet()
        val next = mutableMapOf<String, String>()
        var pulled = 0
        var pushed = 0
        var conflicts = 0

        for (path in paths) {
            val remoteDoc = tree.files[path]
            val localFile = local[path]
            val remoteHash = remoteDoc?.let { hashDocument(it.uri) }
            val localHash = localFile?.sha256()
            val base = baseline[path]
            when {
                remoteHash == localHash -> if (remoteHash != null) next[path] = remoteHash
                base == null && remoteHash != null && localHash == null -> {
                    pull(remoteDoc!!.uri, File(localRoot, path)); next[path] = remoteHash; pulled++
                }
                base == null && localHash != null && remoteHash == null -> {
                    push(tree, path, localFile!!); next[path] = localHash; pushed++
                }
                base == remoteHash && localHash != base -> {
                    if (localHash == null) {
                        check(DocumentsContract.deleteDocument(resolver, remoteDoc!!.uri)) {
                            "Cannot remove $path from selected folder"
                        }
                    } else {
                        push(tree, path, localFile!!); next[path] = localHash
                    }
                    pushed++
                }
                base == localHash && remoteHash != base -> {
                    if (remoteHash == null) localFile!!.delete()
                    else { pull(remoteDoc!!.uri, File(localRoot, path)); next[path] = remoteHash }
                    pulled++
                }
                remoteHash == null && localHash != null -> {
                    push(tree, path, localFile!!); next[path] = localHash; pushed++
                }
                localHash == null && remoteHash != null -> {
                    pull(remoteDoc!!.uri, File(localRoot, path)); next[path] = remoteHash; pulled++
                }
                remoteHash != null && localHash != null -> {
                    // Both copies changed. Keep the agent's version as a
                    // separate local file; the external file stays untouched.
                    val conflict = uniqueConflict(File(localRoot, path))
                    localFile!!.copyTo(conflict)
                    pull(remoteDoc!!.uri, localFile)
                    next[path] = remoteHash
                    conflicts++
                }
            }
        }
        // Include empty directories, too. Never remove an external directory
        // just because it disappeared from the working copy.
        tree.dirs.keys.forEach { File(localRoot, it).mkdirs() }
        localRoot.walkTopDown()
            .onEnter { it == localRoot || it.canonicalFile.toPath().startsWith(canonicalRoot.toPath()) }
            .filter { it.isDirectory && it != localRoot }
            .map { it.relativeTo(localRoot).invariantSeparatorsPath }
            .sortedBy { it.length }.forEach { ensureDirectory(tree, it) }
        writeState(stateFile, next)
        return "Synced $name: $pulled from phone, $pushed to phone" +
            (if (conflicts > 0) ", $conflicts conflict ${if (conflicts == 1) "copy" else "copies"} saved locally" else "")
    }

    private fun scan(treeUri: Uri): Tree {
        val root = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val result = Tree(root)
        fun walk(parent: Uri, path: String, depth: Int) {
            if (depth > 96) error("Folder nesting is too deep")
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, DocumentsContract.getDocumentId(parent))
            resolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ), null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val leaf = cursor.getString(nameCol) ?: continue
                    if (leaf.isBlank() || leaf == "." || leaf == ".." || '/' in leaf || '\\' in leaf) continue
                    val rel = if (path.isEmpty()) leaf else "$path/$leaf"
                    val mime = cursor.getString(mimeCol) ?: "application/octet-stream"
                    val doc = Document(DocumentsContract.buildDocumentUriUsingTree(
                        treeUri, cursor.getString(idCol)), leaf, mime)
                    if (mime == directoryMime) {
                        result.dirs[rel] = doc
                        walk(doc.uri, rel, depth + 1)
                    } else if (!isInternalArtifact(leaf)) result.files[rel] = doc
                }
            } ?: error("Cannot read the selected folder. Reconnect it from Projects.")
        }
        walk(root, "", 0)
        return result
    }

    private fun ensureDirectory(tree: Tree, path: String): Uri {
        if (path.isEmpty()) return tree.root
        tree.dirs[path]?.let { return it.uri }
        val parent = path.substringBeforeLast('/', "")
        val uri = DocumentsContract.createDocument(resolver, ensureDirectory(tree, parent),
            directoryMime, path.substringAfterLast('/')) ?: error("Cannot create folder $path")
        tree.dirs[path] = Document(uri, path.substringAfterLast('/'), directoryMime)
        return uri
    }

    private fun push(tree: Tree, path: String, source: File) {
        val parent = ensureDirectory(tree, path.substringBeforeLast('/', ""))
        val leaf = path.substringAfterLast('/')
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            leaf.substringAfterLast('.', "").lowercase(Locale.ROOT)) ?: "application/octet-stream"
        val target = tree.files[path]?.uri ?: DocumentsContract.createDocument(resolver, parent, mime, leaf)
            ?: error("Cannot create $path in selected folder")
        resolver.openOutputStream(target, "wt")?.use { out -> source.inputStream().use { it.copyTo(out) } }
            ?: error("Cannot write $path in selected folder")
        tree.files[path] = Document(target, leaf, mime)
    }

    private fun pull(uri: Uri, target: File) {
        target.parentFile?.mkdirs()
        val staging = File(target.parentFile, ".${target.name}.keepagent-download")
        try {
            resolver.openInputStream(uri)?.use { input ->
                staging.outputStream().use { input.copyTo(it) }
            } ?: error("Cannot read $uri")
            val backup = File(target.parentFile, ".${target.name}.keepagent-backup-${System.nanoTime()}")
            if (target.exists()) {
                if (!target.renameTo(backup)) error("Cannot replace ${target.name}")
            }
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target)
                error("Cannot finish ${target.name}")
            }
            backup.delete()
        } finally { staging.delete() }
    }

    private fun hashDocument(uri: Uri): String =
        resolver.openInputStream(uri)?.use { stream ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65536)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
            digest.digest().hex()
        } ?: error("Cannot read $uri")

    private fun File.sha256(): String = inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().hex()
    }
    private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
        .digest(toByteArray()).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun uniqueConflict(file: File): File {
        var index = 1
        while (true) {
            val target = File(file.parentFile, "${file.name}.keepagent-conflict-$index")
            if (!target.exists()) return target
            index++
        }
    }
    private fun isInternalArtifact(name: String): Boolean =
        ".keepagent-conflict-" in name || ".keepagent-backup-" in name ||
            name.endsWith(".keepagent-download")
    private fun readState(file: File): Map<String, String> = runCatching {
        val json = JSONObject(file.readText())
        json.keys().asSequence().associateWith { json.getString(it) }
    }.getOrDefault(emptyMap())
    private fun writeState(file: File, state: Map<String, String>) {
        file.parentFile?.mkdirs()
        val json = JSONObject()
        state.forEach { (path, hash) -> json.put(path, hash) }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.toString())
        if (file.exists()) file.delete()
        check(temp.renameTo(file)) { "Cannot save sync state" }
    }
}
