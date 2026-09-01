package io.keepagent.app

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val store = ChatStore(File(app.filesDir, "keepagent/chats"))

    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns.asStateFlow()

    /** Title of the active session ("New chat" until first message or rename). */
    private val _sessionTitle = MutableStateFlow("New chat")
    val sessionTitle: StateFlow<String> = _sessionTitle.asStateFlow()

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
        get() = _turns.value.lastOrNull()?.run?.isRunning == true

    fun send(text: String) {
        val trimmed = text.trim()
        val images = _pendingImages.value
        val files = _pendingFiles.value
        if (trimmed.isEmpty() && images.isEmpty() && files.isEmpty()) return
        if (busy) return
        val agent = app.createAgentLoop()
        if (agent == null) {
            app.eventBus.emit(EventKind.ERROR, "chat", "chat unavailable: model not configured (base URL + model)")
            return
        }
        // Pending attachments (screenshots, files, folders) ride this message.
        _pendingImages.value = emptyList()
        _pendingFiles.value = emptyList()
        val turn = Turn(trimmed, images, AgentRun(), files)
        _turns.value = _turns.value + turn
        scope.launch(Dispatchers.Default) {
            agent.runTurn(turn.run, buildPromptText(trimmed, files), images)
            saveSession()
        }
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

    fun decideApproval(allow: Boolean) = app.approvalGate.decide(allow)

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
                    ToolLine(id = "hist-$i", name = t.name, summary = t.summary, status = status, detail = t.detail),
                )
            }
            val err = st.error
            if (err != null) run.fail(err) else run.complete()
            Turn(
                userText = st.userText,
                images = st.images,
                run = run,
                files = st.files.map { AttachedFile(it.path, it.isFolder, it.preview) },
            )
        }
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
        return StoredTurn(
            userText = t.userText,
            images = t.images,
            files = t.files.map { StoredFile(it.path, it.isFolder, it.preview) },
            agentText = run.text.value,
            thinking = run.thinking.value,
            error = run.error.value,
            tools = run.toolLines.value.map {
                StoredTool(it.name, it.summary, it.status.name, it.detail)
            },
        )
    }
}
