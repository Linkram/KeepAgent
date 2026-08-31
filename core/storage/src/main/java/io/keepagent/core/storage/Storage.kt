package io.keepagent.core.storage

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * On-device storage (spec §9): app data directory layout plus the
 * content-addressed attachment store (screenshots, attachments).
 */
class Storage(context: Context) {

    val root: File = File(context.filesDir, "keepagent").apply { mkdirs() }
    val addonsDir: File = File(root, "addons").apply { mkdirs() }
    val attachmentsDir: File = File(root, "attachments").apply { mkdirs() }
    val workspacesDir: File = File(root, "workspaces").apply { mkdirs() }
    val eventsFile: File = File(root, "events", "stream.jsonl").apply { parentFile?.mkdirs() }

    /** Stores bytes and returns their content hash; returns the existing hash if already present. */
    fun putAttachment(bytes: ByteArray, contentType: String?): String {
        val hash = sha256Hex(bytes)
        val file = File(attachmentsDir, hash)
        if (!file.exists()) file.writeBytes(bytes)
        return hash
    }

    fun readAttachment(hash: String): ByteArray? =
        runCatching { File(attachmentsDir, hash).readBytes() }.getOrNull()

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
