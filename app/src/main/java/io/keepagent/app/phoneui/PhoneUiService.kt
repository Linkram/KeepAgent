package io.keepagent.app.phoneui

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import io.keepagent.app.Holder
import io.keepagent.core.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.coroutines.resume

/** Opt-in bridge to the currently visible Android app. Events are never harvested in the background. */
class PhoneUiService : AccessibilityService() {
    private data class NodeRef(
        val path: String,
        val className: String,
        val viewId: String,
        val label: String,
        val bounds: Rect,
    )

    private var snapshotId = ""
    private var snapshotPackage = ""
    private var snapshotWindowId = -1
    private val refs = mutableMapOf<String, NodeRef>()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        val mode = accessMode()
        if (mode == "off") {
            disableSelf()
            return
        }
        active = this
        connected.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        clearActive()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        clearActive()
        super.onDestroy()
    }

    private fun clearActive() {
        if (active === this) {
            active = null
            connected.value = false
        }
        clearSnapshot()
    }

    private fun accessMode(): String = runCatching {
        Holder.app.settingsStore.getString(SettingsStore.NS_GENERAL, "phoneUiAccess")
    }.getOrNull() ?: "off"

    suspend fun execute(args: JSONObject): String {
        require(accessMode() in setOf("ask", "full")) { "Phone app access is off. Enable it in Tools first." }
        return when (val action = args.optString("action")) {
            "inspect" -> withContext(Dispatchers.Main) { inspect(args) }
            "list_apps" -> withContext(Dispatchers.Main) { listApps(args) }
            "launch" -> withContext(Dispatchers.Main) { launch(args) }
            "open_url" -> withContext(Dispatchers.Main) { openUrl(args) }
            "click", "set_text", "scroll_forward", "scroll_backward" ->
                withContext(Dispatchers.Main) { nodeAction(action, args) }
            "tap", "swipe" -> gestureAction(action, args)
            "back", "home" -> withContext(Dispatchers.Main) { globalAction(action, args) }
            else -> error("Unknown phone UI action: $action")
        }
    }

    private fun inspect(args: JSONObject): String {
        val root = targetRoot(args.optString("package"))
        clearSnapshot()
        snapshotId = UUID.randomUUID().toString()
        snapshotPackage = root.packageName?.toString().orEmpty()
        snapshotWindowId = root.windowId
        val nodes = JSONArray()
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
            if (nodes.length() >= MAX_NODES || depth > MAX_DEPTH) return
            val bounds = Rect().also(node::getBoundsInScreen)
            val label = if (node.isPassword) "[password hidden]" else
                (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().take(240)
            val ref = NodeRef(path, node.className?.toString().orEmpty(), node.viewIdResourceName.orEmpty(), label, bounds)
            refs[path] = ref
            nodes.put(JSONObject().apply {
                put("node", path)
                put("class", ref.className)
                put("id", ref.viewId)
                put("label", label)
                put("bounds", "${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}")
                put("clickable", node.isClickable)
                put("editable", node.isEditable)
                put("scrollable", node.isScrollable)
                put("password", node.isPassword)
            })
            for (i in 0 until node.childCount) {
                if (nodes.length() >= MAX_NODES) break
                val child = node.getChild(i) ?: continue
                try { visit(child, "$path.$i", depth + 1) } finally { child.recycle() }
            }
        }
        visit(root, "0", 0)
        return JSONObject().apply {
            put("package", snapshotPackage)
            put("snapshot", snapshotId)
            put("window", snapshotWindowId)
            put("nodes", nodes)
            put("truncated", nodes.length() >= MAX_NODES)
        }.toString()
    }

    @Suppress("DEPRECATION")
    private fun listApps(args: JSONObject): String {
        val query = args.optString("query").trim().lowercase()
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val matches = packageManager.queryIntentActivities(launcher, 0)
            .map { info -> info.loadLabel(packageManager).toString() to info.activityInfo.packageName }
            .distinctBy { it.second }
            .filter { query.isEmpty() || it.first.lowercase().contains(query) || it.second.lowercase().contains(query) }
            .sortedBy { it.first.lowercase() }
        val apps = JSONArray()
        matches.take(80).forEach { (label, pkg) ->
            apps.put(JSONObject().put("label", label).put("package", pkg))
        }
        return JSONObject().put("apps", apps).put("truncated", matches.size > 80).toString()
    }

    private fun nodeAction(action: String, args: JSONObject): String {
        val expectedPackage = requiredPackage(args)
        val token = args.optString("snapshot")
        val path = args.optString("node")
        require(token.isNotBlank() && token == snapshotId && expectedPackage == snapshotPackage) {
            "Screen snapshot expired. Inspect the target app again."
        }
        val ref = refs[path] ?: error("Node is not in the snapshot. Inspect again.")
        val root = targetRoot(expectedPackage)
        require(root.windowId == snapshotWindowId) { "Window changed. Inspect again." }
        var node = root
        path.split('.').drop(1).forEach { segment ->
            node = node.getChild(segment.toIntOrNull() ?: -1)
                ?: error("Screen changed. Inspect again.")
        }
        val fresh = Rect().also(node::getBoundsInScreen)
        val freshLabel = if (node.isPassword) "[password hidden]" else
            (node.text?.toString() ?: node.contentDescription?.toString()).orEmpty().take(240)
        require(node.className?.toString().orEmpty() == ref.className &&
            node.viewIdResourceName.orEmpty() == ref.viewId &&
            fresh == ref.bounds && freshLabel == ref.label) {
            "Screen changed. Inspect again before acting."
        }
        val ok = when (action) {
            "click" -> {
                var target: AccessibilityNodeInfo? = node
                var result = false
                repeat(4) {
                    if (!result && target != null) {
                        if (target!!.isClickable) result = target!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        target = target!!.parent
                    }
                }
                result
            }
            "set_text" -> {
                require(node.isEditable) { "Selected node is not an editable field" }
                val value = args.getString("text")
                require(value.length <= 4_000) { "Text is too long" }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                })
            }
            "scroll_forward", "scroll_backward" -> {
                require(node.isScrollable) { "Selected node is not scrollable" }
                node.performAction(if (action == "scroll_forward")
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            }
            else -> false
        }
        clearSnapshot()
        return JSONObject().put("ok", ok).put("package", expectedPackage).put("action", action).toString()
    }

    private suspend fun gestureAction(action: String, args: JSONObject): String {
        val expectedPackage = requiredPackage(args)
        val gesture = withContext(Dispatchers.Main) {
            targetRoot(expectedPackage)
            val path = Path()
            val x = args.getInt("x")
            val y = args.getInt("y")
            require(x >= 0 && y >= 0) { "Coordinates must be non-negative" }
            path.moveTo(x.toFloat(), y.toFloat())
            val duration = if (action == "swipe") {
                val endX = args.getInt("end_x")
                val endY = args.getInt("end_y")
                require(endX >= 0 && endY >= 0) { "Coordinates must be non-negative" }
                path.lineTo(endX.toFloat(), endY.toFloat())
                args.optLong("duration_ms", 400).coerceIn(100, 2_000)
            } else 80L
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()
        }
        val ok = withTimeoutOrNull(5_000) {
            withContext(Dispatchers.Main) {
                targetRoot(expectedPackage)
                suspendCancellableCoroutine { continuation ->
                    val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(true)
                        }
                        override fun onCancelled(gestureDescription: GestureDescription?) {
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }, null)
                    if (!accepted && continuation.isActive) continuation.resume(false)
                }
            }
        } ?: false
        withContext(Dispatchers.Main) { clearSnapshot() }
        return JSONObject().put("ok", ok).put("package", expectedPackage).put("action", action).toString()
    }

    private fun globalAction(action: String, args: JSONObject): String {
        val expectedPackage = requiredPackage(args)
        targetRoot(expectedPackage)
        val ok = performGlobalAction(if (action == "back") GLOBAL_ACTION_BACK else GLOBAL_ACTION_HOME)
        clearSnapshot()
        return JSONObject().put("ok", ok).put("action", action).toString()
    }

    private fun launch(args: JSONObject): String {
        val target = requiredPackage(args)
        require(target.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+"))) { "Invalid app package name" }
        val intent = packageManager.getLaunchIntentForPackage(target)
            ?: error("No launchable app found for $target")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        clearSnapshot()
        return JSONObject().put("launched", target).toString()
    }

    private fun openUrl(args: JSONObject): String {
        val url = Uri.parse(args.getString("url"))
        require(url.scheme in listOf("http", "https") && !url.host.isNullOrBlank()) { "Use an http or https URL" }
        startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        clearSnapshot()
        return JSONObject().put("opened", url.toString()).toString()
    }

    private fun targetRoot(expectedPackage: String): AccessibilityNodeInfo {
        val root = rootInActiveWindow ?: error("No accessible app is in the foreground")
        val actual = root.packageName?.toString().orEmpty()
        require(actual.isNotBlank() && actual != packageName) { "Open the target app before inspecting or controlling it" }
        if (expectedPackage.isNotBlank()) require(actual == expectedPackage) {
            "Foreground app changed from $expectedPackage to $actual. Inspect again."
        }
        return root
    }

    private fun requiredPackage(args: JSONObject): String =
        args.optString("package").takeIf { it.isNotBlank() }
            ?: error("Supply the target app package from the last inspection")

    private fun clearSnapshot() {
        snapshotId = ""
        snapshotPackage = ""
        snapshotWindowId = -1
        refs.clear()
    }

    companion object {
        const val MAX_NODES = 160
        const val MAX_DEPTH = 16
        val connected = MutableStateFlow(false)
        @Volatile private var active: PhoneUiService? = null

        suspend fun perform(args: JSONObject): String =
            (active ?: error("Enable KeepAgent phone app control in Android Accessibility settings"))
                .execute(args)

        fun stop() { active?.disableSelf() }
    }
}
