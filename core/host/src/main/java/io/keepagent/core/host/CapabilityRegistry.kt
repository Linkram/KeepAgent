package io.keepagent.core.host

import io.keepagent.addonsapi.ToolRegistration

/**
 * The capability registry — the only coupling between host and add-ons
 * (spec §5.3). Add-ons register; the host consumes. M0 implements the
 * `tool` capability; the others (llm.provider, test.runner, …) slot in as
 * their milestones land.
 */
class CapabilityRegistry {

    data class Entry(val addonId: String, val tool: ToolRegistration)

    private val tools = linkedMapOf<String, Entry>()
    private val lock = Any()

    fun registerTool(addonId: String, tool: ToolRegistration) {
        synchronized(lock) { tools[tool.name] = Entry(addonId, tool) }
    }

    fun unregisterAddon(addonId: String) {
        synchronized(lock) {
            tools.entries.removeAll { it.value.addonId == addonId }
        }
    }

    fun tool(name: String): Entry? = synchronized(lock) { tools[name] }

    fun tools(): List<Entry> = synchronized(lock) { tools.values.toList() }
}
