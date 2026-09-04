package io.keepagent.runtime.js

/**
 * Thin Kotlin wrapper over the quickjs-ng JNI bridge.
 * One JsHost = one isolated JS context (one add-on in M0).
 */
class JsHost(private val callbacks: Callbacks) {

    /** Java-side mirror of the `native` object installed by the C bridge. */
    interface Callbacks {
        fun onLog(message: String)
        fun onRegisterTool(specJson: String)
        fun onError(message: String)

        /** The active workspace root, exposed to add-ons. */
        fun workspacePath(): String

        /** JSON settings document for a namespace, or null if unreadable. */
        fun settingsGet(namespace: String): String?
    }

    private var handle = 0L

    /** True when the native library is available. */
    val isAvailable: Boolean
        get() = QuickJs.isLoaded

    /** Creates the JS runtime/context and installs the `native` host object. */
    fun start(): Boolean {
        if (handle != 0L) return true
        if (!QuickJs.isLoaded) return false
        handle = nativeCreate(callbacks)
        return handle != 0L
    }

    /** Evaluates JS source. Returns an error message, or null on success. */
    fun evaluate(source: String, filename: String): String? {
        if (handle == 0L) return "runtime not started"
        return try {
            nativeEval(handle, source, filename)
            null
        } catch (e: RuntimeException) {
            val message = e.message ?: "JS error"
            callbacks.onError(message)
            message
        }
    }

    /** Calls a global function with two string args; returns its string result. */
    fun callFunction(name: String, arg1: String, arg2: String?): String =
        if (handle == 0L) "" else nativeCallTwo(handle, name, arg1, arg2) ?: ""

    fun close() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    private external fun nativeCreate(callbacks: Callbacks): Long
    private external fun nativeEval(handle: Long, source: String, filename: String): String?
    private external fun nativeCallTwo(handle: Long, funcName: String, arg1: String, arg2: String?): String?
    private external fun nativeDestroy(handle: Long)
}
