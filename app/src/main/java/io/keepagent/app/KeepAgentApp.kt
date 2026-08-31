package io.keepagent.app

import android.app.Application
import io.keepagent.core.events.EventBus
import io.keepagent.core.events.EventKind
import io.keepagent.core.events.EventLog
import io.keepagent.core.host.AddonManager
import io.keepagent.core.host.JsAddonRuntime
import io.keepagent.core.settings.SettingsStore
import io.keepagent.core.storage.Storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KeepAgentApp : Application() {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var storage: Storage
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var eventBus: EventBus
        private set
    lateinit var addonManager: AddonManager
        private set

    override fun onCreate() {
        super.onCreate()
        Holder.init(this)

        storage = Storage(this)
        settingsStore = SettingsStore(this)
        eventBus = EventBus(EventLog(storage.eventsFile))
        addonManager = AddonManager(
            context = this,
            addonsDir = storage.addonsDir,
            eventBus = eventBus,
            settings = settingsStore,
            runtimeFactory = { addonId, workspacePath ->
                JsAddonRuntime(
                    addonId = addonId,
                    workspacePath = workspacePath,
                    settings = settingsStore,
                    onLog = { msg -> eventBus.emit(EventKind.SYSTEM, addonId, msg) },
                    onRegisterTool = { id, specJson -> addonManager.onToolRegistered(id, specJson) },
                )
            },
        )

        eventBus.emit(EventKind.SYSTEM, "app", "KeepAgent 0.1.0-m0 starting")
        // Add-on discovery + JS init run off the main thread; the bus and
        // the manager are thread-safe for this.
        mainScope.launch(Dispatchers.IO) {
            try {
                addonManager.start(storage.root.absolutePath)
            } catch (e: Exception) {
                eventBus.emit(EventKind.ERROR, "app", "addon start failed: ${e.message}")
            }
        }
    }

    companion object {
        private class Holder {
            private var app: KeepAgentApp? = null
            fun init(a: KeepAgentApp) { app = a }
            val app: KeepAgentApp
                get() = app ?: error("KeepAgentApp not initialized")
        }
    }
}
