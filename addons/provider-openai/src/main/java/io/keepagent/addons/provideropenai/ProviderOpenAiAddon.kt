package io.keepagent.addons.provideropenai

import io.keepagent.addonsapi.AddonManifest
import io.keepagent.addonsapi.Capabilities
import io.keepagent.addonsapi.Permissions
import io.keepagent.addonsapi.llm.LlmEvent
import io.keepagent.addonsapi.llm.LlmException
import io.keepagent.addonsapi.llm.LlmModel
import io.keepagent.addonsapi.llm.LlmProvider
import io.keepagent.addonsapi.llm.LlmRequest
import io.keepagent.addonsapi.llm.LlmResult
import io.keepagent.core.events.EventBus
import io.keepagent.core.events.EventKind
import io.keepagent.core.host.Tier1Addon
import io.keepagent.core.host.Tier1Host
import io.keepagent.core.llm.OpenAiCompatibleClient
import io.keepagent.core.settings.SettingsStore
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Tier-1 add-on registering an OpenAI-compatible provider (F-001).
 * Any endpoint speaking the OpenAI chat-completions wire format works:
 * OpenAI, DeepSeek, OpenRouter, Ollama, vLLM, local gateways…
 *
 * Endpoint, key, and model are user settings (the model profile); the
 * provider re-reads them on every use so changes apply without a restart.
 * Each request/response is logged to the console (latency + tokens; full
 * request bodies only when the verbose LLM log is enabled).
 */
class ProviderOpenAiAddon(
    private val settings: SettingsStore,
    private val eventBus: EventBus,
) : Tier1Addon {

    override val manifest = AddonManifest(
        id = "io.keepagent.provider.openai",
        name = "OpenAI-compatible provider",
        version = "0.1.0",
        apiVersion = 1,
        tier = 1,
        entry = "kotlin",
        permissions = listOf(Permissions.NETWORK),
        provides = listOf(Capabilities.LLM_PROVIDER),
    )

    override fun initialize(host: Tier1Host) {
        host.registerProvider(ConfiguredOpenAiProvider(settings, eventBus))
    }

    /**
     * An [LlmProvider] whose endpoint is the user's model profile.
     * The underlying HTTP client is cached per (baseUrl, apiKey, timeout)
     * so repeated turns don't churn connections.
     */
    class ConfiguredOpenAiProvider(
        private val settings: SettingsStore,
        private val eventBus: EventBus,
    ) : LlmProvider {

        override val id = "openai-compatible"

        private var client: OpenAiCompatibleClient? = null
        private var clientKey: String? = null

        override suspend fun listModels(): List<LlmModel> = client().listModels()

        override suspend fun streamChat(
            request: LlmRequest,
            onEvent: suspend (LlmEvent) -> Unit,
        ): LlmResult {
            val maxRetries = settings.getString(NS_MODEL, "maxRetries")?.toIntOrNull()?.coerceIn(0, 5) ?: 1
            val verbose = settings.getString(NS_GENERAL, "llmLogVerbose") == "true"
            var attempt = 0
            while (true) {
                attempt++
                var gotEvent = false
                val started = System.currentTimeMillis()
                eventBus.emit(
                    EventKind.PROVIDER,
                    id,
                    "request: model=${request.model} messages=${request.messages.size} tools=${request.tools.size}" +
                        (if (attempt > 1) " (retry $attempt)" else ""),
                    if (verbose) mapOf("body" to describe(request)) else emptyMap(),
                )
                try {
                    val result = client().streamChat(request) { ev ->
                        gotEvent = true
                        onEvent(ev)
                    }
                    val ms = System.currentTimeMillis() - started
                    val u = result.usage
                    eventBus.emit(
                        EventKind.PROVIDER,
                        id,
                        "response: ok in ${ms}ms, tokens ${u.promptTokens}+${u.completionTokens}, finish=${result.finishReason ?: "—"}",
                    )
                    return result
                } catch (e: LlmException) {
                    val ms = System.currentTimeMillis() - started
                    val status = e.httpStatus
                    val retryable = !gotEvent &&
                        (status == null || status == 429 || status >= 500) &&
                        attempt <= maxRetries
                    eventBus.emit(
                        EventKind.PROVIDER,
                        id,
                        "response: error in ${ms}ms: ${e.message}" + (if (retryable) " (will retry)" else ""),
                    )
                    if (!retryable) throw e
                    delay(1000L * attempt)
                }
            }
        }

        override suspend fun close() {
            client?.close()
            client = null
            clientKey = null
        }

        /** Compact request description for the verbose console log. */
        private fun describe(request: LlmRequest): String {
            val roles = request.messages.joinToString(",") { it.role.name.lowercase() }
            return "model=${request.model} temperature=${request.temperature} maxTokens=${request.maxTokens} [${roles}]"
        }

        private fun client(): OpenAiCompatibleClient {
            val baseUrl = settings.getString(SettingsStore.NS_MODEL, "baseUrl")?.trim() ?: ""
            val apiKey = settings.getString(SettingsStore.NS_MODEL, "apiKey")?.trim() ?: ""
            val timeoutSec = settings.getString(NS_MODEL, "timeoutSeconds")?.toIntOrNull()?.coerceIn(30, 3600) ?: 600
            if (baseUrl.isEmpty()) {
                throw LlmException(
                    "No model configured — set the base URL, API key, and model in the model profile.",
                )
            }
            val key = "$baseUrl|$apiKey|$timeoutSec"
            if (client == null || clientKey != key) {
                val http = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                    .build()
                client = OpenAiCompatibleClient(baseUrl = baseUrl, apiKey = apiKey, client = http)
                clientKey = key
            }
            return client!!
        }
    }

    companion object {
        private const val NS_MODEL = SettingsStore.NS_MODEL
        private const val NS_GENERAL = SettingsStore.NS_GENERAL
    }
}
