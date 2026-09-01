package io.keepagent.app.git

import io.keepagent.app.ui.common.ADD
import io.keepagent.app.ui.common.CTX
import io.keepagent.app.ui.common.DEL
import io.keepagent.app.ui.common.diffLines
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only git for the active workspace (M1.4): init, status, diff,
 * commit-all, and log. No remotes, no network — the identity is fixed to
 * KeepAgent / keepagent@local and lives only in per-commit metadata.
 */
class GitService {

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
}
