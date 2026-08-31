package io.keepagent.core.llm

import io.keepagent.addonsapi.llm.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for any OpenAI-compatible `/chat/completions` endpoint (OpenAI,
 * OpenRouter, vLLM, Ollama, local servers). SSE streaming, tool calls,
 * images (data-URL parts), reasoning tokens (`reasoning_content` / `reasoning`),
 * and final-usage reporting.
 */
class OpenAiCompatibleClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient(),
    /** When the endpoint has no `GET /models`, these are reported instead. */
    private val knownModels: List<LlmModel> = emptyList(),
) : LlmProvider {

    override val id: String = "openai-compatible"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun listModels(): List<LlmModel> {
        if (knownModels.isNotEmpty()) return knownModels
        return try {
            fetchModels()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * `GET /models` without swallowing errors — the connection-test path.
     * Throws [IOException] on network failure or a non-2xx response
     * (`"HTTP 401"`), so callers can surface the real problem.
     */
    suspend fun fetchModels(): List<LlmModel> {
        val url = rootUrl() + "/models"
        val req = Request.Builder().url(url).get().auth().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val root = json.parseToJsonElement(body).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()
            return data.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                LlmModel(id = id, name = id, acceptsImages = id.contains("vision", ignoreCase = true))
            }
        }
    }

    override suspend fun streamChat(
        request: LlmRequest,
        onEvent: suspend (LlmEvent) -> Unit,
    ): LlmResult = withContext(Dispatchers.IO) {
        val state = State()
        val text = StringBuilder()
        val thinking = StringBuilder()
        // index -> [id, name, arguments]
        val toolCalls = sortedMapOf<Int, MutableList<String>>()

        try {
            val url = rootUrl() + "/chat/completions"
            val body = buildBody(request)
            val req = Request.Builder()
                .url(url)
                .post(body.toString().toRequestBody(jsonMediaType))
                .header("Accept", "text/event-stream")
                .auth()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = runCatching { resp.body?.string().orEmpty() }.getOrDefault("")
                    throw LlmException(
                        "LLM HTTP ${resp.code}: ${errBody.take(500)}",
                        httpStatus = resp.code,
                    )
                }
                val source = resp.body?.source() ?: throw LlmException("Empty LLM response body")
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.isEmpty() || !line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    if (payload.isEmpty()) continue
                    handleChunk(
                        chunk = json.parseToJsonElement(payload).jsonObject,
                        onEvent = onEvent,
                        text = text,
                        thinking = thinking,
                        toolCalls = toolCalls,
                        state = state,
                    )
                }
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: IOException) {
            throw LlmException("LLM network error: ${e.message}", cause = e)
        }

        val calls = toolCalls.map { (idx, parts) ->
            ToolCall(
                id = parts[0].ifEmpty { "call_$idx" },
                name = parts[1],
                argumentsJson = parts[2].ifEmpty { "{}" },
            )
        }
        LlmResult(
            text = text.toString(),
            thinking = thinking.toString(),
            toolCalls = calls,
            usage = state.usage,
            finishReason = state.finishReason,
        )
    }

    override suspend fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    // --- internals -----------------------------------------------------------

    /** Mutable holder so handleChunk can update it without being generic. */
    private class State {
        var usage = LlmUsage()
        var finishReason: String? = null
    }

    private fun rootUrl(): String = baseUrl.trimEnd('/')

    private fun Request.Builder.auth(): Request.Builder = apply {
        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
    }

    private fun buildBody(req: LlmRequest): JsonObject = buildJsonObject {
        put("model", req.model)
        put("stream", true)
        putJsonObject("stream_options") { put("include_usage", true) }
        req.temperature?.let { put("temperature", it) }
        req.maxTokens?.let { put("max_tokens", it) }

        putJsonArray("messages") {
            for (m in req.messages) {
                addJsonObject {
                    put("role", when (m.role) {
                        ChatRole.SYSTEM -> "system"
                        ChatRole.USER -> "user"
                        ChatRole.ASSISTANT -> "assistant"
                        ChatRole.TOOL -> "tool"
                    })
                    when {
                        m.role == ChatRole.TOOL -> {
                            put("tool_call_id", m.toolCallId.orEmpty())
                            put("content", m.content)
                        }
                        m.images.isNotEmpty() && m.role == ChatRole.USER -> {
                            putJsonArray("content") {
                                if (m.content.isNotBlank()) {
                                    addJsonObject {
                                        put("type", "text")
                                        put("text", m.content)
                                    }
                                }
                                for (img in m.images) {
                                    addJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") {
                                            put("url", "data:${img.mimeType};base64,${img.dataBase64}")
                                        }
                                    }
                                }
                            }
                        }
                        else -> put("content", m.content)
                    }
                    if (m.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") {
                            for (tc in m.toolCalls) {
                                addJsonObject {
                                    put("id", tc.id)
                                    put("type", "function")
                                    putJsonObject("function") {
                                        put("name", tc.name)
                                        put("arguments", tc.argumentsJson)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (req.tools.isNotEmpty()) {
            putJsonArray("tools") {
                for (t in req.tools) {
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", t.name)
                            put("description", t.description)
                            val params = t.parametersJson.ifBlank { "{}" }
                            put("parameters", json.parseToJsonElement(params))
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleChunk(
        chunk: JsonObject,
        onEvent: suspend (LlmEvent) -> Unit,
        text: StringBuilder,
        thinking: StringBuilder,
        toolCalls: MutableMap<Int, MutableList<String>>,
        state: State,
    ) {
        val choice = chunk["choices"]?.jsonArray?.firstOrNull()?.jsonObject
        val delta = choice?.get("delta")?.jsonObject

        delta?.get("content")?.jsonPrimitive?.contentOrNull?.let {
            if (it.isNotEmpty()) {
                text.append(it)
                onEvent(LlmEvent.TextDelta(it))
            }
        }
        val reasoning = delta
            ?.get("reasoning_content")?.jsonPrimitive?.contentOrNull
            ?: delta?.get("reasoning")?.jsonPrimitive?.contentOrNull
        if (!reasoning.isNullOrEmpty()) {
            thinking.append(reasoning)
            onEvent(LlmEvent.ThinkingDelta(reasoning))
        }

        delta?.get("tool_calls")?.jsonArray?.forEach { tcEl ->
            val tc = tcEl.jsonObject
            val idx = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
            val isNew = !toolCalls.containsKey(idx)
            val slot = toolCalls.getOrPut(idx) { mutableListOf("", "", "") }
            val id = tc["id"]?.jsonPrimitive?.contentOrNull
            val name = tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            if (isNew) onEvent(LlmEvent.ToolCallStart(idx, id, name))
            id?.let { slot[0] = it }
            name?.let { slot[1] = it }
            tc["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull?.let {
                slot[2] += it
                onEvent(LlmEvent.ToolCallArgumentsDelta(idx, it))
            }
        }

        chunk["usage"]?.jsonObject?.let { u ->
            state.usage = LlmUsage(
                promptTokens = u["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                completionTokens = u["completion_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
            )
        }
        choice?.get("finish_reason")?.jsonPrimitive?.contentOrNull?.let { state.finishReason = it }
    }
}

private fun defaultClient() = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .writeTimeout(60, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.MINUTES) // agent turns can stream for a long time
    .build()
