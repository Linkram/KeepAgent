package io.keepagent.core.host

import android.content.Context
import io.keepagent.addonsapi.ToolRegistration
import io.keepagent.core.events.EventBus
import io.keepagent.core.events.EventKind
import io.keepagent.core.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Owns the add-on lifecycle (spec §5.4):
 * discover → validate → enable → initialize → running.
 *
 * M0 runs Tier-2 add-ons in-process in the quickjs-ng sandbox (spec §13,
 * ADR-0001); separate-process isolation + IPC lands in M1.
 */
class AddonManager(
    private val context: Context,
    private val addonsDir: File,
    private val eventBus: EventBus,
    private val settings: SettingsStore,
    private val registry: CapabilityRegistry = CapabilityRegistry(),
    private val runtimeFactory: (addonId: String, workspacePath: String) -> AddonRuntime,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    private val records = linkedMapOf<String, AddonRecord>()
    private val runtimes = linkedMapOf<String, AddonRuntime>()

    private val _records = MutableStateFlow<List<AddonRecord>>(emptyList())
    /** Live view for the UI (Add-ons tab). */
    val recordsFlow: StateFlow<List<AddonRecord>> = _records.asStateFlow()

    val registryRef: CapabilityRegistry get() = registry

    /** Seeds samples, discovers, validates, and initializes. Run off the main thread. */
    fun start(workspacePath: String) {
        val repository = AddonRepository(addonsDir)
        repository.seedFromAssets(context.assets)
        val discovered = repository.discover()
        discovered.forEach { record ->
            records[record.dirName] = record
            when {
                record.manifest == null || record.validationErrors.isNotEmpty() ->
                    eventBus.emit(
                        EventKind.ADDON,
                        "host",
                        "add-on ${record.dirName} invalid: " +
                            (record.validationErrors.firstOrNull() ?: "unknown"),
                    )
                else ->
                    eventBus.emit(
                        EventKind.ADDON,
                        "host",
                        "add-on ${record.manifest.id} v${record.manifest.version} discovered (tier ${record.manifest.tier})",
                    )
            }
        }
        refreshRecords()
        records.values
            .filter { it.status == AddonStatus.VALID }
            .forEach { enable(it, workspacePath) }
        refreshRecords()
        eventBus.emit(
            EventKind.SYSTEM,
            "host",
            "plugin host ready: ${records.values.count { it.status == AddonStatus.INITIALIZED }} initialized, ${records.size} discovered",
        )
    }

    private fun enable(record: AddonRecord, workspacePath: String) {
        val manifest = record.manifest ?: return
        record.status = AddonStatus.ENABLED
        eventBus.emit(EventKind.ADDON, "host", "add-on ${manifest.id} enabled")
        when (manifest.tier) {
            2 -> initializeTier2(record, workspacePath)
            else -> {
                record.status = AddonStatus.UNAVAILABLE
                record.statusDetail =
                    "Tier ${manifest.tier} not implemented in M0 (in-process Kotlin modules and external connections land in M1–M3)"
                eventBus.emit(EventKind.ADDON, "host", "add-on ${manifest.id} pending: ${record.statusDetail}")
            }
        }
        refreshRecords()
    }

    private fun initializeTier2(record: AddonRecord, workspacePath: String) {
        val manifest = record.manifest ?: return
        val addonDir = File(addonsDir, record.dirName)
        val entryFile = File(addonDir, manifest.entry)
        if (!entryFile.exists()) {
            record.status = AddonStatus.FAILED
            record.statusDetail = "entry file missing: ${manifest.entry}"
            eventBus.emit(EventKind.ERROR, "host", "add-on ${manifest.id}: ${record.statusDetail}")
            refreshRecords()
            return
        }
        val runtime = runtimeFactory(manifest.id, workspacePath)
        if (!runtime.initialize()) {
            record.status = AddonStatus.UNAVAILABLE
            record.statusDetail = "JS runtime unavailable (native library not built — see README)"
            eventBus.emit(EventKind.ADDON, "host", "add-on ${manifest.id} deferred: ${record.statusDetail}")
            refreshRecords()
            return
        }
        val error = runtime.evaluate(entryFile.readText(), manifest.entry)
        if (error != null) {
            record.status = AddonStatus.FAILED
            record.statusDetail = error
            eventBus.emit(EventKind.ERROR, "host", "add-on ${manifest.id} init failed: $error")
            refreshRecords()
            return
        }
        runtimes[record.dirName] = runtime
        record.status = AddonStatus.INITIALIZED
        eventBus.emit(EventKind.ADDON, "host", "add-on ${manifest.id} initialized in quickjs-ng sandbox")
        refreshRecords()
    }

    /** Called by the JS bridge when an add-on registers a tool. */
    fun onToolRegistered(addonId: String, specJson: String) {
        val tool = runCatching { json.decodeFromString(ToolRegistration.serializer(), specJson) }
            .getOrNull() ?: return
        registry.registerTool(addonId, tool)
        records.values
            .firstOrNull { it.id == addonId }
            ?.apply {
                if (tool.name !in tools) tools.add(tool.name)
                refreshRecords()
            }
        eventBus.emit(EventKind.TOOL, addonId, "tool '${tool.name}' registered (${tool.permission.name.lowercase()})")
    }

    /**
     * Invokes a registered tool by name. Returns the JSON result string
     * produced by the sandbox, or an error JSON.
     */
    fun invokeTool(name: String, argsJson: String): String {
        val entry = registry.tool(name)
            ?: return """{"ok":false,"error":"no such tool: $name"}"""
        val record = records.values.firstOrNull { it.id == entry.addonId }
            ?: return """{"ok":false,"error":"add-on offline"}"""
        val runtime = runtimes[record.dirName]
            ?: return """{"ok":false,"error":"add-on runtime not active"}"""
        eventBus.emit(EventKind.TOOL, entry.addonId, "tool '$name' invoked")
        val result = runtime.invokeTool(name, argsJson)
        eventBus.emit(EventKind.TOOL, entry.addonId, "tool '$name' finished")
        return result
    }

    private fun refreshRecords() {
        _records.value = records.values.toList()
    }
}
