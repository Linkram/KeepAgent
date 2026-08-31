package io.keepagent.core.host

import android.content.Context
import io.keepagent.addonsapi.AddonManifest
import io.keepagent.addonsapi.ToolRegistration
import io.keepagent.addonsapi.llm.LlmProvider
import io.keepagent.core.events.EventBus
import io.keepagent.core.settings.SettingsStore
import io.keepagent.core.storage.Storage

/**
 * The Tier-1 add-on contract (spec §5.1): an in-process Kotlin module
 * compiled into the APK, bound to the host signature — full Kotlin, direct
 * access to core services, no sandbox.
 *
 * Tier-1 modules declare their manifest in code (nothing to scan on disk);
 * the app passes its list of Tier-1 modules to the host at startup.
 */
interface Tier1Addon {
    /** The module's manifest, embedded in code. */
    val manifest: AddonManifest

    /**
     * Called once at host startup, on an IO thread. The module registers
     * capabilities through [host]. Throwing marks the module FAILED.
     */
    fun initialize(host: Tier1Host)
}

/**
 * What the host hands a Tier-1 module during [Tier1Addon.initialize].
 * This is the module's entire surface to the host: capabilities go out
 * through [registerTool]/[registerProvider] (consumed via the registry),
 * everything else happens through the core services passed here.
 */
interface Tier1Host {
    val context: Context
    val eventBus: EventBus
    val settings: SettingsStore
    val storage: Storage
    /** Root of the active workspace (ADR-0002 enforcement lives in core-fs). */
    val workspacePath: String

    /**
     * Registers a tool with its in-process handler. The handler takes the
     * args JSON and returns the result JSON
     * (`{"ok":true,"text":…}` or `{"ok":false,"error":…}`).
     */
    fun registerTool(tool: ToolRegistration, handler: (String) -> String)
    fun registerProvider(provider: LlmProvider)
}
