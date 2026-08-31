package io.keepagent.addonsapi.llm

/**
 * An LLM provider — the `llm.provider` capability (spec §4.1, §5.3).
 *
 * Providers register with the host at init; the agent loop only ever sees
 * this interface. Streaming is event-driven: [streamChat] invokes [onEvent]
 * as deltas arrive and returns the full [LlmResult].
 */
interface LlmProvider {
    /** Stable provider id, e.g. `openai-compatible`. */
    val id: String

    /** Models offered by this provider. May return empty if the endpoint has no model list. */
    suspend fun listModels(): List<LlmModel>

    suspend fun streamChat(
        request: LlmRequest,
        onEvent: suspend (LlmEvent) -> Unit,
    ): LlmResult

    /** Release network resources. Default: no-op. */
    suspend fun close() {}
}
