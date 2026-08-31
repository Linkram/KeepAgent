package io.keepagent.core.agent

import io.keepagent.addonsapi.ToolPermission
import io.keepagent.addonsapi.ToolRegistration
import io.keepagent.addonsapi.llm.ToolDefinition

/** One callable tool as the agent loop sees it (from the capability registry). */
data class ToolSpec(
    val name: String,
    val description: String,
    val permission: ToolPermission,
    /** JSON-Schema object for the tool's arguments, as a JSON string. */
    val parametersJson: String,
) {
    fun toDefinition() = ToolDefinition(name, description, parametersJson)

    companion object {
        fun from(reg: ToolRegistration) =
            ToolSpec(reg.name, reg.description, reg.permission, reg.inputSchema.toString())
    }
}

/** What executing a tool produces; [text] is what the model sees as the tool result. */
data class ToolOutcome(
    val ok: Boolean,
    val text: String,
)

/**
 * Executes tools by name. The host wires this to the capability registry
 * (Tier-1 handlers and Tier-2 sandbox runtimes alike); the agent loop never
 * knows where a tool lives.
 */
fun interface ToolExecutor {
    suspend fun execute(name: String, argsJson: String): ToolOutcome
}
