package io.keepagent.addons.toolscore

import io.keepagent.addonsapi.AddonManifest
import io.keepagent.addonsapi.Capabilities
import io.keepagent.addonsapi.Permissions
import io.keepagent.addonsapi.ToolPermission
import io.keepagent.addonsapi.ToolRegistration
import io.keepagent.core.fs.FileService
import io.keepagent.core.host.Tier1Addon
import io.keepagent.core.host.Tier1Host
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The built-in file tools (F-007): read, write, edit, glob, grep.
 * Tier-1 — in-process, compiled into the APK. All path handling goes
 * through [FileService], which enforces the ADR-0002 access mode.
 */
class ToolsCoreAddon(private val fileService: FileService) : Tier1Addon {

    override val manifest = AddonManifest(
        id = "io.keepagent.tools.core",
        name = "File tools",
        version = "0.1.0",
        apiVersion = 1,
        tier = 1,
        entry = "kotlin",
        permissions = listOf(Permissions.WORKSPACE_READ, Permissions.WORKSPACE_WRITE),
        provides = listOf(Capabilities.TOOL),
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun initialize(host: Tier1Host) {
        host.registerTool(
            reg(
                "read",
                "Read a text file from the workspace. Returns the file content; very large or binary files are rejected.",
                ToolPermission.READ,
                """{"type":"object","properties":{"path":{"type":"string","description":"File path relative to the workspace root (absolute paths only in Full access mode)."}},"required":["path"]}""",
            ),
        ) { argsJson ->
            withArgs(argsJson) { a -> fileService.read(a.requireString("path")).toResult() }
        }

        host.registerTool(
            reg(
                "write",
                "Create or overwrite a text file in the workspace. Parent directories are created as needed. Use for new files and full rewrites.",
                ToolPermission.WRITE,
                """{"type":"object","properties":{"path":{"type":"string","description":"File path relative to the workspace root."},"content":{"type":"string","description":"Full file content to write."}},"required":["path","content"]}""",
            ),
        ) { argsJson ->
            withArgs(argsJson) { a ->
                fileService.write(a.requireString("path"), a.requireString("content")).toResult()
            }
        }

        host.registerTool(
            reg(
                "edit",
                "Replace an exact text span in a file. oldString must match exactly once unless replaceAll is true — include enough surrounding context to be unique.",
                ToolPermission.WRITE,
                """{"type":"object","properties":{"path":{"type":"string","description":"File path relative to the workspace root."},"oldString":{"type":"string","description":"Exact text to replace."},"newString":{"type":"string","description":"Replacement text."},"replaceAll":{"type":"boolean","description":"Replace every occurrence. Defaults to false."}},"required":["path","oldString","newString"]}""",
            ),
        ) { argsJson ->
            withArgs(argsJson) { a ->
                fileService.edit(
                    path = a.requireString("path"),
                    oldString = a.requireString("oldString"),
                    newString = a.optString("newString"),
                    replaceAll = a.optBoolean("replaceAll"),
                ).toResult()
            }
        }

        host.registerTool(
            reg(
                "glob",
                "List files by glob pattern (newest first). Patterns without '/' match the filename at any depth ('*.kt'); with '/' they match the path ('src/**/*.kt').",
                ToolPermission.READ,
                """{"type":"object","properties":{"pattern":{"type":"string","description":"Glob pattern, e.g. \"**/*.kt\"."}},"required":["pattern"]}""",
            ),
        ) { argsJson ->
            withArgs(argsJson) { a -> fileService.glob(a.requireString("pattern")).toResult() }
        }

        host.registerTool(
            reg(
                "grep",
                "Search file contents with a regular expression. Returns matching lines as path:line: text.",
                ToolPermission.READ,
                """{"type":"object","properties":{"pattern":{"type":"string","description":"Regular expression to search for."},"path":{"type":"string","description":"Optional file or directory to search in. Defaults to the workspace root."},"include":{"type":"string","description":"Optional filename glob filter, e.g. \"*.kt\"."}},"required":["pattern"]}""",
            ),
        ) { argsJson ->
            withArgs(argsJson) { a ->
                fileService.grep(
                    pattern = a.requireString("pattern"),
                    path = a.optStringOrNull("path"),
                    include = a.optStringOrNull("include"),
                ).toResult()
            }
        }
    }

    // -- registration + invocation helpers ------------------------------------

    private fun reg(
        name: String,
        description: String,
        permission: ToolPermission,
        schemaJson: String,
    ) = ToolRegistration(
        name = name,
        description = description,
        permission = permission,
        inputSchema = json.parseToJsonElement(schemaJson).jsonObject,
    )

    private class Args(val obj: JsonObject) {
        fun requireString(key: String): String =
            obj[key]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("missing required argument: $key")

        fun optString(key: String): String = obj[key]?.jsonPrimitive?.contentOrNull ?: ""

        fun optStringOrNull(key: String): String? = obj[key]?.jsonPrimitive?.contentOrNull

        fun optBoolean(key: String): Boolean =
            obj[key]?.jsonPrimitive?.booleanOrNull ?: false
    }

    private fun withArgs(argsJson: String, block: (Args) -> String): String =
        runCatching {
            val obj = try {
                json.parseToJsonElement(argsJson).jsonObject
            } catch (_: Exception) {
                JsonObject(emptyMap())
            }
            block(Args(obj))
        }.getOrElse { e ->
            result(false, e.message ?: "tool failed")
        }

    private fun FileService.Result.toResult(): String =
        if (ok) result(true, text) else result(false, error ?: "failed")

    /** The JSON contract between tool handlers and the agent loop. */
    private fun result(ok: Boolean, text: String): String =
        buildJsonObject {
            put("ok", ok)
            put("text", text)
        }.toString()
}
