package io.keepagent.core.agent

import io.keepagent.addonsapi.llm.ChatMessage
import io.keepagent.addonsapi.llm.LlmUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** One compact tool invocation for the chat view (F-004 tool lines). */
data class ToolLine(
    val id: String,
    val name: String,
    val summary: String,
    val status: Status,
    val detail: String? = null,
    /** Structured extras for the UI (diff old/new content, file path, …). */
    val extra: Map<String, String> = emptyMap(),
) {
    enum class Status { RUNNING, OK, ERROR, DENIED }
}

/**
 * The live state of one agent turn, observed by the UI (F-003 thinking
 * blocks, F-004 tool lines, streaming text).
 */
class AgentRun {

    enum class Status { RUNNING, DONE, CANCELED, ERROR }

    val id: String = "run-${UUID.randomUUID()}"
    val createdAtMillis: Long = System.currentTimeMillis()

    private val _status = MutableStateFlow(Status.RUNNING)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Accumulated visible assistant text for the whole turn (across tool rounds). */
    private val _text = MutableStateFlow("")
    val text: StateFlow<String> = _text.asStateFlow()

    /** Accumulated thinking/reasoning text for the whole turn. */
    private val _thinking = MutableStateFlow("")
    val thinking: StateFlow<String> = _thinking.asStateFlow()

    /** Conversation so far (user + assistant + tool messages) — the model's context. */
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** Compact tool invocations, in order. */
    private val _toolLines = MutableStateFlow<List<ToolLine>>(emptyList())
    val toolLines: StateFlow<List<ToolLine>> = _toolLines.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    var usage: LlmUsage = LlmUsage()
        private set

    /** Set when the turn ends (complete/cancel/fail); 0 while running. */
    @Volatile
    var finishedAtMillis: Long = 0L

    val isRunning: Boolean get() = _status.value == Status.RUNNING

    val elapsedMs: Long
        get() = when {
            finishedAtMillis > createdAtMillis -> finishedAtMillis - createdAtMillis
            isRunning -> System.currentTimeMillis() - createdAtMillis
            else -> 0L
        }

    fun appendText(delta: String) {
        _text.value += delta
    }

    fun appendThinking(delta: String) {
        _thinking.value += delta
    }

    fun addMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    fun addToolLine(line: ToolLine) {
        _toolLines.value = _toolLines.value + line
    }

    fun setToolLine(id: String, transform: (ToolLine) -> ToolLine) {
        _toolLines.value = _toolLines.value.map { if (it.id == id) transform(it) else it }
    }

    fun addUsage(promptTokens: Int, completionTokens: Int) {
        usage = LlmUsage(usage.promptTokens + promptTokens, usage.completionTokens + completionTokens)
    }

    fun complete() {
        _status.value = Status.DONE
        if (finishedAtMillis == 0L) finishedAtMillis = System.currentTimeMillis()
    }

    /** The user stopped the turn (stop button / notification action). */
    fun cancel() {
        _status.value = Status.CANCELED
        if (finishedAtMillis == 0L) finishedAtMillis = System.currentTimeMillis()
    }

    fun fail(message: String) {
        _error.value = message
        _status.value = Status.ERROR
        if (finishedAtMillis == 0L) finishedAtMillis = System.currentTimeMillis()
    }
}
