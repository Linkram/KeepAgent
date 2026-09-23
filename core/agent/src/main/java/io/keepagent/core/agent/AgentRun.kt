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

/** Ordered, expandable work performed before the final assistant answer. */
sealed interface RunActivity {
    data class Thinking(val text: String) : RunActivity
    data class Tool(val line: ToolLine) : RunActivity
    data class Notice(val text: String) : RunActivity
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
    private val textBuffer = StringBuilder()

    /** Accumulated thinking/reasoning text for the whole turn. */
    private val _thinking = MutableStateFlow("")
    val thinking: StateFlow<String> = _thinking.asStateFlow()
    private val thinkingBuffer = StringBuilder()

    /** Thinking/tool events in the order they happened across model rounds. */
    private val _activities = MutableStateFlow<List<RunActivity>>(emptyList())
    val activities: StateFlow<List<RunActivity>> = _activities.asStateFlow()
    private var activeThinkingStart = -1

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

    /** Best live estimate for the prompt currently occupying the context. */
    @Volatile
    var currentPromptTokens: Int = 0
        private set

    @Volatile
    private var lastPublishedAtNanos: Long = 0L

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

    @Synchronized
    fun appendText(delta: String) {
        textBuffer.append(delta)
        publishStreamsIfDue()
    }

    @Synchronized
    fun appendThinking(delta: String) {
        if (activeThinkingStart < 0) {
            activeThinkingStart = thinkingBuffer.length
            _activities.value = _activities.value + RunActivity.Thinking("")
        }
        thinkingBuffer.append(delta)
        publishStreamsIfDue()
    }

    /** Publishes buffered stream text without copying it for every token. */
    @Synchronized
    fun flushStreams() {
        _text.value = textBuffer.toString()
        _thinking.value = thinkingBuffer.toString()
        publishThinkingActivity()
        lastPublishedAtNanos = System.nanoTime()
    }

    @Synchronized
    private fun publishStreamsIfDue() {
        val size = textBuffer.length + thinkingBuffer.length
        val intervalMs = when {
            size >= 32_000 -> 500L
            size >= 8_000 -> 250L
            else -> 75L
        }
        val now = System.nanoTime()
        if (lastPublishedAtNanos == 0L || now - lastPublishedAtNanos >= intervalMs * 1_000_000L) {
            _text.value = textBuffer.toString()
            _thinking.value = thinkingBuffer.toString()
            publishThinkingActivity()
            lastPublishedAtNanos = now
        }
    }

    private fun publishThinkingActivity() {
        if (activeThinkingStart < 0) return
        val current = _activities.value
        if (current.lastOrNull() is RunActivity.Thinking) {
            _activities.value = current.dropLast(1) +
                RunActivity.Thinking(thinkingBuffer.substring(activeThinkingStart))
        }
    }

    fun addMessage(message: ChatMessage) {
        _messages.value = _messages.value + message
    }

    fun replaceMessages(messages: List<ChatMessage>) {
        _messages.value = messages
    }

    @Synchronized
    fun addToolLine(line: ToolLine) {
        flushStreams()
        activeThinkingStart = -1
        _toolLines.value = _toolLines.value + line
        _activities.value = _activities.value + RunActivity.Tool(line)
    }

    fun setToolLine(id: String, transform: (ToolLine) -> ToolLine) {
        var updated: ToolLine? = null
        _toolLines.value = _toolLines.value.map {
            if (it.id == id) transform(it).also { line -> updated = line } else it
        }
        updated?.let { line ->
            _activities.value = _activities.value.map {
                if (it is RunActivity.Tool && it.line.id == id) RunActivity.Tool(line) else it
            }
        }
    }

    fun addNotice(text: String) {
        _activities.value = _activities.value + RunActivity.Notice(text)
    }

    fun setCurrentPromptTokens(tokens: Int) {
        currentPromptTokens = tokens.coerceAtLeast(0)
    }

    /** Live fallback until the provider publishes authoritative usage. */
    @Synchronized
    fun estimatedCompletionTokens(): Int =
        maxOf(usage.completionTokens, (textBuffer.length + thinkingBuffer.length + 3) / 4)

    fun addUsage(promptTokens: Int, completionTokens: Int) {
        usage = LlmUsage(usage.promptTokens + promptTokens, usage.completionTokens + completionTokens)
    }

    fun complete() {
        flushStreams()
        _status.value = Status.DONE
        if (finishedAtMillis == 0L) finishedAtMillis = System.currentTimeMillis()
    }

    /** The user stopped the turn (stop button / notification action). */
    fun cancel() {
        flushStreams()
        _status.value = Status.CANCELED
        if (finishedAtMillis == 0L) finishedAtMillis = System.currentTimeMillis()
    }

    fun fail(message: String) {
        flushStreams()
        _error.value = message
        _status.value = Status.ERROR
        if (finishedAtMillis == 0L) finishedAtMillis = System.currentTimeMillis()
    }
}
