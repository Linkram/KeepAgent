package io.keepagent.core.events

/** Kinds of events flowing through the KeepAgent event stream. */
enum class EventKind {
    SYSTEM,   // host, storage, app lifecycle
    ADDON,    // add-on lifecycle (discovered, validated, enabled, initialized, failed)
    TOOL,     // tool registration and invocations
    PROVIDER, // LLM provider activity (M1)
    MESSAGE,  // chat messages (M1)
    APPROVAL, // approval gate activity (M1)
    JOB,      // background jobs (M2)
    ERROR,
}

/**
 * One entry in the append-only event stream (spec §9).
 * [seq] is monotonically increasing per process; [timestamp] is epoch millis.
 */
data class AgentEvent(
    val seq: Long,
    val timestamp: Long,
    val kind: EventKind,
    val source: String,
    val summary: String,
    val detail: Map<String, String> = emptyMap(),
)
