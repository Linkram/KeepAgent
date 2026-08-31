package io.keepagent.runtime.js

/**
 * The Tier-2 prelude, shared by the in-process runtime (JsAddonRuntime in
 * core/host) and the helper-process sandbox (JsSandboxService here).
 * Defines the `ka` host API and the tool dispatcher; the C bridge exposes
 * the `native` object it calls.
 */
object JsPrelude {

    const val PRELUDE = """
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
              // Normalize to the host contract: {"ok":true,"text":...} /
              // {"ok":false,"error":...}. Handler may return a string, an
              // object with a `text` (or `error`) field, or anything else.
              var payload;
              if (typeof result === "string") {
                payload = { ok: true, text: result };
              } else if (result && typeof result === "object") {
                if (typeof result.text === "string") payload = { ok: true, text: result.text };
                else if (typeof result.error === "string") payload = { ok: false, error: result.error };
                else payload = { ok: true, text: JSON.stringify(result) };
              } else {
                payload = { ok: true, text: (result === null || result === undefined) ? "" : String(result) };
              }
              return JSON.stringify(payload);
            } catch (e) {
              return JSON.stringify({ ok: false, error: String(e && e.message ? e.message : e) });
            }
          };
        })();
    """
}
