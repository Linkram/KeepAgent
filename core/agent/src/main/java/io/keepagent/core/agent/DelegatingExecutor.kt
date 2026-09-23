package io.keepagent.core.agent

import io.keepagent.addonsapi.ToolPermission
import io.keepagent.addonsapi.llm.LlmProvider
import io.keepagent.core.events.EventBus
import io.keepagent.core.events.EventKind
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*

/** One bounded specialist at a time. Fresh context, read-only tools, no recursive delegation. */
class DelegatingExecutor(
    private val provider: LlmProvider,
    private val model: String,
    private val tools: List<ToolSpec>,
    private val executor: ToolExecutor,
    private val approval: ApprovalGate,
    private val events: EventBus,
    private val contextLimit: Int,
    private val projectGuidance: String,
) : ToolExecutor {
    private var delegated = 0

    override suspend fun execute(name: String, argsJson: String): ToolOutcome {
        if (name != SPEC.name) return executor.execute(name, argsJson)
        if (++delegated > 3) return ToolOutcome(false, "This turn's three-subagent budget is exhausted. Continue with the findings already returned.")
        val task = Json.parseToJsonElement(argsJson).jsonObject["task"]?.jsonPrimitive?.content.orEmpty()
        if (task.isBlank() || task.length > 8000) return ToolOutcome(false, "Provide a specific task of 1–8000 characters.")
        val child = AgentRun()
        val readTools = tools.filter { it.name in setOf("read", "glob", "grep") && it.permission == ToolPermission.READ }
        events.emit(EventKind.SYSTEM, "subagent", "started ${child.id}: ${task.take(160)}")
        val result = withTimeoutOrNull(180_000) {
            AgentLoop(provider, model, readTools, executor, approval, events, contextLimit, maxRounds=6)
                .runTurn(child, task, systemText="You are a read-only coding specialist. Inspect files to answer the assigned task. " +
                    "Do not claim to edit or run tests. Return concise findings, file paths and unresolved questions.\n" + projectGuidance)
            ToolOutcome(child.status.value == AgentRun.Status.DONE,
                "Subagent ${child.id}: ${child.text.value.takeLast(8000)}" +
                    (child.error.value?.let { "\nError: $it" } ?: ""))
        } ?: ToolOutcome(false, "Subagent ${child.id} reached its three-minute limit. Findings: ${child.text.value.takeLast(4000)}")
        events.emit(EventKind.SYSTEM, "subagent", "finished ${child.id}; ${child.usage.promptTokens}+${child.usage.completionTokens} tokens")
        return result
    }

    companion object {
        val SPEC = ToolSpec("delegate", "Ask a read-only subagent to inspect a focused part of the phone workspace. It has fresh context; supply a self-contained task. Maximum three specialists per turn, six rounds each. Returns findings; cannot edit or run tests.",
            ToolPermission.READ, """{"type":"object","properties":{"task":{"type":"string"}},"required":["task"]}""")
    }
}
