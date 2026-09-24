package io.keepagent.app

import android.app.Application
import android.content.Intent
import android.os.Build
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class KeepAgentApp : Application() {
    private val backgroundClaims = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val projectChecks by lazy { io.keepagent.app.test.ProjectChecks(this) }
    val localRuntime by lazy { io.keepagent.app.runtime.LocalRuntimeAddon(this, workspaceManager) }

    lateinit var storage: Storage
        private set
    lateinit var settingsStore: SettingsStore
        private set
    lateinit var eventBus: EventBus
        private set
    lateinit var eventLog: EventLog
        private set
    lateinit var workspaceManager: WorkspaceManager
        private set
    val linkedProjectSync by lazy { LinkedProjectSync(this, workspaceManager) }
    lateinit var fileService: FileService
        private set
    lateinit var approvalGate: ApprovalGate
        private set
    lateinit var addonManager: AddonManager
        private set
    lateinit var chatController: ChatController
        private set
    lateinit var connections: ConnectionsStore
        private set
    lateinit var runnerConnection: io.keepagent.app.runner.RunnerConnection
        private set

    override fun onCreate() {
        super.onCreate()
        // The `:js` helper process runs only JsSandboxService (ADR-0001).
        // Android still instantiates this Application class in that process,
        // and bootstrapping there re-runs the whole add-on stack a second
        // time — discovery, sandbox init, and even a second bind to the
        // sandbox service — double-writing the shared event stream (seen on
        // device 2026-09-03: duplicated "plugin host ready" blocks on every
        // launch). The service is fully self-contained over AIDL, so the
        // helper process needs none of the app bootstrap.
        if (currentProcessName().endsWith(":js") || currentProcessName().endsWith(":runtime")) return
        Holder.init(this)

        storage = Storage(this)
        // Crash log (M1.4h): uncaught exceptions are written to the storage
        // root as crash-<ts>.log before the process dies.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                File(storage.root, "crash-${System.currentTimeMillis()}.log").writeText(
                    "thread: ${thread.name}\n" + e.stackTraceToString(),
                )
                eventBus.emit(EventKind.ERROR, "app", "uncaught exception: ${e.message}")
            }
            defaultHandler?.uncaughtException(thread, e)
        }
        settingsStore = SettingsStore(this)
        io.keepagent.app.ui.theme.ThemeState.load(settingsStore)
        runnerConnection = io.keepagent.app.runner.RunnerConnection(settingsStore)
        eventLog = EventLog(storage.eventsFile)
        eventBus = EventBus(eventLog)
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
            phoneUiFullAccessProvider = {
                settingsStore.getString(SettingsStore.NS_GENERAL, "phoneUiAccess") == "full"
            },
        )
        val approvalNotifier = ApprovalNotifier(this)
        mainScope.launch {
            approvalGate.pending.collectLatest { request ->
                if (request == null) approvalNotifier.dismiss() else approvalNotifier.show(request)
            }
        }
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
                ProviderOpenAiAddon(settingsStore, eventBus),
                ToolsCoreAddon(fileService),
                localRuntime,
                io.keepagent.app.runner.RunnerAddon(runnerConnection),
                io.keepagent.app.phoneui.PhoneUiAddon(settingsStore, approvalGate),
            ),
        )

        // API connections (OpenRouter / local / custom) — the active one
        // mirrors into the model profile read by the provider add-on.
        connections = ConnectionsStore(settingsStore)

        // App-level controller: the conversation survives tab switches.
        chatController = ChatController(this)

        val versionName = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "dev"
        eventBus.emit(EventKind.SYSTEM, "app", "KeepAgent $versionName starting")
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

    /**
     * Applies an active-workspace change to every subsystem (2026-09-03):
     * re-roots the file service (Tier-1 tools) and refreshes the Tier-2
     * sandbox environment snapshots. Called by the UI after a successful
     * workspace switch.
     */
    fun workspaceChanged() {
        val root = workspaceManager.activeRoot().absolutePath
        fileService.setRoot(workspaceManager.activeRoot())
        addonManager.workspaceChanged(root)
        val name = workspaceManager.activeName()
        if (workspaceManager.linkedUri(name) != null) mainScope.launch(Dispatchers.IO) {
            runCatching { linkedProjectSync.sync(name) }
                .onSuccess { eventBus.emit(EventKind.SYSTEM, "projects", it) }
                .onFailure { eventBus.emit(EventKind.ERROR, "projects", "Folder sync failed: ${it.message}") }
        }
        eventBus.emit(EventKind.SYSTEM, "app", "workspace switched → ${workspaceManager.activeName()}")
    }

    /** This process's name ("io.keepagent" or "io.keepagent:js"). */
    private fun currentProcessName(): String =
        if (Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            runCatching { File("/proc/self/cmdline").readText().trim('\u0000', ' ') }
                .getOrDefault("")
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

    /**
     * Keeps the process alive while a turn runs. Best-effort: if the platform
     * refuses (e.g. battery restrictions), the turn simply runs in-app.
     */
    fun ensureAgentForeground() {
        try {
            val intent = Intent(this, AgentForegroundService::class.java)
            startForegroundService(intent)
        } catch (e: Exception) {
            eventBus.emit(EventKind.ERROR, "background", "Android could not protect background work: ${e.javaClass.simpleName}. Keep the app open for this run.")
        }
    }

    fun beginBackgroundWork(id: String, cancel: () -> Unit) {
        backgroundClaims[id] = cancel
        ensureAgentForeground()
    }

    fun finishBackgroundWork(id: String) {
        backgroundClaims.remove(id)
        if (backgroundClaims.isEmpty()) stopAgentForeground()
    }

    fun hasBackgroundWork(): Boolean = backgroundClaims.isNotEmpty()

    fun cancelBackgroundWork() {
        backgroundClaims.values.toList().forEach { runCatching { it() } }
    }

    /** Called when the turn finishes (completed, canceled, or failed). */
    fun stopAgentForeground() {
        AgentForegroundService.stop(this)
    }

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
        val runnerTools = setOf("runner_info", "run", "job", "cancel_job", "remote_file", "browser_check", "desktop_check", "remote_addon", "android_check")
        val tools = addonManager.registryRef.tools().map { ToolSpec.from(it.tool) }
            .filter { runnerConnection.configured() || it.name !in runnerTools }
        val limit = connections.active()?.effectiveContextLimit ?: ApiConnection.DEFAULT_CONTEXT_LIMIT
        val executor = io.keepagent.core.agent.DelegatingExecutor(
            provider, modelId, tools, RegistryToolExecutor(addonManager), approvalGate, eventBus, limit,
            "Workspace: ${workspaceManager.activeName()}. Read AGENTS.md and relevant nested instructions before inspecting code.",
        )
        return AgentLoop(
            provider = provider,
            modelId = modelId,
            tools = tools + io.keepagent.core.agent.DelegatingExecutor.SPEC,
            executor = executor,
            approval = approvalGate,
            eventBus = eventBus,
            contextLimit = connections.active()?.effectiveContextLimit
                ?: ApiConnection.DEFAULT_CONTEXT_LIMIT,
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
                val extra = obj["extra"]?.let { e ->
                    (e as? kotlinx.serialization.json.JsonObject)
                        ?.mapValues { it.value.jsonPrimitive.contentOrNull ?: it.value.toString() }
                        ?: emptyMap()
                } ?: emptyMap()
                ToolOutcome(ok, text, extra)
            } catch (_: Exception) {
                ToolOutcome(false, raw)
            }
        }
}
