package io.keepagent.app

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
    data class Turn(val userText: String, val run: AgentRun)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _turns = MutableStateFlow<List<Turn>>(emptyList())
    val turns: StateFlow<List<Turn>> = _turns.asStateFlow()

    private val _models = MutableStateFlow<List<LlmModel>>(emptyList())
    val models: StateFlow<List<LlmModel>> = _models.asStateFlow()

    private val _modelsError = MutableStateFlow<String?>(null)
    val modelsError: StateFlow<String?> = _modelsError.asStateFlow()

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
        val turn = Turn(trimmed, AgentRun())
        _turns.value = _turns.value + turn
        scope.launch(Dispatchers.Default) { agent.runTurn(turn.run, trimmed) }
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
