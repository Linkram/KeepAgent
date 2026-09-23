package io.keepagent.app.phoneui

import io.keepagent.addonsapi.AddonManifest
import io.keepagent.addonsapi.Capabilities
import io.keepagent.addonsapi.Permissions
import io.keepagent.addonsapi.ToolPermission
import io.keepagent.addonsapi.ToolRegistration
import io.keepagent.core.host.Tier1Addon
import io.keepagent.core.host.Tier1Host
import io.keepagent.core.agent.ApprovalGate
import io.keepagent.core.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

/** Generic foreground-app control. The agent's PHONE_UI approval gate runs before this handler. */
class PhoneUiAddon(private val settings: SettingsStore, private val approval: ApprovalGate) : Tier1Addon {
    override val manifest = AddonManifest(
        id = "io.keepagent.phone-ui",
        name = "Phone app control",
        version = "0.1.0",
        apiVersion = 1,
        tier = 1,
        entry = "kotlin",
        permissions = listOf(Permissions.DEVICE_UIAUTOMATION),
        provides = listOf(Capabilities.TOOL),
    )

    override fun initialize(host: Tier1Host) {
        host.registerTool(
            ToolRegistration(
                name = "phone_ui",
                description = "With the owner's approval, inspect and control the foreground Android app. Works with any app that exposes accessible UI, including messaging and browsers. Actions: list_apps to find package names, launch a package, open_url, inspect visible nodes, click/set_text/scroll using a fresh snapshot and node path, tap/swipe coordinates, back, home. Each call requires its own approval unless Phone app control is set to Full access. Android Accessibility must be enabled. Inspect again after each action; never assume a message was sent without checking the screen.",
                permission = ToolPermission.PHONE_UI,
                inputSchema = Json.parseToJsonElement("""{
                    "type":"object",
                    "properties":{
                        "action":{"type":"string","enum":["list_apps","launch","open_url","inspect","click","set_text","scroll_forward","scroll_backward","tap","swipe","back","home"]},
                        "query":{"type":"string","description":"Optional app name filter for list_apps."},
                        "package":{"type":"string","description":"Target app package. Required for launch and actions; use the package returned by inspect."},
                        "url":{"type":"string","description":"http or https URL for open_url."},
                        "snapshot":{"type":"string","description":"ID returned by the latest inspect action."},
                        "node":{"type":"string","description":"Node path from the latest inspect action."},
                        "text":{"type":"string","description":"Replacement text for set_text."},
                        "x":{"type":"integer"},"y":{"type":"integer"},
                        "end_x":{"type":"integer"},"end_y":{"type":"integer"},
                        "duration_ms":{"type":"integer"}
                    },
                    "required":["action"]
                }""").jsonObject,
            ),
        ) { raw ->
            try {
                val mode = settings.getString(SettingsStore.NS_GENERAL, "phoneUiAccess")
                require(mode in setOf("ask", "full")) {
                    "Phone app access is off. Turn it on in Tools first."
                }
                require(mode == "full" || approval.consumePhoneUiPermit(raw)) {
                    "This phone app action was not approved. Ask again in Chat."
                }
                val result = runBlocking { PhoneUiService.perform(JSONObject(raw)) }
                val ok = JSONObject(result).optBoolean("ok", true)
                JSONObject().put("ok", ok).put("text", result).toString()
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("text", e.message ?: "Phone app control failed").toString()
            }
        }
    }
}
