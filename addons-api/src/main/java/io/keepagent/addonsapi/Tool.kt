package io.keepagent.addonsapi

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Tool permission classes — these gate the approval system (spec §10). */
enum class ToolPermission {
    READ,
    WRITE,
    EXEC,
    NETWORK,
    DEVICE,
    PHONE_UI;

    companion object {
        fun from(name: String?): ToolPermission =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: READ
    }
}

/**
 * A tool registration — what an add-on hands the host when it registers a
 * tool. Tier-2 tools execute inside the JS sandbox; the host sees only this
 * spec and the JSON result of an invocation.
 */
@Serializable
data class ToolRegistration(
    val name: String,
    val description: String = "",
    val permission: ToolPermission = ToolPermission.READ,
    val inputSchema: JsonObject = JsonObject(emptyMap()),
)

/** Structured tool result (M0: text-only payloads). */
@Serializable
data class ToolResult(val text: String, val isError: Boolean = false)
