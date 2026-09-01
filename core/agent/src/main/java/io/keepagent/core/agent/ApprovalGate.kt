package io.keepagent.core.agent

import io.keepagent.addonsapi.ToolPermission
import io.keepagent.core.events.EventBus
import io.keepagent.core.events.EventKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Approval modes (spec §10):
 * - [ASK] — the default: `write`/`exec`/`network`/`device` tools prompt the
 *   user before each invocation;
 * - [AUTO_ALLOW] — tools on the allowlist run silently; everything else
 *   still prompts;
 * - [NEVER_ASK] — full trust: nothing prompts. Requires an explicit toggle
 *   and a confirmation in the UI.
 */
enum class ApprovalMode {
    ASK,
    AUTO_ALLOW,
    NEVER_ASK;

    companion object {
        fun from(name: String?): ApprovalMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: ASK
    }
}

/** A tool call awaiting the user's decision. */
data class ApprovalRequest(
    val toolName: String,
    val permission: ToolPermission,
    val summary: String,
    val argsJson: String,
)

/**
 * The approval gate (spec §4.1 `core-agent`, §10). Consulted by the agent
 * loop before every tool invocation in a gated permission class.
 *
 * `read` and `network` are not gated here (M1 ships no network tools;
 * provider traffic is the user's own endpoint). One request is pending at
 * a time — single active session in M1.
 */
class ApprovalGate(
    private val eventBus: EventBus,
    private val modeProvider: () -> ApprovalMode,
    private val allowlistProvider: () -> List<String> = { emptyList() },
) {

    private val _pending = MutableStateFlow<ApprovalRequest?>(null)
    /** The request awaiting a UI decision, if any. */
    val pending: StateFlow<ApprovalRequest?> = _pending.asStateFlow()

    @Volatile
    private var decision: CompletableDeferred<Boolean>? = null

    /**
     * Session memory: tools the user chose "always allow (this session)"
     * for. In-memory only — cleared on app restart and when the user starts
     * a new chat session (see [clearMemory]).
     */
    private val remembered = mutableSetOf<String>()

    fun rememberedTools(): List<String> = synchronized(remembered) { remembered.toList() }

    fun clearMemory() = synchronized(remembered) { remembered.clear() }

    suspend fun request(spec: ToolSpec, argsJson: String, summary: String): Boolean {
        if (spec.permission == ToolPermission.READ || spec.permission == ToolPermission.NETWORK) {
            return true
        }
        if (synchronized(remembered) { spec.name.lowercase() in remembered }) {
            eventBus.emit(EventKind.APPROVAL, "gate", "session memory: ${spec.name} allowed silently")
            return true
        }
        val mode = modeProvider()
        when (mode) {
            ApprovalMode.NEVER_ASK -> {
                eventBus.emit(EventKind.APPROVAL, "gate", "full trust: ${spec.name} allowed without prompt")
                return true
            }
            ApprovalMode.AUTO_ALLOW -> {
                if (allowlisted(spec.name)) {
                    eventBus.emit(EventKind.APPROVAL, "gate", "allowlist: ${spec.name} allowed silently")
                    return true
                }
                return ask(spec, argsJson, summary)
            }
            ApprovalMode.ASK -> return ask(spec, argsJson, summary)
        }
    }

    private suspend fun ask(spec: ToolSpec, argsJson: String, summary: String): Boolean {
        val req = ApprovalRequest(spec.name, spec.permission, summary, argsJson)
        val deferred = CompletableDeferred<Boolean>()
        decision = deferred
        _pending.value = req
        eventBus.emit(
            EventKind.APPROVAL,
            "gate",
            "approval requested: ${spec.name} (${spec.permission.name.lowercase()}) — $summary",
        )
        val allowed = deferred.await()
        _pending.value = null
        decision = null
        eventBus.emit(
            EventKind.APPROVAL,
            "gate",
            if (allowed) "approved: ${spec.name}" else "denied: ${spec.name}",
        )
        return allowed
    }

    /**
     * UI decision for the pending request. No-op when nothing is pending.
     * [remember] = "always allow this tool for the rest of this session".
     */
    fun decide(allow: Boolean, remember: Boolean = false) {
        if (allow && remember) {
            synchronized(remembered) { remembered.add(pending.value?.toolName?.lowercase().orEmpty().ifEmpty { "?" }) }
        }
        decision?.complete(allow)
    }

    private fun allowlisted(toolName: String): Boolean =
        allowlistProvider().any { it.equals(toolName, ignoreCase = true) }
}
