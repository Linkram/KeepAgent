package io.keepagent.runtime.js;

/**
 * Client-side callbacks for one sandboxed add-on hosted by
 * JsSandboxService in the helper process.
 */
interface IKeepJsCallback {
    void onLog(String message);
    void onRegisterTool(String specJson);
    void onError(String message);
}
