package io.keepagent.core.host

import io.keepagent.addonsapi.ToolRegistration
import io.keepagent.addonsapi.llm.LlmProvider

/**
 * The capability registry — the only coupling between host and add-ons
 * (spec §5.3). Add-ons register; the host consumes. M1 implements the
 * `tool` and `llm.provider` capabilities; the others (test.runner,
 * dev.toolchain, …) slot in as their milestones land.
 */
class CapabilityRegistry {

    /**
     * A registered tool. [handler] is non-null for Tier-1 tools (executed
     * in-process, takes args JSON, returns result JSON); null for Tier-2
     * tools (routed to the sandbox runtime by name).
     */
    data class Entry(
        val addonId: String,
        val tool: ToolRegistration,
        val handler: ((String) -> String)? = null,
    )
    data class ProviderEntry(val addonId: String, val provider: LlmProvider)

    private val tools = linkedMapOf<String, Entry>()
    private val providers = linkedMapOf<String, ProviderEntry>()
    private val lock = Any()

    fun registerTool(
        addonId: String,
        tool: ToolRegistration,
        handler: ((String) -> String)? = null,
    ) {
        synchronized(lock) { tools[tool.name] = Entry(addonId, tool, handler) }
    }

    fun unregisterAddon(addonId: String) {
        synchronized(lock) {
            tools.entries.removeAll { it.value.addonId == addonId }
            providers.entries.removeAll { it.value.addonId == addonId }
        }
    }

    fun tool(name: String): Entry? = synchronized(lock) { tools[name] }

    fun tools(): List<Entry> = synchronized(lock) { tools.values.toList() }

    fun registerProvider(addonId: String, provider: LlmProvider) {
        synchronized(lock) { providers[provider.id] = ProviderEntry(addonId, provider) }
    }

    fun provider(id: String): LlmProvider? =
        synchronized(lock) { providers[id]?.provider }

    fun providers(): List<ProviderEntry> = synchronized(lock) { providers.values.toList() }
}
