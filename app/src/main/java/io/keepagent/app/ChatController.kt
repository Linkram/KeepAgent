package io.keepagent.app

import io.keepagent.addonsapi.llm.ChatMessage
import io.keepagent.addonsapi.llm.ImagePart
import io.keepagent.addonsapi.llm.LlmModel
import io.keepagent.app.chat.ChatSession
import io.keepagent.app.chat.ChatStore
import io.keepagent.app.chat.StoredFile
import io.keepagent.app.chat.StoredTool
import io.keepagent.app.chat.StoredTurn
import io.keepagent.core.agent.AgentRun
import io.keepagent.core.agent.ToolLine
import io.keepagent.core.events.EventKind
import io.keepagent.core.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Chat session controller. Owns the active session's turns, model
 * discovery, pending attachments, and chat history persistence (M1.3:
 * sessions survive reloads; view, continue, rename, delete from the
 * history dialog).
 */
class ChatController(private val app: KeepAgentApp) {

    /** A workspace file or folder attached to a user message (path + preview). */
    data class AttachedFile(val path: String, val isFolder: Boolean, val preview: String)

    /** One user message + its agent run (streaming state included). */
    data class Turn(
        val userText: String,
        val images: List<ImagePart>,
        val run: AgentRun,
        val files: List<AttachedFile> = emptyList(),
        val modelId: String? = null,
        /** The exact prompt sent to the model (user text + inlined attachments). */
        val sentPrompt: String = "",
    )

    /** Aggregate stats for the header (speed + context usage). */
    data class SessionStats(
        val completionTokens: Int = 0,
        val elapsedMs: Long = 0,
        val lastPromptTokens: Int = 0,
        val contextLimit: Int = 0,
    )

    private val _stats = MutableStateFlow(SessionStats())
    val stats: StateFlow<SessionStats> = _stats.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = ChatStore(File(app.filesDir, "keepagent/chats"))

    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns.asStateFlow()

    /** Title of the active session ("New chat" until first message or rename). */
    private val _sessionTitle = MutableStateFlow("New chat")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

    /**
     * Controller-owned run state. AgentRun.status is a nested flow, so deriving
     * this from `_turns.map { run.isRunning }` misses status-only updates and
     * leaves the Stop button stuck after errors or completion.
     */
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    @Volatile
    private var _activeSessionId: String? = null

    /** Id of the session currently shown in the chat (null = fresh/unsaved). */
    val currentSessionId: String?
        get() = _activeSessionId

    @Volatile
    private var modelsLoaded = false

    private val _models = MutableStateFlow<List<LlmModel>>(emptyList())
    val models: StateFlow<List<LlmModel>> = _models.asStateFlow()

    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()

    /** Images waiting to ride the next user message (e.g. Test-tab screenshots). */
    private val _pendingImages = MutableStateFlow<List<ImagePart>>(emptyList())
    val pendingImages: StateFlow<List<ImagePart>> = _pendingImages.asStateFlow()

    /** Workspace files/folders waiting to ride the next user message. */
    private val _pendingFiles = MutableStateFlow<List<AttachedFile>>(emptyList())
    val pendingFiles: StateFlow<List<AttachedFile>> = _pendingFiles.asStateFlow()

    fun attachImage(part: ImagePart) {
        _pendingImages.value = _pendingImages.value + part
    }

    fun removePendingImage(index: Int) {
        _pendingImages.value = _pendingImages.value.filterIndexed { i, _ -> i != index }
    }

    fun attachFile(file: AttachedFile) {
        _pendingFiles.value = _pendingFiles.value + file
    }

    fun removePendingFile(index: Int) {
        _pendingFiles.value = _pendingFiles.value.filterIndexed { i, _ -> i != index }
    }

    init {
        // Continue the most recent session across app restarts.
        scope.launch(Dispatchers.IO) {
            val latest = store.latest()
            if (latest != null && latest.turns.isNotEmpty()) loadIntoTurns(latest)
        }
    }

    val busy: Boolean
        get() = _isRunning.value

    @Volatile
    private var turnJob: Job? = null

    fun send(text: String) {
        val trimmed = text.trim()
        val images = _pendingImages.value
        val files = _pendingFiles.value
        if (trimmed.isEmpty() && images.isEmpty() && files.isEmpty()) return
        if (busy) return
        // Pending attachments (screenshots, files, folders) ride this message.
        _pendingImages.value = emptyList()
        _pendingFiles.value = emptyList()
        _draftText.value = ""
        persistDraft()
        if (app.settingsStore.getString(
                io.keepagent.core.settings.SettingsStore.NS_GENERAL,
                "firstMessageSent",
            ) != "true"
        ) {
            app.settingsStore.setString(
                io.keepagent.core.settings.SettingsStore.NS_GENERAL,
                "firstMessageSent",
                "true",
            )
        }
        app.ensureAgentForeground()
        dispatch(trimmed, images, files)
    }

    /** The actual turn launch; [dispatch] is also used by retry. */
    private fun dispatch(text: String, images: List<ImagePart>, files: List<AttachedFile>) {
        val agent = app.createAgentLoop()
        if (agent == null) {
            app.eventBus.emit(EventKind.ERROR, "chat", "chat unavailable: model not configured (base URL + model)")
            return
        }
        val prompt = buildPromptText(text, files)
        val turn = Turn(
            userText = text,
            images = images,
            run = AgentRun(),
            files = files,
            modelId = app.currentModelId(),
            sentPrompt = prompt,
        )
        _turns.value = _turns.value + turn
        _isRunning.value = true
        val history = buildHistory(excludeLast = true)
        val system = buildSystemText()
        turnJob = scope.launch(Dispatchers.Default) {
            // Periodic save so a killed app keeps a partial turn.
            val ticker = launch {
                while (isActive && turn.run.isRunning) {
                    delay(5_000)
                    saveSession()
                }
            }
            try {
                agent.runTurn(turn.run, prompt, images, history = history, systemText = system)
            } catch (e: CancellationException) {
                // stop() — run is already CANCELED; fall through to save.
            } finally {
                ticker.cancel()
                _isRunning.value = false
                turnJob = null
                saveSession()
                refreshStats()
                app.stopAgentForeground()
            }
        }
    }

    /** Cancels the running turn (stop button / notification action). */
    fun stop() {
        _turns.value.lastOrNull { it.run.isRunning }?.run?.cancel()
        _isRunning.value = false
        turnJob?.cancel()
        turnJob = null
    }

    // ---- Per-turn actions (M1.4) ----

    /** Re-sends the last turn (after an error or a stop). */
    fun retryLast() {
        if (busy) return
        val last = _turns.value.lastOrNull() ?: return
        _turns.value = _turns.value.dropLast(1)
        dispatch(last.userText, last.images, last.files)
    }

    /** Loads the last user message back into the composer for editing. */
    fun editLast() {
        if (busy) return
        val last = _turns.value.lastOrNull() ?: return
        _turns.value = _turns.value.dropLast(1)
        _draftText.value = last.userText
        persistDraft()
        saveSession()
    }

    /**
     * Rolls the conversation back to [index], replaces that user message
     * with [newText], and re-runs the agent from there — everything after
     * the edited message is discarded (2026-09-03: message edit action).
     */
    fun editTurn(index: Int, newText: String) {
        if (busy) return
        val text = newText.trim()
        if (text.isEmpty()) return
        val turns = _turns.value
        if (index < 0 || index >= turns.size) return
        val original = turns[index]
        _turns.value = turns.subList(0, index)
        // The re-sent message keeps the original turn's attachments.
        dispatch(text, original.images, original.files)
    }

    /** Removes the turns at the given indices (history multi-delete, M1.4h). */
    fun deleteTurns(indices: Set<Int>) {
        if (busy || indices.isEmpty()) return
        _turns.value = _turns.value.filterIndexed { i, _ -> i !in indices }
        saveSession()
    }

    /** Removes one turn (and everything after it) from the session. */
    fun deleteTurn(index: Int) {
        if (busy) return
        val turns = _turns.value
        if (index < 0 || index >= turns.size) return
        _turns.value = turns.subList(0, index)
        saveSession()
    }

    /** Starts a new session containing the turns up to (and including) [index]. */
    fun forkFrom(index: Int) {
        if (busy) return
        val turns = _turns.value
        if (index < 0 || index >= turns.size) return
        scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val id = ChatStore.newId()
            val session = ChatSession(
                id = id,
                title = (_sessionTitle.value + " (fork)").take(60),
                createdAt = now,
                updatedAt = now,
                turns = turns.subList(0, index + 1).map { snapshot(it) },
            )
            store.save(session)
            loadIntoTurns(session)
        }
    }

    /** Draft (unsent composer text), persisted so it survives an app kill. */
    private val _draftText = MutableStateFlow(app.settingsStore.getString(SettingsStore.NS_SESSIONS, "draftText").orEmpty())
    val draftText: StateFlow<String> = _draftText.asStateFlow()

    fun setDraft(text: String) {
        _draftText.value = text
        persistDraftDebounced(text)
    }

    @Volatile
    private var draftPersistJob: Job? = null

    private fun persistDraftDebounced(text: String) {
        draftPersistJob?.cancel()
        draftPersistJob = scope.launch {
            delay(800)
            if (_draftText.value == text) persistDraft()
        }
    }

    private fun persistDraft() {
        app.settingsStore.setString(SettingsStore.NS_SESSIONS, "draftText", _draftText.value)
    }

    /** Recomputes header stats (tokens/second, last context size, limit). */
    private fun refreshStats() {
        val turns = _turns.value
        var completion = 0
        var elapsed = 0L
        var lastPrompt = 0
        for (t in turns) {
            val u = t.run.usage
            completion += u.completionTokens
            elapsed += t.run.elapsedMs
            lastPrompt = maxOf(lastPrompt, u.promptTokens)
        }
        // For settled turns, use the stored elapsed when available (loaded sessions).
        val limit = app.settingsStore
            .getString(SettingsStore.NS_MODEL, "contextLimit")?.toIntOrNull()?.coerceIn(1000, 2_000_000) ?: 128_000
        _stats.value = SessionStats(completion, elapsed, lastPrompt, limit)
    }

    /**
     * Prior turns flattened into user/assistant pairs so the model keeps
     * context across the conversation. The most recent turns also carry a
     * compact tool transcript (WS-1.4) so follow-ups can ground in the
     * files earlier turns actually touched.
     */
    private fun buildHistory(excludeLast: Boolean): List<ChatMessage> {
        val src = _turns.value
        val last = if (excludeLast) src.size - 1 else src.size
        val n = minOf(last, src.size)
        val out = mutableListOf<ChatMessage>()
        for (i in 0 until n) {
            val t = src[i]
            if (t.run.isRunning) continue
            if (t.userText.isBlank() && t.images.isEmpty()) continue
            out.add(ChatMessage.user(t.userText, t.images))
            val transcript = if (n - i <= io.keepagent.core.agent.HistoryTranscript.RECENT_TURNS) {
                io.keepagent.core.agent.HistoryTranscript.render(t.run.toolLines.value)
            } else null
            val text = t.run.text.value
            when {
                text.isNotBlank() && transcript != null ->
                    out.add(ChatMessage.assistant(text + "\n" + transcript))
                text.isNotBlank() -> out.add(ChatMessage.assistant(text))
                transcript != null -> out.add(ChatMessage.assistant(transcript))
            }
        }
        return out
    }

    /** System prompt for small local models first (WS-1.2); see SystemPrompts. */
    private fun buildSystemText(): String? {
        val ws = runCatching { app.workspaceManager.activeName() }.getOrNull() ?: return null
        return io.keepagent.core.agent.SystemPrompts.keepAgent(ws)
    }

    /** The user text as sent to the model, with attachments inlined at the end. */
    private fun buildPromptText(text: String, files: List<AttachedFile>): String {
        if (files.isEmpty()) return text
        val sb = StringBuilder(text)
        files.forEach { f ->
            sb.append("\n\n---\nAttached ").append(if (f.isFolder) "folder" else "file").append(": ").append(f.path)
            if (f.preview.isNotBlank()) sb.append("\n").append(f.preview)
        }
        return sb.toString().trim().ifEmpty { text }
    }

    fun decideApproval(allow: Boolean, remember: Boolean = false) =
        app.approvalGate.decide(allow, remember)

    /** Loads the provider's model list; failures surface in [modelsError]. */
    fun refreshModels(force: Boolean = false) {
        if (modelsLoaded && !force) return
        val provider = app.addonManager.registryRef.provider("openai-compatible")
        if (provider == null) return
        scope.launch {
            runCatching { provider.listModels() }
                .onSuccess {
                    _models.value = it
                    _modelsError.value = null
                    modelsLoaded = true
                }
                .onFailure {
                    _modelsError.value = it.message ?: "model list unavailable"
                }
        }
    }

    // ---- Chat history (M1.3) ----

    /** All persisted sessions, most recently updated first. Call off the main thread. */
    fun listSessions(): List<ChatSession> = store.list()

    /** Loads a session's turns into the chat so the conversation continues. */
    fun openSession(id: String) {
        if (busy || sessionLoadInFlight) return
        sessionLoadInFlight = true
        scope.launch(Dispatchers.IO) {
            try {
                val s = store.load(id)
                if (s != null) loadIntoTurns(s)
            } finally {
                sessionLoadInFlight = false
            }
        }
    }

    @Volatile
    private var sessionLoadInFlight = false

    fun newSession() {
        if (busy) return
        _activeSessionId = null
        _sessionTitle.value = "New chat"
        _turns.value = emptyList()
        app.approvalGate.clearMemory() // "always allow (this session)" resets per conversation
    }

    fun renameSession(id: String, title: String) {
        scope.launch(Dispatchers.IO) {
            val s = store.load(id) ?: return@launch
            val t = title.trim().ifEmpty { s.title }
            store.save(s.copy(title = t, updatedAt = System.currentTimeMillis()))
            if (id == _activeSessionId) _sessionTitle.value = t
        }
    }

    fun deleteSession(id: String) {
        if (busy && id == _activeSessionId) return
        scope.launch(Dispatchers.IO) {
            store.delete(id)
            if (id == _activeSessionId) {
                _activeSessionId = null
                _sessionTitle.value = "New chat"
                _turns.value = emptyList()
            }
        }
    }

    private fun loadIntoTurns(session: ChatSession) {
        _activeSessionId = session.id
        _sessionTitle.value = session.title
        _turns.value = session.turns.map { st ->
            val run = AgentRun()
            if (st.agentText.isNotEmpty()) run.appendText(st.agentText)
            if (st.thinking.isNotEmpty()) run.appendThinking(st.thinking)
            st.tools.forEachIndexed { i, t ->
                val status = runCatching { ToolLine.Status.valueOf(t.status) }
                    .getOrDefault(ToolLine.Status.OK)
                run.addToolLine(
                    ToolLine(
                        id = "hist-$i",
                        name = t.name,
                        summary = t.summary,
                        status = status,
                        detail = t.detail,
                        extra = t.extra,
                    ),
                )
            }
            val err = st.error
            if (err != null) run.fail(err) else run.complete()
            run.addUsage(st.usagePrompt, st.usageCompletion)
            if (st.elapsedMs > 0) run.finishedAtMillis = run.createdAtMillis + st.elapsedMs
            Turn(
                userText = st.userText,
                images = st.images,
                run = run,
                files = st.files.map { AttachedFile(it.path, it.isFolder, it.preview) },
                modelId = st.modelId,
                sentPrompt = st.sentPrompt.orEmpty(),
            )
        }
        refreshStats()
    }

    /** Persists the active session (call after a turn settles). */
    private fun saveSession() {
        val id = _activeSessionId ?: ChatStore.newId().also { _activeSessionId = it }
        val turns = _turns.value
        val title = if (_sessionTitle.value.isBlank() || _sessionTitle.value == "New chat") {
            turns.firstOrNull()?.userText?.trim()?.take(48)?.ifEmpty { null } ?: "Untitled chat"
        } else {
            _sessionTitle.value
        }
        val now = System.currentTimeMillis()
        val createdAt = store.load(id)?.createdAt ?: now
        val session = ChatSession(
            id = id,
            title = title,
            createdAt = createdAt,
            updatedAt = now,
            turns = turns.map { snapshot(it) },
        )
        scope.launch(Dispatchers.IO) { store.save(session) }
    }

    private fun snapshot(t: Turn): StoredTurn {
        val run = t.run
        val u = run.usage
        return StoredTurn(
            userText = t.userText,
            images = t.images,
            files = t.files.map { StoredFile(it.path, it.isFolder, it.preview) },
            agentText = run.text.value,
            thinking = run.thinking.value,
            error = run.error.value,
            tools = run.toolLines.value.map {
                StoredTool(it.name, it.summary, it.status.name, it.detail, it.extra)
            },
            modelId = t.modelId,
            sentPrompt = t.sentPrompt.takeIf { it.isNotEmpty() },
            usagePrompt = u.promptTokens,
            usageCompletion = u.completionTokens,
            elapsedMs = run.elapsedMs,
            interrupted = run.status.value == AgentRun.Status.CANCELED,
        )
    }
}
