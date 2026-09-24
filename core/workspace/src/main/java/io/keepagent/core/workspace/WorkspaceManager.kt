package io.keepagent.core.workspace

import io.keepagent.core.events.EventBus
import io.keepagent.core.events.EventKind
import io.keepagent.core.settings.SettingsStore
import io.keepagent.core.storage.Storage
import java.io.File
import java.util.UUID

/** One workspace: a named directory the agent works in (spec §4.1 `core-workspace`). */
data class WorkspaceInfo(
    val name: String,
    val root: String,
    val lastModifiedMillis: Long,
    val fileCount: Int,
    val sizeBytes: Long,
    val linkedUri: String? = null,
)

/**
 * Creates, lists, and switches workspaces under [Storage.workspacesDir].
 * M1 keeps one active workspace; the selection is persisted in settings so
 * it survives restarts (SAF export/import lands in M2, F-017).
 */
class WorkspaceManager(
    private val storage: Storage,
    private val settings: SettingsStore,
    private val eventBus: EventBus,
) {

    private val root: File = storage.workspacesDir

    /** Returns the active workspace name, creating `default` on first run. */
    fun ensureDefault(): String {
        val active = settings.getString(SettingsStore.NS_SESSIONS, ACTIVE_KEY)
        if (active != null && File(root, active).isDirectory) return active
        val dir = File(root, DEFAULT_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
            eventBus.emit(EventKind.SYSTEM, "workspaces", "created workspace '$DEFAULT_NAME' at ${dir.absolutePath}")
        }
        settings.setString(SettingsStore.NS_SESSIONS, ACTIVE_KEY, DEFAULT_NAME)
        return DEFAULT_NAME
    }

    fun list(): List<WorkspaceInfo> {
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: emptyList()
        return dirs.map { dir ->
            val files = dir.walkTopDown().filter { it.isFile }.toList()
            WorkspaceInfo(
                name = dir.name,
                root = dir.absolutePath,
                lastModifiedMillis = files.maxOfOrNull { it.lastModified() } ?: dir.lastModified(),
                fileCount = files.size,
                sizeBytes = files.sumOf { it.length() },
                linkedUri = linkedUri(dir.name),
            )
        }.sortedBy { it.name }
    }

    /** Creates a new workspace; returns its name, or null if the name is unusable or taken. */
    fun create(name: String): String? {
        val sanitized = sanitize(name)
        if (sanitized.isEmpty() || sanitized.length > 40) return null
        if (File(root, sanitized).exists()) return null
        File(root, sanitized).mkdirs()
        eventBus.emit(EventKind.SYSTEM, "workspaces", "created workspace '$sanitized'")
        return sanitized
    }

    /** A linked project keeps a local working copy and a persisted Android folder grant. */
    fun link(name: String, uri: String): String? {
        val created = create(name) ?: return null
        settings.setString(SettingsStore.NS_SESSIONS, "linkedUri:$created", uri)
        return created
    }

    fun linkedUri(name: String): String? =
        settings.getString(SettingsStore.NS_SESSIONS, "linkedUri:$name")?.takeIf { it.isNotBlank() }

    fun recentNames(): List<String> =
        (root.listFiles()?.filter { it.isDirectory } ?: emptyList()).sortedWith(
            compareByDescending<File> {
                if (it.name == activeName()) Long.MAX_VALUE
                else settings.getString(SettingsStore.NS_SESSIONS, "activated:${it.name}")
                    ?.toLongOrNull() ?: it.lastModified()
            },
        ).map { it.name }

    /** Renames a workspace (and its persisted active pointer). Returns the new name, or null. */
    fun rename(oldName: String, newName: String): String? {
        val sanitized = sanitize(newName)
        if (sanitized.isEmpty() || sanitized.length > 40) return null
        if (sanitized == oldName) return oldName
        val oldDir = File(root, oldName)
        val newDir = File(root, sanitized)
        if (!oldDir.isDirectory || newDir.exists()) return null
        if (!oldDir.renameTo(newDir)) return null
        if (activeName() == oldName) {
            settings.setString(SettingsStore.NS_SESSIONS, ACTIVE_KEY, sanitized)
            eventBus.emit(EventKind.SYSTEM, "workspaces", "active workspace renamed -> $sanitized")
        }
        linkedUri(oldName)?.let {
            settings.setString(SettingsStore.NS_SESSIONS, "linkedUri:$sanitized", it)
            settings.setString(SettingsStore.NS_SESSIONS, "linkedUri:$oldName", "")
        }
        settings.getString(SettingsStore.NS_SESSIONS, "activated:$oldName")?.let {
            settings.setString(SettingsStore.NS_SESSIONS, "activated:$sanitized", it)
            settings.setString(SettingsStore.NS_SESSIONS, "activated:$oldName", "")
        }
        eventBus.emit(EventKind.SYSTEM, "workspaces", "renamed workspace '$oldName' -> '$sanitized'")
        return sanitized
    }

    /** Deletes a workspace (never the active one). Returns true on success. */
    fun delete(name: String): Boolean {
        if (name == activeName()) return false
        val dir = File(root, name)
        if (!dir.exists() || dir.parentFile != root) return false
        val ok = dir.deleteRecursively()
        if (ok) settings.setString(SettingsStore.NS_SESSIONS, "linkedUri:$name", "")
        if (ok) eventBus.emit(EventKind.SYSTEM, "workspaces", "deleted workspace '$name'")
        return ok
    }

    fun activeName(): String = settings.getString(SettingsStore.NS_SESSIONS, ACTIVE_KEY) ?: DEFAULT_NAME

    fun setActive(name: String): Boolean {
        val dir = File(root, name)
        if (!dir.isDirectory) return false
        settings.setString(SettingsStore.NS_SESSIONS, ACTIVE_KEY, name)
        settings.setString(SettingsStore.NS_SESSIONS, "activated:$name", System.currentTimeMillis().toString())
        eventBus.emit(EventKind.SYSTEM, "workspaces", "active workspace -> $name")
        return true
    }

    fun activeRoot(): File = File(root, activeName())

    /** Resolves an existing project directory without allowing path traversal. */
    fun rootFor(name: String): File? {
        val candidate = File(root, name)
        return candidate.takeIf {
            it.isDirectory && it.parentFile?.canonicalFile == root.canonicalFile
        }
    }

    /**
     * Builds a project outside the visible workspace list, then atomically
     * publishes it. This keeps interrupted clones/generators from appearing as
     * valid projects and never overwrites an existing destination.
     */
    fun importGenerated(name: String, populate: (File) -> Unit): WorkspaceInfo {
        val sanitized = sanitize(name)
        require(sanitized.isNotEmpty() && sanitized.length <= 40) { "Choose a valid project name." }
        val destination = File(root, sanitized)
        require(!destination.exists()) { "That project name is unavailable." }
        val stagingRoot = File(root.parentFile, ".project-imports")
        check(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "Cannot create import staging area." }
        val staging = File(stagingRoot, "generated-${UUID.randomUUID()}")
        val project = File(staging, sanitized)
        check(project.mkdirs()) { "Cannot create import staging directory." }
        try {
            populate(project)
            require(project.listFiles()?.isNotEmpty() == true) { "The imported project is empty." }
            check(project.renameTo(destination)) { "Cannot finish project import." }
            val files = destination.walkTopDown().filter { it.isFile }.toList()
            return WorkspaceInfo(
                name = sanitized,
                root = destination.absolutePath,
                lastModifiedMillis = files.maxOfOrNull { it.lastModified() } ?: destination.lastModified(),
                fileCount = files.size,
                sizeBytes = files.sumOf { it.length() },
            ).also {
                eventBus.emit(EventKind.SYSTEM, "workspaces", "imported generated project '$sanitized': ${it.fileCount} files")
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    fun importZip(input: java.io.InputStream, name: String): ProjectImporter.Result {
        val result = ProjectImporter(root).importZip(input, name)
        eventBus.emit(EventKind.SYSTEM, "workspaces", "imported '${result.name}': ${result.files} files, ${result.bytes} bytes")
        return result
    }

    companion object {
        const val DEFAULT_NAME = "default"
        const val ACTIVE_KEY = "activeWorkspace"

        private fun sanitize(name: String): String =
            name.replace(Regex("[^a-zA-Z0-9-]"), "-").trim('-')
    }
}
