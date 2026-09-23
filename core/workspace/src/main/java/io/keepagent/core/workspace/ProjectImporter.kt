package io.keepagent.core.workspace

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Imports into a private staging directory; an existing project is never overwritten. */
class ProjectImporter(
    private val workspaceRoot: File,
    private val maxBytes: Long = 512L * 1024 * 1024,
    private val maxEntries: Int = 50_000,
) {
    data class Result(val name: String, val files: Int, val bytes: Long)

    fun importZip(input: InputStream, requestedName: String): Result {
        val name = requestedName.replace(Regex("[^a-zA-Z0-9-]"), "-").trim('-').take(40)
        require(name.isNotBlank()) { "Enter a project name using letters or numbers." }
        val destination = File(workspaceRoot, name)
        require(!destination.exists()) { "A project named '$name' already exists." }
        val stagingRoot = File(workspaceRoot.parentFile, ".project-imports")
        check(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "Cannot create import staging area." }
        val staging = java.nio.file.Files.createTempDirectory(stagingRoot.toPath(), "zip-").toFile()
        var bytes = 0L
        var files = 0
        var entries = 0
        val seen = HashSet<String>()
        try {
            ZipInputStream(input).use { zip ->
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(++entries <= maxEntries) { "Archive has more than $maxEntries entries." }
                    val path = entry.name.replace('\\', '/')
                    require(path.isNotBlank() && !path.startsWith('/') && ':' !in path &&
                        path.split('/').none { it == ".." || it == "." }) { "Unsafe archive path: $path" }
                    val target = File(staging, path).canonicalFile
                    require(target.toPath().startsWith(staging.canonicalFile.toPath()) && target != staging) {
                        "Archive path escapes the project."
                    }
                    require(seen.add(target.path)) { "Duplicate archive entry: $path" }
                    if (entry.isDirectory) {
                        check(target.isDirectory || target.mkdirs()) { "Cannot create folder: $path" }
                    } else {
                        val parent = requireNotNull(target.parentFile)
                        check(parent.isDirectory || parent.mkdirs()) { "Cannot create parent: $path" }
                        target.outputStream().use { output ->
                            while (true) {
                                val count = zip.read(buffer)
                                if (count < 0) break
                                bytes += count
                                require(bytes <= maxBytes) { "Expanded archive exceeds ${maxBytes / 1024 / 1024} MiB." }
                                output.write(buffer, 0, count)
                            }
                        }
                        files++
                    }
                    zip.closeEntry()
                }
            }
            require(files > 0) { "The archive contains no files." }
            // GitHub and desktop exports often wrap the repository in a single folder.
            val children = staging.listFiles().orEmpty()
            val source = children.singleOrNull()?.takeIf { it.isDirectory && !it.name.startsWith('.') } ?: staging
            synchronized(IMPORT_LOCK) {
                require(!destination.exists()) { "A project named '$name' already exists." }
                check(workspaceRoot.isDirectory || workspaceRoot.mkdirs()) { "Cannot create workspace folder." }
                check(source.renameTo(destination)) { "Cannot finish project import." }
            }
            return Result(name, files, bytes)
        } finally {
            // Only this invocation's generated staging directory is eligible for cleanup.
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private companion object { val IMPORT_LOCK = Any() }
}
