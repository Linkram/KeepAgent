package io.keepagent.core.host

/**
 * A runtime that can execute one Tier-2 add-on (spec §5.1).
 *
 * M0: in-process quickjs-ng sandbox (ADR-0001, spec §13). M1: the engine
 * moves to a helper process and this interface's calls cross the IPC
 * boundary instead of JNI.
 */
interface AddonRuntime {

    /** Prepares the sandbox (host bindings). Returns false if the runtime is unavailable. */
    fun initialize(): Boolean

    /** Evaluates the add-on's entry module. Returns an error message, or null on success. */
    fun evaluate(source: String, filename: String): String?

    /** Invokes a tool handler by name; returns the handler's JSON result string. */
    fun invokeTool(name: String, argsJson: String): String

    fun shutdown()
}
