package io.keepagent.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.keepagent.app.ui.KeepAgentShell
import io.keepagent.app.ui.common.Pending
import io.keepagent.app.ui.theme.KeepAgentTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncoming(intent)
        setContent {
            KeepAgentTheme {
                KeepAgentShell()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    /**
     * Share-in (M1.4): text/plain or text/html sent/viewed from other apps is
     * copied into the active workspace under imports/ and attached to the
     * next chat message. The shell switches to Chat to show the attachment.
     */
    private fun handleIncoming(intent: Intent?) {
        val text = runCatching {
            when (intent?.action) {
                Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                Intent.ACTION_VIEW -> intent.data?.let { uri ->
                    contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }
                else -> null
            }
        }.getOrNull()
        if (text.isNullOrBlank() || text.length > 2_000_000) return
        val app = Holder.app
        val rel = runCatching {
            val dir = File(app.fileService.root, "imports")
            dir.mkdirs()
            val name = "shared-${System.currentTimeMillis()}.txt"
            File(dir, name).writeText(text)
            "imports/$name"
        }.getOrNull() ?: return
        app.chatController.attachFile(ChatController.AttachedFile(rel, false, ""))
        Pending.sharedImport = rel
    }
}
