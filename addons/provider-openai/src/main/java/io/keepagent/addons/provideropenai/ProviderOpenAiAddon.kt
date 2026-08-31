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
import io.keepagent.core.host.Tier1Addon
import io.keepagent.core.host.Tier1Host
import io.keepagent.core.llm.OpenAiCompatibleClient
import io.keepagent.core.settings.SettingsStore

/**
 * Tier-1 add-on registering an OpenAI-compatible provider (F-001).
 * Any endpoint speaking the OpenAI chat-completions wire format works:
 * OpenAI, DeepSeek, OpenRouter, Ollama, vLLM, local gateways…
 *
 * Endpoint, key, and model are user settings (the model profile); the
 * provider re-reads them on every use so changes apply without a restart.
 */
class ProviderOpenAiAddon(private val settings: SettingsStore) : Tier1Addon {

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
        host.registerProvider(ConfiguredOpenAiProvider(settings))
    }

    /**
     * An [LlmProvider] whose endpoint is the user's model profile.
     * The underlying HTTP client is cached per (baseUrl, apiKey) so
     * repeated turns don't churn connections.
     */
    class ConfiguredOpenAiProvider(private val settings: SettingsStore) : LlmProvider {

        override val id = "openai-compatible"

        private var client: OpenAiCompatibleClient? = null
        private var clientKey: String? = null

        override suspend fun listModels(): List<LlmModel> = client().listModels()

        override suspend fun streamChat(
            request: LlmRequest,
            onEvent: suspend (LlmEvent) -> Unit,
        ): LlmResult = client().streamChat(request, onEvent)

        override suspend fun close() {
            client?.close()
            client = null
            clientKey = null
        }

        private fun client(): OpenAiCompatibleClient {
            val baseUrl = settings.getString(SettingsStore.NS_MODEL, "baseUrl")?.trim() ?: ""
            val apiKey = settings.getString(SettingsStore.NS_MODEL, "apiKey")?.trim() ?: ""
            if (baseUrl.isEmpty()) {
                throw LlmException(
                    "No model configured — set the base URL, API key, and model in the model profile.",
                )
            }
            val key = "$baseUrl|$apiKey"
            if (client == null || clientKey != key) {
                client = OpenAiCompatibleClient(baseUrl = baseUrl, apiKey = apiKey)
                clientKey = key
            }
            return client!!
        }
    }
}
