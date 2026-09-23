package io.keepagent.app.git

import io.keepagent.app.ui.common.ADD
import io.keepagent.app.ui.common.CTX
import io.keepagent.app.ui.common.DEL
import io.keepagent.app.ui.common.diffLines
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import io.keepagent.core.workspace.WorkspaceManager
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Git for mobile workspaces: public HTTP(S) clone plus local init, status,
 * diff, commit-all, and log. Push/pull credentials are intentionally not yet
 * accepted. Local commits use the fixed KeepAgent / keepagent@local identity.
 */
class GitService {

    data class CloneInfo(val name: String, val files: Int)
    data class DirtyFile(val path: String, val sha256: String?)
    data class HandoffState(
        val branch: String?,
        val commit: String?,
        val remote: String?,
        val dirtyFiles: List<DirtyFile>,
    )

    data class StatusInfo(
        val staged: Collection<String>,
        val changed: Collection<String>,
        val deleted: Collection<String>,
        val untracked: Collection<String>,
    ) {
        val count: Int get() = staged.size + changed.size + deleted.size + untracked.size
        val clean: Boolean get() = count == 0
    }

    data class CommitInfo(val hash: String, val message: String, val whenText: String)

    fun isRepo(root: File): Boolean = File(root, ".git").exists()

    fun init(root: File) {
        if (isRepo(root)) return
        Git.init().setDirectory(root).setInitialBranch("main").call().use { it.close() }
    }

    /**
     * Clones a public HTTP(S) repository into a new workspace. Embedded URL
     * credentials and non-network schemes are rejected so secrets cannot leak
     * into Git config or event history. A failed clone removes its partial
     * destination, while an existing project is never overwritten.
     */
    fun clonePublic(
        workspaces: WorkspaceManager,
        requestedName: String,
        repositoryUrl: String,
    ): CloneInfo {
        val safeUrl = validatePublicUrl(repositoryUrl)
        try {
            val imported = workspaces.importGenerated(requestedName) { destination ->
                Git.cloneRepository()
                    .setURI(safeUrl)
                    .setDirectory(destination)
                    .setTimeout(300)
                    .call()
                    .use { it.close() }
            }
            return CloneInfo(imported.name, imported.fileCount)
        } catch (e: Exception) {
            throw IllegalStateException(
                e.message?.takeIf { it.isNotBlank() } ?: "Git clone failed.",
                e,
            )
        }
    }

    fun status(root: File): StatusInfo? = runCatching {
        Git.open(root).use { git ->
            val st = git.status().call()
            StatusInfo(st.added, st.changed, st.missing, st.untracked)
        }
    }.getOrNull()

    /**
     * Human-readable diff: one status line per changed path, plus line-level
     * +/- output for up to 4 changed text files (binary/oversized skipped).
     */
    fun diffText(root: File): String? = runCatching {
        Git.open(root).use { git ->
            val repo = git.repository
            val st = git.status().call()
            val sb = StringBuilder()
            val head = buildList {
                st.added.forEach { add("A  $it") }
                st.changed.forEach { add("M  $it") }
                st.missing.forEach { add("D  $it") }
                st.untracked.forEach { add("?  $it") }
            }
            sb.append(head.joinToString("\n"))
            var files = 0
            for (path in (st.changed.toList() + st.added.toList())) {
                if (files >= 4 || sb.length > 6000) break
                if (path.endsWith(".png") || path.endsWith(".zip") ||
                    path.endsWith(".jar") || path.endsWith(".apk")
                ) continue
                val disk = File(root, path)
                if (!disk.isFile || disk.length() > 200_000) continue
                val oldText = indexText(repo, path) ?: ""
                val newText = runCatching { disk.readText() }.getOrNull() ?: continue
                val d = diffLines(oldText, newText, 1500) ?: continue
                if (d.none { it.kind != CTX }) continue
                files++
                sb.append("\n\n--- $path")
                d.take(200).forEach {
                    sb.append('\n').append(
                        when (it.kind) {
                            ADD -> "+${it.text}"
                            DEL -> "-${it.text}"
                            else -> " ${it.text}"
                        },
                    )
                }
            }
            sb.toString()
        }
    }.getOrNull()

    /** `git add -A` + commit with the fixed local identity. Returns the short hash. */
    fun commitAll(root: File, message: String): String? = runCatching {
        Git.open(root).use { git ->
            git.add().addFilepattern(".").call()
            val c = git.commit()
                .setMessage(message.ifBlank { "commit all changes" })
                .setAuthor("KeepAgent", "keepagent@local")
                .setCommitter("KeepAgent", "keepagent@local")
                .call()
            c.name.substring(0, 8)
        }
    }.getOrNull()

    fun log(root: File, n: Int = 15): List<CommitInfo> = runCatching {
        val fmt = SimpleDateFormat("MMM d HH:mm", Locale.US)
        Git.open(root).use { git ->
            val out = mutableListOf<CommitInfo>()
            for (c in git.log().call()) {
                if (out.size >= n) break
                out.add(
                    CommitInfo(
                        c.name.substring(0, 8),
                        c.shortMessage,
                        fmt.format(Date(c.commitTime * 1000L)),
                    ),
                )
            }
            out
        }
    }.getOrDefault(emptyList())

    fun handoffState(root: File): HandoffState? = runCatching {
        Git.open(root).use { git ->
            val repo = git.repository
            val status = git.status().call()
            val paths = (
                status.added + status.changed + status.modified + status.missing +
                    status.removed + status.untracked + status.untrackedFolders
                ).distinct().sorted().take(500)
            val dirty = paths.map { path ->
                val file = File(root, path)
                DirtyFile(path, file.takeIf { it.isFile }?.let(::sha256))
            }
            HandoffState(
                branch = repo.branch,
                commit = repo.resolve("HEAD")?.name,
                remote = safeRemote(repo.config.getString("remote", "origin", "url")),
                dirtyFiles = dirty,
            )
        }
    }.getOrNull()

    private fun indexText(repo: Repository, path: String): String? {
        return try {
            val idx = repo.readDirCache()
            val entry = idx.getEntry(path) ?: return null
            val id = entry.objectId
            repo.newObjectReader().use { reader -> String(reader.open(id).getBytes()) }
        } catch (e: Exception) {
            null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun safeRemote(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (uri.userInfo != null) return null
        return value
    }

    companion object {
        internal fun validatePublicUrl(value: String): String {
            val text = value.trim()
            val uri = requireNotNull(runCatching { URI(text) }.getOrNull()) {
                "Enter a valid repository URL."
            }
            require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) {
                "Only HTTP(S) repository URLs are supported."
            }
            require(!uri.host.isNullOrBlank()) { "The repository URL needs a host." }
            require(uri.userInfo == null) {
                "Credentials in repository URLs are not allowed. Clone public projects without embedded secrets."
            }
            return uri.toASCIIString()
        }
    }
}
