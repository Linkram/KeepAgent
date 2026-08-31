package io.keepagent.addonsapi.llm

/**
 * LLM conversation + provider types — part of the Addon API v1 contract.
 * Provider add-ons (Tier-1 Kotlin, and Tier-2 in M2+) code against these.
 */

/** Conversation roles, mapped to the OpenAI wire format (tool role stays "tool"). */
enum class ChatRole { SYSTEM, USER, ASSISTANT, TOOL }

/** An image attachment inside a user message (base64, data-URL style). */
data class ImagePart(
    val mimeType: String,
    val dataBase64: String,
)

/** A tool call requested by the model. */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/** A tool the agent can call this turn (OpenAI "function" shape). */
data class ToolDefinition(
    val name: String,
    val description: String,
    /** JSON-Schema object for the tool's arguments, as a JSON string. */
    val parametersJson: String,
)

/** One message in the conversation. */
data class ChatMessage(
    val role: ChatRole,
    val content: String,
    val images: List<ImagePart> = emptyList(),
    val toolCallId: String? = null, // TOOL messages: which call this answers
    val toolCalls: List<ToolCall> = emptyList(), // ASSISTANT messages: requested calls
) {
    companion object {
        fun system(text: String) = ChatMessage(ChatRole.SYSTEM, text)
        fun user(text: String, images: List<ImagePart> = emptyList()) =
            ChatMessage(ChatRole.USER, text, images)
        fun assistant(text: String, toolCalls: List<ToolCall> = emptyList()) =
            ChatMessage(ChatRole.ASSISTANT, text, toolCalls = toolCalls)
        fun tool(callId: String, output: String) =
            ChatMessage(ChatRole.TOOL, output, toolCallId = callId)
    }
}

/** Model metadata surfaced by a provider. */
data class LlmModel(
    val id: String,
    val name: String,
    val contextWindow: Int = 32_768,
    val acceptsImages: Boolean = false,
    val supportsTools: Boolean = true,
    val supportsThinking: Boolean = false,
)

data class LlmRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
)

/** Incremental events while a chat completion streams. */
sealed interface LlmEvent {
    data class TextDelta(val text: String) : LlmEvent
    /** Reasoning/thinking tokens (e.g. `reasoning_content` on Qwen thinking models). */
    data class ThinkingDelta(val text: String) : LlmEvent
    data class ToolCallStart(val index: Int, val id: String?, val name: String?) : LlmEvent
    data class ToolCallArgumentsDelta(val index: Int, val chunk: String) : LlmEvent
}

data class LlmUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
)

/** The completed result of one streamed completion. */
data class LlmResult(
    val text: String,
    val thinking: String,
    val toolCalls: List<ToolCall>,
    val usage: LlmUsage,
    val finishReason: String?,
)

class LlmException(
    message: String,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
