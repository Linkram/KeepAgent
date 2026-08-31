package io.keepagent.core.workspace

import io.keepagent.core.events.EventBus
import io.keepagent.core.events.EventKind
import io.keepagent.core.settings.SettingsStore
import io.keepagent.core.storage.Storage
import java.io.File

/** One workspace: a named directory the agent works in (spec §4.1 `core-workspace`). */
data class WorkspaceInfo(
    val name: String,
    val root: String,
    val lastModifiedMillis: Long,
    val fileCount: Int,
    val sizeBytes: Long,
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
            )
        }.sortedBy { it.name }
    }

    /** Creates a new workspace; returns its name, or null if the name is unusable or taken. */
    fun create(name: String): String? {
        val sanitized = name.replace(Regex("[^a-zA-Z0-9-]"), "-").trim('-')
        if (sanitized.isEmpty() || sanitized.length > 40) return null
        if (File(root, sanitized).exists()) return null
        File(root, sanitized).mkdirs()
        eventBus.emit(EventKind.SYSTEM, "workspaces", "created workspace '$sanitized'")
        return sanitized
    }

    /** Deletes a workspace (never the active one). Returns true on success. */
    fun delete(name: String): Boolean {
        if (name == activeName()) return false
        val dir = File(root, name)
        if (!dir.exists() || dir.parentFile != root) return false
        val ok = dir.deleteRecursively()
        if (ok) eventBus.emit(EventKind.SYSTEM, "workspaces", "deleted workspace '$name'")
        return ok
    }

    fun activeName(): String = settings.getString(SettingsStore.NS_SESSIONS, ACTIVE_KEY) ?: DEFAULT_NAME

    fun setActive(name: String): Boolean {
        val dir = File(root, name)
        if (!dir.isDirectory) return false
        settings.setString(SettingsStore.NS_SESSIONS, ACTIVE_KEY, name)
        eventBus.emit(EventKind.SYSTEM, "workspaces", "active workspace -> $name")
        return true
    }

    fun activeRoot(): File = File(root, activeName())

    companion object {
        const val DEFAULT_NAME = "default"
        const val ACTIVE_KEY = "activeWorkspace"
    }
}
