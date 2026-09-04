package io.keepagent.runtime.js;

import io.keepagent.runtime.js.IKeepJsCallback;

/**
 * The quickjs-ng sandbox service (helper process, ADR-0001 M1).
 * One engine per add-on; tool invocations and registrations cross
 * this IPC boundary.
 */
interface KeepJsService {
    // Creates the engine for an add-on and evaluates the prelude.
    void init(String addonId, IKeepJsCallback callback, String settingsJson, String workspacePath);

    // Evaluates the add-on's entry module. Returns an error message, or "" on success.
    String eval(String addonId, String source, String filename);

    // Invokes a registered tool handler; returns the handler's JSON result string.
    String invokeTool(String addonId, String toolName, String argsJson);

    // Refreshes the environment snapshot (settings document + workspace
    // path) for a live engine WITHOUT restarting it — used when the active
    // workspace or settings change mid-session.
    void updateEnv(String addonId, String settingsJson, String workspacePath);

    void shutdownAddon(String addonId);
    void shutdownAll();
}
