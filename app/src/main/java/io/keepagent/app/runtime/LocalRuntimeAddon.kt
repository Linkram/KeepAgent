package io.keepagent.app.runtime

import android.content.Context
import io.keepagent.addonsapi.AddonManifest
import io.keepagent.addonsapi.Capabilities
import io.keepagent.addonsapi.Permissions
import io.keepagent.addonsapi.ToolPermission
import io.keepagent.addonsapi.ToolRegistration
import io.keepagent.core.host.Tier1Addon
import io.keepagent.core.host.Tier1Host
import io.keepagent.core.workspace.WorkspaceManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Bundled, offline-capable runtimes for source code which can execute on Android. */
class LocalRuntimeAddon(
    private val context: Context,
    private val workspaces: WorkspaceManager,
) : Tier1Addon {
    override val manifest = AddonManifest(
        id = "io.keepagent.runtime.local",
        name = "On-device runtimes",
        version = "0.1.0",
        apiVersion = 1,
        tier = 1,
        entry = "kotlin",
        permissions = listOf(Permissions.PROCESS_SPAWN, Permissions.WORKSPACE_READ),
        provides = listOf(Capabilities.TOOL),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val client = LocalRuntimeClient(context.applicationContext)

    override fun initialize(host: Tier1Host) {
        host.registerTool(
            ToolRegistration(
                name = "local_runtime_info",
                description = "Inspect source-code runtimes bundled on this phone. No paired computer is involved.",
                permission = ToolPermission.READ,
                inputSchema = json.parseToJsonElement("""{"type":"object","properties":{}}""").jsonObject,
            ),
        ) {
            client.info()
        }
        host.registerTool(
            ToolRegistration(
                name = "run_python",
                description = "Run Python 3.13 entirely on this phone inside the active workspace. Supports a script or pytest mode; requests and pytest are bundled. This is local execution, not the optional desktop runner.",
                permission = ToolPermission.EXEC,
                inputSchema = json.parseToJsonElement(
                    """{"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative .py file or test path."},"mode":{"type":"string","enum":["script","pytest"]},"args":{"type":"array","items":{"type":"string"}}},"required":["mode"]}""",
                ).jsonObject,
            ),
        ) { raw -> runPython(raw) }
        host.registerTool(
            ToolRegistration(
                name = "run_java",
                description = "Compile and run Java source entirely on this phone. Uses a bundled Java compiler and Android dex compiler; no paired computer is involved.",
                permission = ToolPermission.EXEC,
                inputSchema = json.parseToJsonElement(
                    """{"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative .java file or source directory."},"mainClass":{"type":"string","description":"Optional fully-qualified main class; inferred when omitted."},"args":{"type":"array","items":{"type":"string"}}},"required":["path"]}""",
                ).jsonObject,
            ),
        ) { raw -> runJava(raw) }
    }

    fun runJava(raw: String, workspace: File = workspaces.activeRoot()): String =
        client.runJava(raw, workspace.canonicalPath)

    fun runPython(raw: String, workspace: File = workspaces.activeRoot()): String =
        client.runPython(raw, workspace.canonicalPath)

    fun cancelActive() = client.cancelActive()
}
