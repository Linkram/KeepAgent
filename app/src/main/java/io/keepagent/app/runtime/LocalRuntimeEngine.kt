package io.keepagent.app.runtime

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/** Runtime implementation instantiated only inside the disposable worker process. */
internal class LocalRuntimeEngine(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val javaRuntime by lazy { JavaRuntime(context) }

    fun info(): String = try {
        startPython()
        buildJsonObject {
            put("ok", true)
            put("text", Python.getInstance().getModule("keepagent_runner").callAttr("info").toString())
        }.toString()
    } catch (e: Exception) {
        failure(e, "runtime check failed")
    }

    fun runJava(raw: String, workspace: File): String = try {
        val input = json.parseToJsonElement(raw).jsonObject
        val path = input["path"]?.jsonPrimitive?.contentOrNull ?: error("path is required")
        val main = input["mainClass"]?.jsonPrimitive?.contentOrNull
        val args = input["args"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        val result = javaRuntime.run(workspace, path, main, args)
        buildJsonObject { put("ok", result.ok); put("text", result.text) }.toString()
    } catch (e: Exception) {
        failure(e, "local Java failed")
    }

    fun runPython(raw: String, workspace: File): String = try {
        val input = json.parseToJsonElement(raw).jsonObject
        val mode = input["mode"]?.jsonPrimitive?.contentOrNull ?: "script"
        require(mode == "script" || mode == "pytest") { "mode must be script or pytest" }
        val relative = input["path"]?.jsonPrimitive?.contentOrNull.orEmpty()
        require(mode == "pytest" || relative.isNotBlank()) { "path is required for script mode" }
        val root = workspace.canonicalFile
        val target = if (relative.isBlank()) root else File(root, relative).canonicalFile
        require(target == root || target.path.startsWith(root.path + File.separator)) { "path escapes the active workspace" }
        require(target.exists()) { "path does not exist: $relative" }
        if (mode == "script") {
            require(target.isFile && target.extension.equals("py", true)) { "script mode requires a .py file" }
            require(target.length() <= 1_000_000) { "script is larger than 1 MB" }
        }
        val args = input["args"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        startPython()
        val result = Python.getInstance().getModule("keepagent_runner").callAttr(
            "run", root.path, target.path, mode, Json.encodeToString(args),
        ).toString()
        val parsed = json.parseToJsonElement(result).jsonObject
        val exit = parsed["exit_code"]?.jsonPrimitive?.content ?: "1"
        val stdout = parsed["stdout"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val stderr = parsed["stderr"]?.jsonPrimitive?.contentOrNull.orEmpty()
        buildJsonObject {
            put("ok", exit == "0")
            put("text", "exit $exit\n${stdout}${if (stderr.isNotBlank()) "\nstderr:\n$stderr" else ""}".trim())
        }.toString()
    } catch (e: Exception) {
        failure(e, "local Python failed")
    }

    private fun startPython() {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
    }

    private fun failure(e: Exception, fallback: String) =
        buildJsonObject { put("ok", false); put("error", e.message ?: fallback) }.toString()
}
