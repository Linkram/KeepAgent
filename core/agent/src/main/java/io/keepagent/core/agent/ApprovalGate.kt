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
    val id: String,
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
    private val phoneUiFullAccessProvider: () -> Boolean = { false },
) {

    private val _pending = MutableStateFlow<ApprovalRequest?>(null)
    /** The request awaiting a UI decision, if any. */
    val pending: StateFlow<ApprovalRequest?> = _pending.asStateFlow()

    @Volatile
    private var decision: CompletableDeferred<Boolean>? = null
    private val phonePermitLock = Any()
    private var phonePermit: Pair<String, Long>? = null

    /**
     * Session memory: tools the user chose "always allow (this session)"
     * for. In-memory only — cleared on app restart and when the user starts
     * a new chat session (see [clearMemory]).
     */
    private val remembered = mutableSetOf<String>()

    fun rememberedTools(): List<String> = synchronized(remembered) { remembered.toList() }

    fun clearMemory() = synchronized(remembered) { remembered.clear() }

    suspend fun request(spec: ToolSpec, argsJson: String, summary: String): Boolean {
        // Phone screen content can contain messages, passwords, and other app data.
        // Its own access setting takes precedence over the general tool modes.
        if (spec.permission == ToolPermission.PHONE_UI) {
            if (phoneUiFullAccessProvider()) {
                eventBus.emit(EventKind.APPROVAL, "gate", "phone full access: ${spec.name} allowed")
                return true
            }
            synchronized(phonePermitLock) { phonePermit = null }
            return ask(spec, argsJson, summary)
        }
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
        val req = ApprovalRequest(java.util.UUID.randomUUID().toString(), spec.name, spec.permission, summary, argsJson)
        val deferred = CompletableDeferred<Boolean>()
        decision = deferred
        _pending.value = req
        eventBus.emit(
            EventKind.APPROVAL,
            "gate",
            "approval requested: ${spec.name} (${spec.permission.name.lowercase()}) — $summary",
        )
        val allowed = try { deferred.await() } finally {
            _pending.value = null
            decision = null
        }
        if (allowed && spec.permission == ToolPermission.PHONE_UI) {
            synchronized(phonePermitLock) { phonePermit = argsJson to System.currentTimeMillis() }
        }
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
    fun decide(allow: Boolean, remember: Boolean = false, requestId: String? = null): Boolean {
        if (requestId != null && pending.value?.id != requestId) return false
        if (allow && remember && pending.value?.permission != ToolPermission.PHONE_UI) {
            synchronized(remembered) { remembered.add(pending.value?.toolName?.lowercase().orEmpty().ifEmpty { "?" }) }
        }
        return decision?.complete(allow) ?: false
    }

    /** One-use authorization for the exact approved phone UI call. Direct registry calls have none. */
    fun consumePhoneUiPermit(argsJson: String): Boolean = synchronized(phonePermitLock) {
        val permit = phonePermit
        phonePermit = null
        permit != null && permit.first == argsJson && System.currentTimeMillis() - permit.second <= 30_000
    }

    private fun allowlisted(toolName: String): Boolean =
        allowlistProvider().any { it.equals(toolName, ignoreCase = true) }
}
