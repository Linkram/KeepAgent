package io.keepagent.runtime.js

import android.app.Service
import android.content.Intent
import android.os.IBinder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * Hosts the quickjs-ng sandbox engines in the helper process (`:js`,
 * ADR-0001 M1). One engine per add-on; the client (core/host) talks to it
 * over the KeepJsService AIDL boundary, so a crashing or hanging add-on
 * costs the helper process, not the app.
 *
 * The prelude and the add-on source are exactly the same as in-process;
 * only the transport around the `native` object changes.
 */
class JsSandboxService : Service() {

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    private class Entry(val host: JsHost)

    /** The AIDL stub. Lives in this service so the binder outlives the client. */
    private val binder = object : KeepJsService.Stub() {
        override fun init(
            addonId: String,
            callback: IKeepJsCallback?,
            settingsJson: String?,
            workspacePath: String?,
        ) {
            this@JsSandboxService.init(addonId, callback, settingsJson, workspacePath)
        }

        override fun eval(addonId: String, source: String, filename: String): String =
            this@JsSandboxService.eval(addonId, source, filename)

        override fun invokeTool(addonId: String, toolName: String, argsJson: String): String =
            this@JsSandboxService.invokeTool(addonId, toolName, argsJson)

        override fun shutdownAddon(addonId: String) {
            this@JsSandboxService.shutdownAddon(addonId)
        }

        override fun shutdownAll() {
            this@JsSandboxService.shutdownAll()
        }
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun init(
        addonId: String,
        callback: IKeepJsCallback?,
        settingsJson: String?,
        workspacePath: String?,
    ) {
        if (callback == null) return
        val settingsDoc = settingsJson ?: "{}"
        val cb = callback
        synchronized(lock) {
            entries.remove(addonId)?.host?.close()
            val host = JsHost(
                object : JsHost.Callbacks {
                    override fun onLog(message: String) {
                        runCatching { cb.onLog(message) }
                    }

                    override fun onRegisterTool(specJson: String) {
                        runCatching { cb.onRegisterTool(specJson) }
                    }

                    override fun onError(message: String) {
                        runCatching { cb.onError(message) }
                    }

                    override fun workspacePath(): String = workspacePath ?: ""

                    override fun settingsGet(namespace: String): String? =
                        try {
                            json.parseToJsonElement(settingsDoc).jsonObject[namespace]?.toString()
                        } catch (_: Exception) {
                            null
                        }
                },
            )
            if (!host.start()) {
                runCatching { cb.onError("native runtime unavailable in helper process") }
                return
            }
            val preludeError = host.evaluate(JsPrelude.PRELUDE, "keepagent-prelude.js")
            if (preludeError != null) {
                host.close()
                runCatching { cb.onError("prelude failed: $preludeError") }
                return
            }
            entries[addonId] = Entry(host)
        }
    }

    private fun eval(addonId: String, source: String, filename: String): String =
        synchronized(lock) {
            val host = entries[addonId]?.host
            if (host == null) return "add-on not initialized in helper process"
            host.evaluate(source, filename) ?: ""
        }

    private fun invokeTool(addonId: String, toolName: String, argsJson: String): String =
        synchronized(lock) {
            val host = entries[addonId]?.host
                ?: return """{"ok":false,"error":"add-on not initialized in helper process"}"""
            host.callFunction("__kaInvokeTool", toolName, argsJson)
        }

    private fun shutdownAddon(addonId: String) {
        synchronized(lock) {
            entries.remove(addonId)?.host?.close()
        }
    }

    private fun shutdownAll() {
        synchronized(lock) {
            entries.values.forEach { it.host.close() }
            entries.clear()
        }
    }

    override fun onDestroy() {
        shutdownAll()
        super.onDestroy()
    }
}
