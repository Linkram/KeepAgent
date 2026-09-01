package io.keepagent.core.fs

import io.keepagent.core.events.EventBus
import io.keepagent.core.events.EventKind
import io.keepagent.core.settings.FileAccess
import java.io.File
import java.util.regex.Pattern

/**
 * Policy-scoped file access (spec §4.2 `core-fs`, ADR-0002).
 *
 * [mode] is the binary toggle: [FileAccess.WORKSPACE] confines every
 * operation to the active workspace root (the default); [FileAccess.FULL]
 * lifts the confinement. Enforcement lives here — tools never re-check paths.
 *
 * Synchronous by design; the agent loop calls it off the main thread.
 */
class FileService(
    root: File,
    private val eventBus: EventBus,
    private val initialMode: FileAccess = FileAccess.WORKSPACE,
) {

    data class Result(
        val ok: Boolean,
        val text: String = "",
        val error: String? = null,
    ) {
        companion object {
            fun ok(text: String = "") = Result(true, text)
            fun fail(error: String) = Result(false, error = error)
        }
    }

    @Volatile
    var root: File = root
        private set

    @Volatile
    var mode: FileAccess = initialMode
        private set

    fun setRoot(newRoot: File) {
        root = newRoot
        eventBus.emit(EventKind.SYSTEM, "fs", "workspace root -> ${newRoot.absolutePath}")
    }

    fun setMode(m: FileAccess) {
        if (m != mode) {
            mode = m
            eventBus.emit(EventKind.SYSTEM, "fs", "file access -> ${m.name.lowercase()}")
        }
    }

    /**
     * Resolves a path given by the model or the user. In [FileAccess.WORKSPACE]
     * mode the resolved path must stay under [root]; escapes are blocked and
     * logged. Returns null when the path is unusable or escapes.
     */
    fun resolve(path: String): File? {
        if (path.isBlank()) return null
        val requested = File(path)
        val target = if (requested.isAbsolute) requested else File(root, path)
        val abs = try {
            target.absoluteFile.normalize()
        } catch (_: Exception) {
            return null
        }
        if (mode == FileAccess.WORKSPACE) {
            val rootAbs = root.absoluteFile.normalize()
            val p = abs.path.replace('/', '\\')
            val rp = rootAbs.path.replace('/', '\\')
            if (p != rp && !p.startsWith(rp + "\\")) {
                eventBus.emit(EventKind.ERROR, "fs", "path escape blocked (workspace mode): $path")
                return null
            }
        }
        return abs
    }

    /** Reads a text file. Binary and very large files are rejected with a message. */
    fun read(path: String, maxChars: Int = 64_000): Result {
        val f = resolve(path) ?: return Result.fail("outside workspace (file access = workspace)")
        if (!f.exists()) return Result.fail("no such file: $path")
        if (f.isDirectory) return Result.fail("is a directory: $path")
        if (f.length() > MAX_FILE_BYTES) {
            return Result.fail("file too large (${f.length()} bytes, max $MAX_FILE_BYTES) — use grep or read a different file")
        }
        val bytes = f.readBytes()
        if (bytes.contains(0.toByte())) return Result.fail("binary file, not text: $path")
        val text = bytes.decodeToString()
        return if (text.length > maxChars) {
            Result.ok(text.take(maxChars) + "\n… [truncated at $maxChars chars — file has ${text.length}]")
        } else {
            Result.ok(text)
        }
    }

    /** Creates or overwrites a file (parent directories created). */
    fun write(path: String, content: String): Result {
        val f = resolve(path) ?: return Result.fail("outside workspace (file access = workspace)")
        try {
            f.parentFile?.mkdirs()
            f.writeBytes(content.encodeToByteArray())
        } catch (e: Exception) {
            return Result.fail("write failed: ${e.message}")
        }
        return Result.ok("wrote $path (${content.length} chars)")
    }

    /**
     * Replaces [oldString] with [newString] in a file. [oldString] must occur
     * exactly once unless [replaceAll] is set; zero or ambiguous matches are
     * reported so the model can widen or narrow the context.
     */
    fun edit(path: String, oldString: String, newString: String, replaceAll: Boolean = false): Result {
        if (oldString.isEmpty()) return Result.fail("oldString must not be empty")
        val f = resolve(path) ?: return Result.fail("outside workspace (file access = workspace)")
        if (!f.exists()) return Result.fail("no such file: $path")
        if (f.isDirectory) return Result.fail("is a directory: $path")
        val original = try {
            f.readText()
        } catch (e: Exception) {
            return Result.fail("read failed: ${e.message}")
        }
        val count = original.split(oldString).size - 1
        if (count == 0) return Result.fail("oldString not found in $path")
        if (count > 1 && !replaceAll) {
            return Result.fail("oldString is not unique ($count occurrences in $path) — widen the context or set replaceAll")
        }
        val updated = if (replaceAll) original.replace(oldString, newString) else original.replaceFirst(oldString, newString)
        return try {
            f.writeBytes(updated.encodeToByteArray())
            Result.ok("edited $path (${if (replaceAll) count else 1} replacement(s))")
        } catch (e: Exception) {
            Result.fail("write failed: ${e.message}")
        }
    }

    /**
     * Glob over the workspace root. Patterns without `/` match the basename
     * at any depth (`*.kt`); patterns with `/` match the relative path
     * (`src/**/*.kt`). Results are relative paths, newest first.
     */
    fun glob(pattern: String, limit: Int = 200): Result {
        val re = try {
            globToRegex(pattern)
        } catch (e: Exception) {
            return Result.fail("invalid pattern: ${e.message}")
        }
        val anchored = pattern.contains('/')
        val matches = mutableListOf<Pair<Long, String>>()
        if (root.exists()) {
            root.walkTopDown()
                .onEnter { it.name != ".git" && it.name != "node_modules" }
                .filter { it.isFile }
                .forEach { f ->
                    val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                    val hay = if (anchored) rel else f.name
                    if (re.matcher(hay).matches()) matches.add(f.lastModified() to rel)
                }
        }
        matches.sortWith(compareByDescending<Pair<Long, String>> { it.first }.thenBy { it.second })
        return Result.ok(matches.take(limit).joinToString("\n") { it.second })
    }

    /**
     * Regex search over files. Returns `path:line: text` lines, capped at
     * [limit]. Directories `.git`/`node_modules`, binary files, and files
     * over 1 MiB are skipped.
     */
    fun grep(
        pattern: String,
        path: String? = null,
        include: String? = null,
        limit: Int = 250,
    ): Result {
        val re = try {
            Pattern.compile(pattern)
        } catch (e: Exception) {
            return Result.fail("invalid regex: ${e.message}")
        }
        val includeRe = include?.let {
            try {
                globToRegex(it)
            } catch (_: Exception) {
                return Result.fail("invalid include pattern: $include")
            }
        }
        val start = when {
            path == null -> root
            else -> resolve(path) ?: return Result.fail("outside workspace (file access = workspace)")
        }
        if (!start.exists()) return Result.fail("no such path: ${path ?: root.path}")
        val lines = mutableListOf<String>()
        var total = 0
        start.walkTopDown()
            .onEnter { if (it.isDirectory) it.name != ".git" && it.name != "node_modules" else true }
            .filter { it.isFile && it.length() <= 1_048_576 }
            .forEach { f ->
                if (includeRe != null && !includeRe.matcher(f.name).matches()) return@forEach
                val bytes = f.readBytes()
                if (bytes.contains(0.toByte())) return@forEach
                val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
                bytes.decodeToString().lineSequence().forEachIndexed { idx, line ->
                    if (re.matcher(line).find()) {
                        total++
                        if (lines.size < limit) lines += "$rel:${idx + 1}: ${line.trimEnd()}"
                    }
                }
            }
        return Result.ok(
            if (lines.isEmpty()) {
                "(no matches for /$pattern/)"
            } else {
                lines.joinToString("\n") +
                    (if (total > limit) "\n… ($total total matches, showing $limit)" else "")
            },
        )
    }

    /** One entry in a directory listing, for the UI file browser. */
    data class DirEntry(
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
    )

    /**
     * Typed directory listing for the Workspaces tab file browser.
     * Returns null when the path is blocked or not a directory.
     */
    fun browse(path: String = "."): List<DirEntry>? {
        val f = resolve(path.ifBlank { "." }) ?: return null
        if (!f.exists() || !f.isDirectory) return null
        return f.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?.map { DirEntry(it.name, it.isDirectory, if (it.isDirectory) 0L else it.length()) }
            ?: emptyList()
    }

    /** Lists a directory (for the Workspaces tab browser). */
    fun listDir(path: String = "."): Result {
        val f = resolve(path.ifBlank { "." }) ?: return Result.fail("outside workspace (file access = workspace)")
        if (!f.exists()) return Result.fail("no such path: $path")
        if (!f.isDirectory) return Result.fail("not a directory: $path")
        val entries = f.listFiles()
            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
        return Result.ok(
            entries.joinToString("\n") { if (it.isDirectory) it.name + "/" else it.name }
                .ifEmpty { "(empty)" },
        )
    }

    companion object {
        const val MAX_FILE_BYTES = 512L * 1024
        private const val MAX_GREP_FILE_BYTES = 1_048_576

        /** Converts a shell-style glob to a regex: a `**` segment matches any depth; `*` and `?` match within a segment. */
        fun globToRegex(pattern: String): Pattern {
            val sb = StringBuilder()
            var i = 0
            while (i < pattern.length) {
                when {
                    pattern.startsWith("**/", i) -> { sb.append("(?:.*/)?"); i += 3 }
                    pattern.startsWith("/**", i) -> { sb.append(".*"); i += 3 }
                    pattern[i] == '*' -> { sb.append("[^/]*"); i++ }
                    pattern[i] == '?' -> { sb.append("[^/]"); i++ }
                    else -> { sb.append(Pattern.quote(pattern[i].toString())); i++ }
                }
            }
            return Pattern.compile(sb.toString())
        }
    }
}
