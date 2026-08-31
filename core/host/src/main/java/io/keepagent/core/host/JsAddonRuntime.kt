package io.keepagent.core.host

import io.keepagent.core.settings.SettingsStore
import io.keepagent.runtime.js.JsHost
import kotlinx.serialization.json.Json

/**
 * Tier-2 runtime on quickjs-ng (ADR-0001). The add-on runs in an isolated JS
 * context; the only host surface it can see is the `ka` object defined by the
 * prelude below.
 *
 * M0 notes:
 *  - The engine runs IN-PROCESS (spec §13 spike). Separate-process isolation
 *    + IPC lands in M1 — same prelude, different transport.
 *  - Tool handlers are synchronous in M0 (no Promise resolution in the C
 *    bridge). Async handlers land in M1.
 */
class JsAddonRuntime(
    private val addonId: String,
    private val workspacePath: String,
    private val settings: SettingsStore,
    private val onLog: (String) -> Unit,
    private val onRegisterTool: (addonId: String, specJson: String) -> Unit,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AddonRuntime {

    private var host: JsHost? = null

    override fun initialize(): Boolean {
        val h = JsHost(
            object : JsHost.Callbacks {
                override fun onLog(message: String) = onLog(message)
                override fun onRegisterTool(specJson: String) = onRegisterTool(addonId, specJson)
                override fun onError(message: String) = onLog("error: $message")
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
        val preludeError = h.evaluate(PRELUDE, "keepagent-prelude.js")
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

    override fun shutdown() {
        host?.close()
        host = null
    }

    companion object {
        /**
         * The Tier-2 prelude. Defines the `ka` host API and the tool
         * dispatcher; the C bridge exposes the `native` object it calls.
         */
        private const val PRELUDE = """
            (function () {
              "use strict";
              var tools = {};
              var ka = {
                log: function (msg) { native.log(String(msg)); },
                workspacePath: function () { return native.workspacePath(); },
                settingsGet: function (ns) { return native.settingsGet(String(ns)); },
                registerTool: function (spec) {
                  var s = (typeof spec === "string") ? JSON.parse(spec) : spec;
                  if (!s || !s.name) throw new Error("registerTool: 'name' is required");
                  if (typeof s.handler !== "function") throw new Error("registerTool: 'handler' must be a function");
                  if (tools[s.name]) throw new Error("registerTool: duplicate tool name '" + s.name + "'");
                  tools[s.name] = s;
                  // permission is uppercased: the host deserializes the
                  // ToolPermission enum by name (case-sensitive).
                  native.onRegisterTool(JSON.stringify({
                    name: s.name,
                    description: s.description || "",
                    permission: String(s.permission || "read").toUpperCase(),
                    inputSchema: s.inputSchema || { type: "object" }
                  }));
                },
              };
              globalThis.ka = ka;
              globalThis.__kaInvokeTool = function (name, argsJson) {
                var t = tools[name];
                if (!t) return JSON.stringify({ ok: false, error: "no such tool: " + name });
                var args = argsJson ? JSON.parse(argsJson) : {};
                try {
                  var result = t.handler(args, { log: ka.log, workspacePath: ka.workspacePath });
                  return JSON.stringify({
                    ok: true,
                    result: (result === undefined || result === null) ? null : result
                  });
                } catch (e) {
                  return JSON.stringify({ ok: false, error: String(e && e.message ? e.message : e) });
                }
              };
            })();
        """
    }
}
