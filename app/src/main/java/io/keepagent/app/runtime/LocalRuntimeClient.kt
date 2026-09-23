package io.keepagent.app.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synchronous IPC client; callers invoke it from a worker dispatcher. */
class LocalRuntimeClient(private val context: Context) {
    @Volatile private var service: LocalRuntimeService? = null
    @Volatile private var connection: ServiceConnection? = null
    private val bindLock = Any()

    fun info(): String = invoke { it.info() }
    fun runPython(raw: String, workspace: String): String = invoke { it.runPython(raw, workspace) }
    fun runJava(raw: String, workspace: String): String = invoke { it.runJava(raw, workspace) }

    fun cancelActive() {
        val current = service
        if (current != null) runCatching { current.cancel() }
        disconnect()
    }

    private fun invoke(block: (LocalRuntimeService) -> String): String {
        val remote = ensureBound()
            ?: return """{"ok":false,"error":"local runtime worker unavailable"}"""
        return try {
            block(remote)
        } catch (e: Exception) {
            disconnect()
            """{"ok":false,"error":"local runtime stopped or crashed"}"""
        }
    }

    private fun ensureBound(): LocalRuntimeService? = synchronized(bindLock) {
        service?.let { return it }
        val latch = CountDownLatch(1)
        var connected: LocalRuntimeService? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                connected = LocalRuntimeService.Stub.asInterface(binder)
                service = connected
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }

            override fun onBindingDied(name: ComponentName?) {
                service = null
            }
        }
        val bound = runCatching {
            context.bindService(Intent(context, RuntimeWorkerService::class.java), conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!bound) return null
        connection = conn
        if (!latch.await(5, TimeUnit.SECONDS)) {
            disconnect()
            return null
        }
        connected
    }

    private fun disconnect() = synchronized(bindLock) {
        connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        service = null
    }
}
