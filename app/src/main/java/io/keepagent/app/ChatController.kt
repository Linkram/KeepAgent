package io.keepagent.app

import io.keepagent.addonsapi.llm.ImagePart
import io.keepagent.addonsapi.llm.LlmModel
import io.keepagent.core.agent.AgentRun
import io.keepagent.core.events.EventKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Chat session controller (M1: one active session; multi-session with
 * persistence lands in M2). Owns the list of turns and model discovery.
 */
class ChatController(private val app: KeepAgentApp) {

    /** One user message + its agent run (streaming state included). */
    data class Turn(val userText: String, val images: List<ImagePart>, val run: AgentRun)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns.asStateFlow()

    private val _models = MutableStateFlow<List<LlmModel>>(emptyList())
    val models: StateFlow<List<LlmModel>> = _models.asStateFlow()

    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()

    /** Images waiting to ride the next user message (e.g. Test-tab screenshots). */
    private val _pendingImages = MutableStateFlow<List<ImagePart>>(emptyList())
    val pendingImages: StateFlow<List<ImagePart>> = _pendingImages.asStateFlow()

    fun attachImage(part: ImagePart) {
        _pendingImages.value = _pendingImages.value + part
    }

    fun removePendingImage(index: Int) {
        _pendingImages.value = _pendingImages.value.filterIndexed { i, _ -> i != index }
    }

    @Volatile
    private var modelsLoaded = false

    val busy: Boolean
        get() = _turns.value.lastOrNull()?.run?.isRunning == true

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || busy) return
        val agent = app.createAgentLoop()
        if (agent == null) {
            app.eventBus.emit(EventKind.ERROR, "chat", "chat unavailable: model not configured (base URL + model)")
            return
        }
        // Pending attachments (screenshots) ride this message.
        val images = _pendingImages.value
        _pendingImages.value = emptyList()
        val turn = Turn(trimmed, images, AgentRun())
        _turns.value = _turns.value + turn
        scope.launch(Dispatchers.Default) { agent.runTurn(turn.run, trimmed, images) }
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

    fun newSession() {
        if (busy) return
        _turns.value = emptyList()
    }
}
