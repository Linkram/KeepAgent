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
