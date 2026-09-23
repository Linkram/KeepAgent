package io.keepagent.app.test

import io.keepagent.app.KeepAgentApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.io.File

/** App-owned runs survive navigation. Results are scoped to the captured workspace. */
class ProjectChecks(private val app: KeepAgentApp) {
    data class Target(val kind: String, val path: String, val title: String, val detail: String)
    @kotlinx.serialization.Serializable
    data class Run(val id: Long, val workspace: String, val target: String, val title: String,
        val running: Boolean, val ok: Boolean = false, val output: String = "")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val state = MutableStateFlow<List<Run>>(emptyList())
    val runs = state.asStateFlow()
    private val journal = android.util.AtomicFile(File(app.filesDir, "keepagent/project-checks.json"))
    private var loaded = false
    private val journalLock = Mutex()
    @Volatile private var activeJob: Job? = null

    init {
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    journal.openRead().bufferedReader().use { Json.decodeFromString<List<Run>>(it.readText()) }
                        .takeLast(20).map { if (it.running) it.copy(running = false, ok = false,
                            output = "Interrupted when Android stopped KeepAgent. Run this check again; it was not automatically repeated.") else it }
                }.getOrDefault(emptyList())
            }
            state.value = saved
            loaded = true
        }
    }

    private suspend fun persist() = journalLock.withLock {
        val snapshot = state.value
        withContext(Dispatchers.IO) {
            try {
                val stream = journal.startWrite()
                try {
                    stream.write(Json.encodeToString(snapshot).toByteArray(Charsets.UTF_8))
                    journal.finishWrite(stream)
                } catch (e: Exception) {
                    journal.failWrite(stream)
                    throw e
                }
            } catch (e: Exception) {
                app.eventBus.emit(io.keepagent.core.events.EventKind.ERROR, "tests", "Could not save test history: ${e.javaClass.simpleName}")
            }
        }
    }

    fun start(root: File, target: Target) {
        if (!loaded || state.value.any { it.running }) return
        val run = Run(System.currentTimeMillis(), root.path, target.path, target.title, true)
        state.value = state.value.takeLast(19) + run
        val workId = "project-check-${run.id}"
        activeJob = scope.launch {
            app.beginBackgroundWork(workId) { stop() }
            persist()
            try {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Pass a captured root so changing project during a queued run cannot retarget it.
                    if (target.kind == "java") app.localRuntime.runJava(buildJsonObject {
                        put("path", target.path); put("args", buildJsonArray { })
                    }.toString(), root) else app.localRuntime.runPython(buildJsonObject {
                        put("mode", "pytest"); put("path", target.path)
                        put("args", buildJsonArray { add("-q"); add("--import-mode=importlib") })
                    }.toString(), root)
                }.getOrElse { buildJsonObject { put("ok", false); put("error", it.message ?: "Test execution failed") }.toString() }
            }
            val obj = Json.parseToJsonElement(result).jsonObject
            state.value = state.value.map {
                if (it.id != run.id) it else it.copy(running = false,
                    ok = obj["ok"]?.jsonPrimitive?.booleanOrNull == true,
                    output = obj["text"]?.jsonPrimitive?.contentOrNull ?: obj["error"]?.jsonPrimitive?.contentOrNull ?: result)
            }
            persist()
            } catch (_: CancellationException) {
                state.value = state.value.map {
                    if (it.id != run.id) it else it.copy(running = false, ok = false, output = "Stopped by you.")
                }
                withContext(NonCancellable) { persist() }
            } finally {
                activeJob = null
                app.finishBackgroundWork(workId)
            }
        }
    }

    fun stop() {
        app.localRuntime.cancelActive()
        activeJob?.cancel()
    }

    companion object {
        fun discover(root: File): List<Target> {
            val base = root.canonicalFile
            val ignored = setOf(".git", ".gradle", ".venv", "venv", "node_modules", "build", "dist", "__pycache__")
            val files = base.walkTopDown().maxDepth(5).onEnter {
                it.name !in ignored && it.canonicalFile == it.absoluteFile
            }.filter { it.isFile && it.canonicalFile.path.startsWith(base.path + File.separator) }.take(3000).toList()
            return buildList {
                if (files.any { it.name.startsWith("test_") && it.extension == "py" || it.name.endsWith("_test.py") || it.name == "pytest.ini" })
                    add(Target("pytest", ".", "Python tests", "pytest · bundled runtime · runs on this phone"))
                if (files.any { it.extension.equals("java", true) && Regex("static\\s+void\\s+main\\s*\\(").containsMatchIn(it.readText().take(1_000_000)) })
                    add(Target("java", ".", "Java program", "Bundled compiler · runs on this phone"))
                if (files.any { it.extension.lowercase() in setOf("html", "htm") })
                    add(Target("web", "web", "App preview", "Open HTML interfaces and capture visual evidence on this phone"))
                if (File(base, "package.json").isFile)
                    add(Target("unavailable", "node", "JavaScript project", "Node/npm execution is not installed. HTML previews work locally; package scripts need an additional runtime."))
                if (files.any { it.extension.lowercase() in setOf("c", "cc", "cpp", "cxx") })
                    add(Target("unavailable", "native", "C / C++ project", "Native Clang toolchain pack is not installed yet. It will run on this phone; desktop pairing is optional."))
                if (File(base, "gradlew").isFile || File(base, "gradlew.bat").isFile)
                    add(Target("unavailable", "gradle", "Android/Gradle build", "Java source runs locally now. Full Android builds need the optional Android build-tools pack."))
            }
        }
    }
}
