package io.keepagent.core.host

import android.content.Context
import io.keepagent.addonsapi.AddonManifest
import io.keepagent.addonsapi.ToolRegistration
import io.keepagent.addonsapi.llm.LlmProvider
import io.keepagent.addonsapi.validate
import io.keepagent.core.events.EventBus
import io.keepagent.core.events.EventKind
import io.keepagent.core.settings.SettingsStore
import io.keepagent.core.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Owns the add-on lifecycle (spec §5.4):
 * discover → validate → enable → initialize → running.
 *
 * M1 runs Tier-1 add-ons in-process (compiled into the APK, spec §5.1) and
 * Tier-2 add-ons in the quickjs-ng sandbox (spec §13, ADR-0001); the sandbox
 * moves to a helper process over IPC in this milestone.
 */
class AddonManager(
    private val context: Context,
    private val addonsDir: File,
    private val eventBus: EventBus,
    private val settings: SettingsStore,
    private val storage: Storage,
    private val registry: CapabilityRegistry = CapabilityRegistry(),
    private val runtimeFactory: (addonId: String, workspacePath: String) -> AddonRuntime,
    private val tier1Addons: List<Tier1Addon> = emptyList(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    private val records = linkedMapOf<String, AddonRecord>()
    private val runtimes = linkedMapOf<String, AddonRuntime>()

    /** Workspace root captured at [start], reused by runtime (re)initialization. */
    @Volatile
    private var workspacePath = ""

    /** Add-on ids the user disabled (persisted, restored on next start). */
    private fun disabledSet(): MutableSet<String> {
        val raw = settings.getString(SettingsStore.NS_ADDONS, "disabled").orEmpty()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
    }

    private fun persistDisabled(id: String, disabled: Boolean) {
        val set = disabledSet()
        if (disabled) set.add(id) else set.remove(id)
        settings.setString(SettingsStore.NS_ADDONS, "disabled", set.joinToString(","))
    }

    private val _records = MutableStateFlow<List<AddonRecord>>(emptyList())
    /** Live view for the UI (Add-ons tab). */
    val recordsFlow: StateFlow<List<AddonRecord>> = _records.asStateFlow()

    /**
     * UI-triggered lifecycle work (enable/disable) runs here, off the main
     * thread — see [setAddonEnabled] for why the main thread must stay free.
     */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val registryRef: CapabilityRegistry get() = registry

    /** Seeds samples, discovers, validates, and initializes. Run off the main thread. */
    fun start(workspacePath: String) {
        this.workspacePath = workspacePath
        initializeTier1(workspacePath)
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
        val disabled = disabledSet()
        records.values
            .filter { it.status == AddonStatus.VALID }
            .forEach { record ->
                if (record.id in disabled) {
                    record.status = AddonStatus.DISABLED
                    record.statusDetail = "disabled by user"
                    eventBus.emit(EventKind.ADDON, "host", "add-on ${record.id} skipped (disabled by user)")
                } else {
                    enable(record, workspacePath)
                }
            }
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
                    "Tier ${manifest.tier} unavailable in M1 (external connections over loopback land in M3)"
                eventBus.emit(EventKind.ADDON, "host", "add-on ${manifest.id} pending: ${record.statusDetail}")
            }
        }
        refreshRecords()
    }

    private fun initializeTier1(workspacePath: String) {
        tier1Addons.forEach { addon ->
            val manifest = addon.manifest
            val record = AddonRecord(
                dirName = BUILTIN_PREFIX + manifest.id,
                manifest = manifest,
                validationErrors = manifest.validate().errors,
                status = AddonStatus.DISCOVERED,
            )
            records[record.dirName] = record
            if (record.validationErrors.isNotEmpty()) {
                record.status = AddonStatus.INVALID
                eventBus.emit(
                    EventKind.ADDON,
                    "host",
                    "built-in add-on ${manifest.id} invalid: " + record.validationErrors.firstOrNull(),
                )
                refreshRecords()
                return@forEach
            }
            eventBus.emit(
                EventKind.ADDON,
                "host",
                "built-in add-on ${manifest.id} v${manifest.version} discovered (tier 1, in-process)",
            )
            if (manifest.id in disabledSet()) {
                record.status = AddonStatus.DISABLED
                record.statusDetail = "disabled by user"
                eventBus.emit(EventKind.ADDON, "host", "add-on ${manifest.id} skipped (disabled by user)")
                refreshRecords()
                return@forEach
            }
            initTier1(addon, workspacePath, record)
        }
    }

    /** (Re)initializes one Tier-1 add-on into the registry. */
    private fun initTier1(addon: Tier1Addon, wsPath: String, record: AddonRecord) {
        val manifest = addon.manifest
        record.status = AddonStatus.ENABLED
        record.statusDetail = null
        try {
            addon.initialize(Tier1HostImpl(manifest, wsPath))
            record.status = AddonStatus.INITIALIZED
            record.tools.clear()
            record.tools.addAll(
                registry.tools()
                    .filter { it.addonId == manifest.id }
                    .map { it.tool.name },
            )
            eventBus.emit(EventKind.ADDON, "host", "add-on ${manifest.id} initialized in-process")
        } catch (e: Exception) {
            record.status = AddonStatus.FAILED
            record.statusDetail = e.message
            eventBus.emit(EventKind.ERROR, "host", "add-on ${manifest.id} init failed: ${e.message}")
        }
        refreshRecords()
    }

    /**
     * User toggles an add-on on/off. Disabling removes its tools + provider
     * from the registry (and shuts down its sandbox, if any); enabling
     * re-runs initialization. The choice persists across app restarts.
     *
     * Runs on an IO dispatcher: Tier-2 enabling binds the `:js` helper
     * service, whose `ServiceConnection.onServiceConnected` is delivered on
     * the *main* looper. Blocking the main thread on the bind latch (the
     * M1.4 behavior) deadlocked against that delivery — 5 s input stall,
     * ANR, app restart, guaranteed in-process fallback (2026-09-03 device
     * test). Off the main thread the looper stays free to deliver the
     * connection. UI state lands via the records StateFlow.
     */
    fun setAddonEnabled(id: String, enabled: Boolean) {
        ioScope.launch { setAddonEnabledLocked(id, enabled) }
    }

    @Synchronized
    private fun setAddonEnabledLocked(id: String, enabled: Boolean) {
        val record = records.values.firstOrNull { it.id == id || it.dirName == id } ?: return
        val manifest = record.manifest ?: return
        if (record.status == AddonStatus.INVALID) return
        if (!enabled) {
            registry.unregisterAddon(manifest.id)
            runtimes[record.dirName]?.let { runCatching { it.shutdown() } }
            runtimes.remove(record.dirName)
            record.tools.clear()
            record.status = AddonStatus.DISABLED
            record.statusDetail = "disabled by user"
            persistDisabled(manifest.id, disabled = true)
            eventBus.emit(EventKind.ADDON, "host", "add-on ${manifest.id} disabled by user")
        } else {
            persistDisabled(manifest.id, disabled = false)
            when (manifest.tier) {
                1 -> {
                    val addon = tier1Addons.firstOrNull { it.manifest.id == manifest.id }
                    if (addon == null) {
                        record.status = AddonStatus.FAILED
                        record.statusDetail = "built-in add-on instance not found"
                    } else {
                        initTier1(addon, workspacePath, record)
                    }
                }
                2 -> enable(record, workspacePath)
                else -> {
                    record.status = AddonStatus.UNAVAILABLE
                    record.statusDetail = "Tier ${manifest.tier} unavailable in M1 (external connections over loopback land in M3)"
                }
            }
            eventBus.emit(EventKind.ADDON, "host", "add-on ${manifest.id} enabled by user")
        }
        refreshRecords()
    }

    private inner class Tier1HostImpl(
        private val manifest: AddonManifest,
        private val wsPath: String,
    ) : Tier1Host {
        override val context: Context get() = this@AddonManager.context
        override val eventBus: EventBus get() = this@AddonManager.eventBus
        override val settings: SettingsStore get() = this@AddonManager.settings
        override val storage: Storage get() = this@AddonManager.storage
        override val workspacePath: String get() = wsPath

        override fun registerTool(tool: ToolRegistration, handler: (String) -> String) {
            registry.registerTool(manifest.id, tool, handler)
            eventBus.emit(
                EventKind.TOOL,
                manifest.id,
                "tool '${tool.name}' registered (${tool.permission.name.lowercase()})",
            )
        }

        override fun registerProvider(provider: LlmProvider) {
            registry.registerProvider(manifest.id, provider)
            eventBus.emit(EventKind.ADDON, manifest.id, "provider '${provider.id}' registered")
        }
    }

    companion object {
        const val BUILTIN_PREFIX = "builtin:"
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
        record.statusDetail = null
        eventBus.emit(EventKind.ADDON, "host", "add-on ${manifest.id} initialized in quickjs-ng sandbox")
        refreshRecords()
    }

    /**
     * Applies an active-workspace change to every live runtime (2026-09-03).
     * Without this, a workspace switch left Tier-2 sandboxes pointing at the
     * OLD workspace (the path is captured at enable time) until each
     * add-on was manually re-enabled.
     */
    @Synchronized
    fun workspaceChanged(newPath: String) {
        this.workspacePath = newPath
        runtimes.values.forEach { rt ->
            runCatching { rt.refreshEnvironment(newPath) }
        }
        eventBus.emit(EventKind.ADDON, "host", "workspace changed → $newPath (${runtimes.size} runtime(s) updated)")
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

        // Tier-1: in-process handler.
        val handler = entry.handler
        if (handler != null) {
            eventBus.emit(EventKind.TOOL, entry.addonId, "tool '$name' invoked (tier 1)")
            val result = handler(argsJson)
            eventBus.emit(EventKind.TOOL, entry.addonId, "tool '$name' finished (tier 1)")
            return result
        }

        // Tier-2: sandbox runtime.
        val runtime = runtimes[record.dirName]
            ?: return """{"ok":false,"error":"add-on runtime not active"}"""
        eventBus.emit(EventKind.TOOL, entry.addonId, "tool '$name' invoked")
        val result = runtime.invokeTool(name, argsJson)
        eventBus.emit(EventKind.TOOL, entry.addonId, "tool '$name' finished")
        return result
    }

    /**
     * Publish a **copy** of every record. [AddonRecord] is a mutable data
     * class and the live [records] map holds the same instances across
     * refreshes, so republishing them as-is is structurally equal to the
     * previous list and `MutableStateFlow` conflates the update away — the
     * Add-ons tab never sees a status or tool change (2026-09-03 device
     * test: enable/disable looked dead, the Tier-2 "run tool" button never
     * appeared). Copying forces a new instance per record so every refresh
     * is observably different and always propagates.
     */
    private fun refreshRecords() {
        _records.value = records.values.map { rec ->
            // copy(tools = toList()) — a plain copy() would still share the
            // live tools list, and a tools-only change would conflate away.
            rec.copy(tools = rec.tools.toMutableList())
        }
    }
}
