package io.keepagent.core.agent

import io.keepagent.addonsapi.llm.ChatMessage
import io.keepagent.addonsapi.llm.ChatRole
import io.keepagent.addonsapi.llm.LlmProvider
import io.keepagent.addonsapi.llm.LlmRequest

/** Summarizes old transcript content before the next request crosses 80%. */
class ContextCompactor(
    private val provider: LlmProvider,
    private val modelId: String,
    private val contextLimit: Int,
) {
    data class Result(val messages: List<ChatMessage>, val summarizedMessages: Int)

    suspend fun compactIfNeeded(
        messages: List<ChatMessage>,
        toolDefinitionTokens: Int = 0,
        providerUsageHint: Int = 0,
    ): Result? {
        if (contextLimit <= 0 || messages.size < 3) return null
        val used = maxOf(estimateTokens(messages) + toolDefinitionTokens, providerUsageHint)
        if (used.toLong() * 100 < contextLimit.toLong() * COMPACT_AT_PERCENT) return null

        val system = messages.firstOrNull()?.takeIf { it.role == ChatRole.SYSTEM }
        val bodyStart = if (system == null) 0 else 1
        var keepFrom = messages.size - 1
        var keptTokens = estimateTokens(messages.subList(keepFrom, messages.size))
        val keepBudget = (contextLimit.toLong() * KEEP_RECENT_PERCENT / 100)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        while (keepFrom > bodyStart && keptTokens < keepBudget) {
            val candidate = keepFrom - 1
            val next = estimateTokens(messages[candidate])
            if (keptTokens + next > keepBudget && keepFrom < messages.size - 1) break
            keepFrom = candidate
            keptTokens += next
        }
        // Never retain an orphaned tool result without its assistant tool call.
        while (keepFrom > bodyStart && messages[keepFrom].role == ChatRole.TOOL) keepFrom--
        if (keepFrom <= bodyStart) return null

        val old = messages.subList(bodyStart, keepFrom)
        val transcript = old.joinToString("\n\n") { message ->
            val calls = message.toolCalls.joinToString { "${it.name}(${it.argumentsJson})" }
            "${message.role.name}: ${message.content}" + if (calls.isBlank()) "" else "\nTOOL CALLS: $calls"
        }
        val summaryRequest = LlmRequest(
            model = modelId,
            messages = listOf(
                ChatMessage.system(
                    "Compress the conversation into a precise working-memory summary. " +
                        "Preserve user requirements, decisions, file paths, edits, tool results, errors, " +
                        "and unresolved work. Do not add commentary.",
                ),
                ChatMessage.user(transcript),
            ),
            maxTokens = minOf(4_096, maxOf(512, contextLimit / 10)),
        )
        val summary = provider.streamChat(summaryRequest) { }.text.trim()
        if (summary.isEmpty()) return null
        val compacted = buildList {
            if (system != null) add(system)
            add(ChatMessage.system("Summary of earlier conversation:\n$summary"))
            addAll(messages.subList(keepFrom, messages.size))
        }
        return Result(compacted, old.size)
    }

    companion object {
        const val COMPACT_AT_PERCENT = 80
        private const val KEEP_RECENT_PERCENT = 30

        fun estimateTokens(messages: List<ChatMessage>): Int =
            messages.sumOf(::estimateTokens)

        fun estimateTokens(message: ChatMessage): Int {
            val toolChars = message.toolCalls.sumOf { it.name.length + it.argumentsJson.length }
            // Provider image tokenization varies. Reserve a conservative fixed budget per image;
            // base64 byte length itself is not the prompt token count.
            return 4 + (message.content.length + toolChars + 3) / 4 + message.images.size * IMAGE_TOKEN_RESERVE
        }

        const val IMAGE_TOKEN_RESERVE = 1_024
    }
}
