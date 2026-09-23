package io.keepagent.app.runner

import io.keepagent.addonsapi.*
import io.keepagent.core.host.Tier1Addon
import io.keepagent.core.host.Tier1Host
import kotlinx.serialization.json.*

/** Remote actions stay distinct from on-phone file tools. Approval is enforced by AgentLoop. */
class RunnerAddon(private val connection: RunnerConnection) : Tier1Addon {
    override val manifest = AddonManifest(id="io.keepagent.runner", name="Desktop runner (optional)", version="0.1.0",
        apiVersion=1, tier=1, entry="kotlin", permissions=listOf(Permissions.PROCESS_SPAWN, Permissions.NETWORK, Permissions.WORKSPACE_READ, Permissions.WORKSPACE_WRITE), provides=listOf(Capabilities.TOOL))

    override fun initialize(host: Tier1Host) {
        fun register(name: String, description: String, permission: ToolPermission, schema: String,
                     operation: (JsonObject) -> String) {
            host.registerTool(ToolRegistration(name, description, permission, Json.parseToJsonElement(schema).jsonObject)) { raw ->
                try {
                    val result = operation(Json.parseToJsonElement(raw).jsonObject)
                    buildJsonObject { put("ok", true); put("text", result) }.toString()
                } catch (e: Exception) {
                    buildJsonObject { put("ok", false); put("text", e.message ?: "Companion request failed") }.toString()
                }
            }
        }
        register("runner_info", "Inspect the paired execution target: OS, workspace and actual capabilities. Remote files are separate from phone files.", ToolPermission.READ,
            """{"type":"object","properties":{}}""") { connection.request("/v1/capabilities") }
        register("run", "Start a command on the paired target, not on the phone. argv is an array; use an explicit shell executable for shell syntax. Returns a job ID; use job to read results. A successful start does not mean the command passed.", ToolPermission.EXEC,
            """{"type":"object","properties":{"argv":{"type":"array","items":{"type":"string"}},"cwd":{"type":"string"},"timeout_seconds":{"type":"integer"},"request_id":{"type":"string","description":"Reuse only when retrying the identical command."}},"required":["argv"]}""") {
            connection.request("/v1/jobs", it.toString())
        }
        register("job", "Read a remote job and up to 16 KiB of output. Use next_offset to read more. State and exit_code determine whether tests passed.", ToolPermission.READ,
            """{"type":"object","properties":{"id":{"type":"string"},"offset":{"type":"integer"}},"required":["id"]}""") {
            val id = java.util.UUID.fromString(it.getValue("id").jsonPrimitive.content).toString()
            val offset = it["offset"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(0) ?: 0
            connection.request("/v1/jobs/$id?offset=$offset")
        }
        register("cancel_job", "Cancel a job and terminate its process tree on the paired target.", ToolPermission.EXEC,
            """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}""") {
            val id = java.util.UUID.fromString(it.getValue("id").jsonPrimitive.content).toString()
            connection.request("/v1/jobs/$id/cancel", "{}")
        }
        register("remote_file", "List, read or write files in the paired target workspace. These are NOT the phone workspace. Read existing files before writing. Results are paged by byte offset.", ToolPermission.WRITE,
            """{"type":"object","properties":{"action":{"type":"string","enum":["list","read","write"]},"path":{"type":"string"},"content":{"type":"string"},"offset":{"type":"integer"}},"required":["action","path"]}""") {
            connection.request("/v1/files", it.toString())
        }
        register("browser_check", "Run a real headless Chromium check on the paired target. Requires Playwright there. Actions use CSS selectors. Returns a job ID; read it with job for assertions, page text and console errors.", ToolPermission.EXEC,
            """{"type":"object","properties":{"url":{"type":"string"},"viewport":{"type":"string","enum":["mobile","desktop"]},"actions":{"type":"array","items":{"type":"object","properties":{"action":{"type":"string","enum":["click","fill","assert_text","assert_visible"]},"selector":{"type":"string"},"text":{"type":"string"}},"required":["action","selector"]}},"timeout_seconds":{"type":"integer"}},"required":["url"]}""") {
            connection.request("/v1/browser", it.toString())
        }
        register("desktop_check", "Launch and test a real Electron desktop app on the paired computer. Requires companion desktop dependencies. entry is its main JS file relative to the paired workspace. Returns a job ID. Arbitrary native OS tests can also run through run with the project's test command.", ToolPermission.EXEC,
            """{"type":"object","properties":{"entry":{"type":"string"},"actions":{"type":"array","items":{"type":"object","properties":{"action":{"type":"string","enum":["click","fill","assert_text","assert_visible"]},"selector":{"type":"string"},"text":{"type":"string"}},"required":["action","selector"]}},"timeout_seconds":{"type":"integer"}},"required":["entry"]}""") {
            connection.request("/v1/electron", it.toString())
        }
        register("remote_addon", "Discover or call owner-configured MCP add-ons on the companion. With no server, list names. With server, discover tool schemas. Add tool and arguments to invoke one. Discovery/calls return job IDs; read job output before choosing arguments. Only discover relevant servers to conserve context.", ToolPermission.EXEC,
            """{"type":"object","properties":{"server":{"type":"string"},"tool":{"type":"string"},"arguments":{"type":"object"},"cursor":{"type":"string"}}}""") {
            connection.request("/v1/addons", if (it.containsKey("server")) it.toString() else null)
        }
        register("android_check", "Build with run, then install, launch, interact with and assert against a real explicitly configured Android emulator/device. apk is relative to the paired workspace. Actions: tap(x,y), text(text), key(key), assert_text(text), assert_package. Returns a job ID with UI XML and screenshot artifacts.", ToolPermission.DEVICE,
            """{"type":"object","properties":{"apk":{"type":"string"},"package":{"type":"string"},"activity":{"type":"string"},"actions":{"type":"array","items":{"type":"object","properties":{"action":{"type":"string","enum":["tap","text","key","assert_text","assert_package"]},"x":{"type":"integer"},"y":{"type":"integer"},"text":{"type":"string"},"key":{"type":"string"},"package":{"type":"string"}},"required":["action"]}},"timeout_seconds":{"type":"integer"}},"required":["package"]}""") {
            connection.request("/v1/android", it.toString())
        }
    }
}
