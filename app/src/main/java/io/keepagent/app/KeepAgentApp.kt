package io.keepagent.app

import android.app.Application
import io.keepagent.addons.provideropenai.ProviderOpenAiAddon
import io.keepagent.addons.toolscore.ToolsCoreAddon
import io.keepagent.core.agent.AgentLoop
import io.keepagent.core.agent.ApprovalGate
import io.keepagent.core.agent.ApprovalMode
import io.keepagent.core.agent.ToolExecutor
import io.keepagent.core.agent.ToolOutcome
import io.keepagent.core.agent.ToolSpec
import io.keepagent.core.events.EventBus
import io.keepagent.core.events.EventKind
import io.keepagent.core.events.EventLog
import io.keepagent.core.fs.FileService
import io.keepagent.core.host.AddonManager
import io.keepagent.core.host.HelperAddonRuntime
import io.keepagent.core.host.JsAddonRuntime
import io.keepagent.core.settings.FileAccess
import io.keepagent.core.settings.SettingsStore
import io.keepagent.core.storage.Storage
import io.keepagent.core.workspace.WorkspaceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class KeepAgentApp : Application() {

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var storage: Storage
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var eventBus: EventBus
        private set
    lateinit var workspaceManager: WorkspaceManager
        private set
    lateinit var fileService: FileService
        private set
    lateinit var approvalGate: ApprovalGate
        private set
    lateinit var addonManager: AddonManager
        private set
    lateinit var chatController: ChatController
        private set

    override fun onCreate() {
        super.onCreate()
        Holder.init(this)

        storage = Storage(this)
        settingsStore = SettingsStore(this)
        eventBus = EventBus(EventLog(storage.eventsFile))
        workspaceManager = WorkspaceManager(storage, settingsStore, eventBus)
        fileService = FileService(
            root = workspaceManager.activeRoot(),
            eventBus = eventBus,
            initialMode = FileAccess.from(
                settingsStore.getString(SettingsStore.NS_GENERAL, "fileAccess"),
            ),
        )
        approvalGate = ApprovalGate(
            eventBus = eventBus,
            modeProvider = {
                ApprovalMode.from(settingsStore.getString(SettingsStore.NS_GENERAL, "approvalMode"))
            },
            allowlistProvider = {
                parseAllowlist(settingsStore.getString(SettingsStore.NS_GENERAL, "toolAllowlist"))
            },
        )
        addonManager = AddonManager(
            context = this,
            addonsDir = storage.addonsDir,
            eventBus = eventBus,
            settings = settingsStore,
            storage = storage,
            runtimeFactory = { addonId, workspacePath ->
                val onLog: (String) -> Unit = { msg -> eventBus.emit(EventKind.SYSTEM, addonId, msg) }
                val onRegisterTool: (String, String) -> Unit = { id, specJson ->
                    addonManager.onToolRegistered(id, specJson)
                }
                // ADR-0001 M1: the sandbox defaults to the helper process
                // (`:js`); the setting can force the in-process engine.
                if (engineMode() == EngineMode.IN_PROCESS) {
                    JsAddonRuntime(
                        addonId = addonId,
                        workspacePath = workspacePath,
                        settings = settingsStore,
                        onLog = onLog,
                        onRegisterTool = onRegisterTool,
                    )
                } else {
                    HelperAddonRuntime(
                        context = this,
                        addonId = addonId,
                        workspacePath = workspacePath,
                        settings = settingsStore,
                        onLog = onLog,
                        onRegisterTool = onRegisterTool,
                    )
                }
            },
            tier1Addons = listOf(
                ProviderOpenAiAddon(settingsStore),
                ToolsCoreAddon(fileService),
            ),
        )

        // App-level controller: the conversation survives tab switches.
        chatController = ChatController(this)

        eventBus.emit(EventKind.SYSTEM, "app", "KeepAgent 0.1.0-m1 starting")
        // Workspace + add-on discovery run off the main thread; the bus and
        // the manager are thread-safe for this.
        mainScope.launch(Dispatchers.IO) {
            workspaceManager.ensureDefault()
            fileService.setRoot(workspaceManager.activeRoot())
            try {
                addonManager.start(workspaceManager.activeRoot().absolutePath)
            } catch (e: Exception) {
                eventBus.emit(EventKind.ERROR, "app", "addon start failed: ${e.message}")
            }
        }
    }

    /** Engine mode for Tier-2 sandboxes (setting: general/engineMode). */
    enum class EngineMode {
        PROCESS,
        IN_PROCESS;

        companion object {
            fun from(name: String?): EngineMode =
                entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: PROCESS
        }
    }

    fun engineMode(): EngineMode =
        EngineMode.from(
            settingsStore.getString(SettingsStore.NS_GENERAL, "engineMode"),
        )

    fun currentModelId(): String? =
        settingsStore.getString(SettingsStore.NS_MODEL, "model")?.takeIf { it.isNotBlank() }

    fun modelConfigured(): Boolean =
        !settingsStore.getString(SettingsStore.NS_MODEL, "baseUrl")?.trim().isNullOrEmpty() &&
            currentModelId() != null

    /**
     * Builds the agent loop for the current model profile and the tools
     * currently registered in the capability registry. Null when no model
     * is configured.
     */
    fun createAgentLoop(): AgentLoop? {
        val provider = addonManager.registryRef.provider("openai-compatible")
            ?: return null
        val modelId = currentModelId() ?: return null
        val tools = addonManager.registryRef.tools().map { ToolSpec.from(it.tool) }
        return AgentLoop(
            provider = provider,
            modelId = modelId,
            tools = tools,
            executor = RegistryToolExecutor(addonManager),
            approval = approvalGate,
            eventBus = eventBus,
        )
    }

    private fun parseAllowlist(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

}

/**
 * Public accessor so composables can reach the Application instance.
 */
object Holder {
    private var appRef: KeepAgentApp? = null
    fun init(a: KeepAgentApp) { appRef = a }
    val app: KeepAgentApp get() = appRef ?: error("KeepAgentApp not initialized")
}

/**
 * Executes tools through the capability registry: Tier-1 in-process
 * handlers and Tier-2 sandbox runtimes alike. The handler's JSON result
 * (`{"ok":…,"text":…|"error":…}`) is unwrapped into [ToolOutcome].
 */
class RegistryToolExecutor(private val manager: AddonManager) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(name: String, argsJson: String): ToolOutcome =
        withContext(Dispatchers.IO) {
            val raw = manager.invokeTool(name, argsJson)
            try {
                val obj = json.parseToJsonElement(raw).jsonObject
                val ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                val text = obj["text"]?.jsonPrimitive?.contentOrNull
                    ?: obj["error"]?.jsonPrimitive?.contentOrNull
                    ?: raw
                ToolOutcome(ok, text)
            } catch (_: Exception) {
                ToolOutcome(false, raw)
            }
        }
}
