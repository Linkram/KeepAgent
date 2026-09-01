package io.keepagent.app.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Clipboard helper used by chat copy actions. */
object Clip {
    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("KeepAgent", text))
    }
}

/**
 * Cross-tab handoff: the Chat tab can ask the Workspaces tab to focus a file.
 * The shell switches tabs; WorkspacesTab consumes [pendingPath] on entry.
 */
object FileOpener {
    @Volatile
    var pendingPath: String? = null

    fun open(path: String) {
        pendingPath = path
    }
}

/**
 * One-shot cross-screen handoffs: a share-in file just landed in the
 * workspace, so the shell jumps to Chat to show the attachment.
 */
object Pending {
    @Volatile
    var sharedImport: String? = null
}

/** Recently opened workspace files (M1.4h), shown at the top of the browser. */
object Recents {
    @Volatile
    var files: List<String> = emptyList()

    fun add(relPath: String) {
        files = (listOf(relPath) + files).distinct().take(6)
    }
}
