package io.keepagent.core.agent

import io.keepagent.addonsapi.llm.ChatMessage
import io.keepagent.addonsapi.llm.ImagePart
import io.keepagent.addonsapi.llm.LlmEvent
import io.keepagent.addonsapi.llm.LlmProvider
import io.keepagent.addonsapi.llm.LlmRequest
import io.keepagent.addonsapi.llm.ToolCall
import io.keepagent.core.events.EventBus
import io.keepagent.core.events.EventKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * The agent turn loop (spec §4.1 `core-agent`): send the conversation,
 * stream the model's response, execute requested tools (through the
 * approval gate), feed results back, and repeat until the model stops
 * calling tools or [maxRounds] is reached.
 */
class AgentLoop(
    private val provider: LlmProvider,
    private val modelId: String,
    private val tools: List<ToolSpec>,
    private val executor: ToolExecutor,
    private val approval: ApprovalGate,
    private val eventBus: EventBus,
    private val maxRounds: Int = MAX_ROUNDS,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    /**
     * @param history prior turns of the session (user/assistant pairs) so a
     *   continued conversation keeps its context;
     * @param systemText optional system prompt (workspace name, tool guidance).
     */
    suspend fun runTurn(
        run: AgentRun,
        userText: String,
        images: List<ImagePart> = emptyList(),
        history: List<ChatMessage> = emptyList(),
        systemText: String? = null,
    ) {
        try {
            systemText?.takeIf { it.isNotBlank() }?.let { run.addMessage(ChatMessage.system(it)) }
            history.forEach { run.addMessage(it) }
            run.addMessage(ChatMessage.user(userText, images))
            eventBus.emit(
                EventKind.MESSAGE,
                modelId,
                "user: ${truncate(userText)}" + (if (history.isNotEmpty()) " (+${history.size} prior messages)" else ""),
            )

            var round = 0
            while (round < maxRounds) {
                round++
                // A stop request between rounds ends the turn cleanly.
                currentCoroutineContext().ensureActive()
                val request = LlmRequest(
                    model = modelId,
                    messages = run.messages.value,
                    tools = tools.map { it.toDefinition() },
                )
                val result = provider.streamChat(request) { ev ->
                    when (ev) {
                        is LlmEvent.TextDelta -> run.appendText(ev.text)
                        is LlmEvent.ThinkingDelta -> run.appendThinking(ev.text)
                        is LlmEvent.ToolCallStart ->
                            eventBus.emit(EventKind.PROVIDER, modelId, "tool call from model: ${ev.name ?: "?"}")
                        is LlmEvent.ToolCallArgumentsDelta -> Unit
                    }
                }
                run.addMessage(ChatMessage.assistant(result.text, result.toolCalls))
                run.addUsage(result.usage.promptTokens, result.usage.completionTokens)
                eventBus.emit(
                    EventKind.MESSAGE,
                    modelId,
                    "assistant: ${truncate(result.text.ifEmpty { "…" })} " +
                        "(${result.usage.promptTokens}+${result.usage.completionTokens} tokens, " +
                        "${result.toolCalls.size} tool call(s))",
                )

                if (result.toolCalls.isEmpty()) {
                    run.complete()
                    return
                }

                for ((i, tc) in result.toolCalls.withIndex()) {
                    currentCoroutineContext().ensureActive() // stop request?
                    val lineId = "${run.id}-r$round-$i"
                    val spec = tools.firstOrNull { it.name == tc.name }
                    val summary = summarizeArgs(tc)

                    if (spec == null) {
                        run.addToolLine(ToolLine(lineId, tc.name, summary, ToolLine.Status.ERROR, "unknown tool"))
                        run.addMessage(ChatMessage.tool(tc.id, "Unknown tool: ${tc.name}. Available: ${tools.joinToString(", ") { it.name }}"))
                        continue
                    }

                    if (!approval.request(spec, tc.argumentsJson, summary)) {
                        run.addToolLine(ToolLine(lineId, tc.name, summary, ToolLine.Status.DENIED))
                        run.addMessage(ChatMessage.tool(tc.id, "The user denied this tool call. Do not retry it; ask what to do instead."))
                        continue
                    }

                    run.addToolLine(ToolLine(lineId, tc.name, summary, ToolLine.Status.RUNNING))
                    eventBus.emit(EventKind.TOOL, spec.name, "invoking: $summary", argsDetail(tc.argumentsJson))
                    val outcome = executor.execute(tc.name, tc.argumentsJson)
                    run.setToolLine(lineId) { line ->
                        line.copy(
                            status = if (outcome.ok) ToolLine.Status.OK else ToolLine.Status.ERROR,
                            detail = truncate(outcome.text),
                            extra = outcome.extra,
                        )
                    }
                    eventBus.emit(
                        EventKind.TOOL,
                        spec.name,
                        if (outcome.ok) "finished: ${truncate(outcome.text)}" else "failed: ${truncate(outcome.text)}",
                        outcomeDetail(outcome.extra),
                    )
                    run.addMessage(ChatMessage.tool(tc.id, outcome.text))
                }
            }
            eventBus.emit(EventKind.MESSAGE, modelId, "stopped: max tool rounds ($maxRounds) reached")
            run.complete()
        } catch (e: CancellationException) {
            // User stop: settle the run and propagate so the caller's job cancels.
            eventBus.emit(EventKind.MESSAGE, modelId, "turn stopped by user")
            run.cancel()
            throw e
        } catch (e: Exception) {
            eventBus.emit(EventKind.ERROR, modelId, "turn failed: ${e.message}")
            run.fail(e.message ?: e::class.simpleName ?: "error")
        }
    }

    /** Args for the console detail pane (full JSON, capped). */
    private fun argsDetail(argsJson: String): Map<String, String> =
        if (argsJson.isNotBlank() && argsJson != "{}") mapOf("args" to argsJson.take(4_000)) else emptyMap()

    /** File-path extras for the console detail pane. */
    private fun outcomeDetail(extra: Map<String, String>): Map<String, String> =
        extra.keys.filter { it in FILE_EXTRA_KEYS }.associateWith { extra.getValue(it).take(200) }

    companion object {
        const val MAX_ROUNDS = 12
        private val FILE_EXTRA_KEYS = setOf("path")
    }

    private fun summarizeArgs(tc: ToolCall): String {
        val args = try {
            json.parseToJsonElement(tc.argumentsJson).jsonObject
        } catch (_: Exception) {
            null
        }
        return if (args == null || args.isEmpty()) {
            tc.name
        } else {
            tc.name + "(" + args.entries.joinToString(", ") { (k, v) ->
                val s = v.toString()
                if (s.length > 60) s.take(57) + "…" else s
            } + ")"
        }
    }

    private fun truncate(s: String): String {
        val flat = s.replace(Regex("\\s+"), " ").trim()
        return if (flat.length > 160) flat.take(157) + "…" else flat
    }
}
