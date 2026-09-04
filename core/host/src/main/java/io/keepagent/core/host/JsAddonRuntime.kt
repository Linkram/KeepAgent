package io.keepagent.core.host

import io.keepagent.core.settings.SettingsStore
import io.keepagent.runtime.js.JsHost
import io.keepagent.runtime.js.JsPrelude
import kotlinx.serialization.json.Json

/**
 * Tier-2 runtime on quickjs-ng (ADR-0001). The add-on runs in an isolated JS
 * context; the only host surface it can see is the `ka` object defined by the
 * prelude (runtime-js, [JsPrelude] — shared with the helper-process sandbox).
 *
 * The engine runs IN-PROCESS; the process-isolated twin is
 * [HelperAddonRuntime], which wraps the same prelude over AIDL IPC.
 * Tool handlers are synchronous (no Promise resolution in the C bridge).
 */
class JsAddonRuntime(
    private val addonId: String,
    // `var`: refreshEnvironment() re-points it on a workspace switch; the
    // callback below reads it live, so no engine restart is needed.
    private var workspacePath: String,
    private val settings: SettingsStore,
    private val onLog: (String) -> Unit,
    private val onRegisterTool: (addonId: String, specJson: String) -> Unit,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AddonRuntime {

    private var host: JsHost? = null

    override fun initialize(): Boolean {
        val h = JsHost(
            object : JsHost.Callbacks {
                override fun onLog(message: String) = this@JsAddonRuntime.onLog(message)
                override fun onRegisterTool(specJson: String) =
                    this@JsAddonRuntime.onRegisterTool(addonId, specJson)
                override fun onError(message: String) = this@JsAddonRuntime.onLog("error: $message")
                override fun workspacePath(): String = this@JsAddonRuntime.workspacePath
                override fun settingsGet(namespace: String): String? =
                    try {
                        settings.get(namespace).toString()
                    } catch (_: Exception) {
                        null
                    }
            },
        )
        if (!h.start()) {
            h.close()
            return false
        }
        val preludeError = h.evaluate(JsPrelude.PRELUDE, "keepagent-prelude.js")
        if (preludeError != null) {
            h.close()
            return false
        }
        host = h
        return true
    }

    override fun evaluate(source: String, filename: String): String? =
        host?.evaluate(source, filename) ?: "runtime not started"

    override fun invokeTool(name: String, argsJson: String): String =
        host?.callFunction("__kaInvokeTool", name, argsJson)
            ?: """{"ok":false,"error":"runtime not active"}"""

    override fun refreshEnvironment(workspacePath: String) {
        this.workspacePath = workspacePath
    }

    override fun shutdown() {
        host?.close()
        host = null
    }
}
