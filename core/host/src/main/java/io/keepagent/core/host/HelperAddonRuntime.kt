package io.keepagent.core.host

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.keepagent.core.settings.SettingsStore
import io.keepagent.runtime.js.IKeepJsCallback
import io.keepagent.runtime.js.JsSandboxService
import io.keepagent.runtime.js.KeepJsService
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tier-2 runtime with the quickjs-ng engine in the helper process (`:js`)
 * over AIDL IPC (ADR-0001, M1). Same prelude and add-on code as in-process;
 * the `native` host object's calls cross the IPC boundary instead of JNI.
 *
 * Failure handling:
 * - helper unreachable at enable time → in-process [JsAddonRuntime] fallback;
 * - helper dies MID-SESSION (OS reclaims the process under memory pressure)
 *   → the next call detects the lost runtime and fails over transparently:
 *   rebind the helper, or fall back in-process, re-running the add-on
 *   bootstrap so its tools exist again (2026-09-03).
 */
class HelperAddonRuntime(
    private val context: Context,
    private val addonId: String,
    // `var`: refreshEnvironment() re-points it on a workspace switch and
    // pushes the new snapshot into the live helper engine.
    private var workspacePath: String,
    private val settings: SettingsStore,
    private val onLog: (String) -> Unit,
    private val onRegisterTool: (addonId: String, specJson: String) -> Unit,
) : AddonRuntime {

    private var service: KeepJsService? = null
    private var connection: ServiceConnection? = null
    private var fallback: JsAddonRuntime? = null

    // Bootstrap memory for failover: the add-on source is re-run on a fresh
    // runtime after the helper dies (a dead process lost its JS state).
    private var lastSource: String? = null
    private var lastFilename: String? = null
    private var bootstrapped = false

    private val callback = object : IKeepJsCallback.Stub() {
        override fun onLog(message: String) {
            this@HelperAddonRuntime.onLog(message)
        }

        override fun onRegisterTool(specJson: String) {
            this@HelperAddonRuntime.onRegisterTool(addonId, specJson)
        }

        override fun onError(message: String) {
            this@HelperAddonRuntime.onLog("error: $message")
        }
    }

    override fun initialize(): Boolean {
        val ok = bindAndInit() || fallbackInit()
        bootstrapped = false
        return ok
    }

    override fun evaluate(source: String, filename: String): String? {
        if (!ensureRuntime()) return "runtime not started"
        lastSource = source
        lastFilename = filename
        val err = runEvaluate(source, filename)
        if (err == null) bootstrapped = true
        return err
    }

    override fun invokeTool(name: String, argsJson: String): String {
        val wasLost = service == null && fallback == null
        if (!ensureRuntime()) return FAIL_INACTIVE
        if (wasLost && !bootstrapped) {
            // The runtime we just (re)built has no add-on state: re-run the
            // bootstrap so the tool below exists again.
            val src = lastSource ?: return FAIL_NO_SOURCE
            val err = runEvaluate(src, lastFilename ?: "$addonId.js")
            if (err != null) return """{"ok":false,"error":"sandbox re-bootstrap failed: $err"}"""
            bootstrapped = true
        }
        return runInvokeTool(name, argsJson)
    }

    override fun refreshEnvironment(workspacePath: String) {
        this.workspacePath = workspacePath
        service?.let { svc ->
            runCatching {
                svc.updateEnv(addonId, settingsDocument(), workspacePath)
            }.onFailure { e -> onLog("helper env refresh failed: ${e.message}") }
            return
        }
        fallback?.refreshEnvironment(workspacePath)
    }

    override fun shutdown() {
        try {
            service?.shutdownAddon(addonId)
        } catch (_: Exception) {
        }
        connection?.let { conn ->
            runCatching { context.unbindService(conn) }
        }
        connection = null
        service = null
        fallback?.shutdown()
        fallback = null
        bootstrapped = false
    }

    // -- internals ------------------------------------------------------------

    /**
     * True when a usable runtime exists; otherwise re-initializes (rebind
     * the helper first, then the in-process fallback). An old, dead
     * connection is unbound before rebinding.
     */
    private fun ensureRuntime(): Boolean {
        if (service != null || fallback != null) return true
        onLog("sandbox runtime lost — failing over (helper rebind, else in-process)")
        connection?.let { conn ->
            runCatching { context.unbindService(conn) }
        }
        connection = null
        service = null
        return bindAndInit() || fallbackInit()
    }

    private fun runEvaluate(source: String, filename: String): String? {
        service?.let { svc ->
            return try {
                val err = svc.eval(addonId, source, filename)
                if (err.isNullOrEmpty()) null else err
            } catch (e: Exception) {
                "IPC error: ${e.message}"
            }
        }
        return fallback?.evaluate(source, filename) ?: "runtime not started"
    }

    private fun runInvokeTool(name: String, argsJson: String): String {
        service?.let { svc ->
            return try {
                svc.invokeTool(addonId, name, argsJson)
            } catch (e: Exception) {
                """{"ok":false,"error":"IPC failure: ${e.message}"}"""
            }
        }
        return fallback?.invokeTool(name, argsJson) ?: FAIL_INACTIVE
    }

    private fun bindAndInit(): Boolean {
        val latch = CountDownLatch(1)
        var boundService: KeepJsService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, ib: IBinder?) {
                boundService = ib?.let { KeepJsService.Stub.asInterface(it) }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Never count down the connection latch here — only
                // onServiceConnected signals success; a disconnect during the
                // wait just lets the timeout expire into the fallback path.
                // After a successful connect, a disconnect (helper crash /
                // unbind) makes the binder unusable: clear the reference so
                // the next call fails over (ensureRuntime) instead of throwing.
                if (service != null) {
                    onLog("helper process disconnected — failing over on next call")
                    service = null
                    bootstrapped = false
                }
            }
        }
        try {
            context.bindService(
                Intent(context, JsSandboxService::class.java),
                conn,
                Context.BIND_AUTO_CREATE,
            )
        } catch (_: SecurityException) {
            return false
        }
        connection = conn
        val connected = latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        val svc = if (connected) boundService else null
        if (svc == null) {
            runCatching { context.unbindService(conn) }
            connection = null
            return false
        }
        try {
            svc.init(addonId, callback, settingsDocument(), workspacePath)
        } catch (e: Exception) {
            runCatching { context.unbindService(conn) }
            connection = null
            onLog("helper IPC init failed: ${e.message}")
            return false
        }
        service = svc
        onLog("sandbox in helper process (ipc)")
        return true
    }

    private fun fallbackInit(): Boolean {
        onLog("helper process unavailable — falling back to in-process sandbox")
        val rt = JsAddonRuntime(addonId, workspacePath, settings, onLog, onRegisterTool)
        if (!rt.initialize()) return false
        fallback = rt
        return true
    }

    /**
     * The settings document the helper process can query per namespace —
     * same shape as `SettingsStore.get(namespace)` in-process.
     */
    private fun settingsDocument(): String {
        val json = Json { ignoreUnknownKeys = true }
        return json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                SettingsStore.NS_GENERAL.let { put(it, settings.get(it)) }
                SettingsStore.NS_MODEL.let { put(it, settings.get(it)) }
                SettingsStore.NS_ADDONS.let { put(it, settings.get(it)) }
                SettingsStore.NS_SESSIONS.let { put(it, settings.get(it)) }
            },
        )
    }

    companion object {
        private const val BIND_TIMEOUT_SECONDS = 5L
        private const val FAIL_INACTIVE = """{"ok":false,"error":"runtime not active"}"""
        private const val FAIL_NO_SOURCE = """{"ok":false,"error":"sandbox bootstrap lost"}"""
    }
}
