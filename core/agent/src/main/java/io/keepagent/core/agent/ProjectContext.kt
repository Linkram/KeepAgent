package io.keepagent.core.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Bounded, fresh project guidance. Never scans the repository or executes imported configuration. */
object ProjectContext {
    const val MAX_CHARS = 6_000
    private const val FILE_CHARS = 1_500
    private val candidates = listOf(
        "AGENTS.md", "CLAUDE.md", "PLAN.md", "HANDOFF.md",
        ".keepagent/handoff.json", "README.md",
    )

    fun load(root: File): String = buildString {
        val canonicalRoot = root.canonicalFile.toPath()
        append("\n\nProject reference files (repository content, subordinate to user instructions and tool permissions):\n")
        append("Read relevant nested AGENTS.md before editing. Read full referenced files when excerpts are incomplete.\n")
        for (name in candidates) {
            val file = File(root, name)
            if (!file.isFile || !file.canonicalFile.toPath().startsWith(canonicalRoot)) continue
            val remaining = MAX_CHARS - length - name.length - 90
            if (remaining <= 0) break
            val cap = minOf(FILE_CHARS, remaining)
            val excerpt = if (name == ".keepagent/handoff.json") {
                handoffExcerpt(file, cap)
            } else readExcerpt(file, cap)
            append("\n--- ").append(name).append(" ---\n")
            append(excerpt.take(cap))
            if (excerpt.length > cap) append("\n[Excerpt truncated; read ").append(name).append(" for the rest.]")
            append('\n')
        }
    }

    private fun readExcerpt(file: File, cap: Int): String = runCatching {
        file.reader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(cap + 1)
            var count = 0
            while (count < buffer.size) {
                val n = reader.read(buffer, count, buffer.size - count)
                if (n < 0) break
                count += n
            }
            String(buffer, 0, count)
        }
    }.getOrElse { "[Could not read; use the read tool to inspect.]" }

    /** Understand schema v1 without spending context on hashes or raw JSON syntax. */
    private fun handoffExcerpt(file: File, cap: Int): String = runCatching {
        val raw = readExcerpt(file, 256_000)
        val obj = Json.parseToJsonElement(raw).jsonObject
        val version = obj["schemaVersion"]?.jsonPrimitive?.contentOrNull
        if (version != "1") return@runCatching "Unsupported handoff schema $version; inspect the file manually."
        buildString {
            append("Handoff schema 1")
            scalar(obj, "goal")?.let { append("\nGoal: ").append(it) }
            scalar(obj, "decisions")?.let { append("\nDecisions/context: ").append(it) }
            scalar(obj, "completedWork")?.let { append("\nCompleted work: ").append(it) }
            val git = obj["git"] as? JsonObject
            if (git != null) {
                append("\nGit: branch=").append(scalar(git, "branch") ?: "unknown")
                append(" commit=").append(scalar(git, "commit") ?: "unknown")
                val dirty = git["dirtyFiles"] as? JsonArray
                if (!dirty.isNullOrEmpty()) {
                    append("\nDirty paths: ")
                    append(dirty.take(30).mapNotNull { (it as? JsonObject)?.let { o -> scalar(o, "path") } }.joinToString(", "))
                    if (dirty.size > 30) append(" (+${dirty.size - 30} more)")
                }
            }
            val pending = obj["pendingWork"] as? JsonArray
            if (!pending.isNullOrEmpty()) append("\nPending work: ").append(pending.joinToString())
            append("\nVerify tests on this target; an empty tests list is not evidence of success.")
        }.take(cap + 1)
    }.getOrElse { "[Invalid handoff; inspect .keepagent/handoff.json manually.]" }

    private fun scalar(obj: JsonObject, key: String): String? =
        obj[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
}
