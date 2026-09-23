package io.keepagent.app.runtime

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process

/** Disposable process boundary for untrusted local Python and Java execution. */
class RuntimeWorkerService : Service() {
    private val engine by lazy { LocalRuntimeEngine(applicationContext) }

    private val binder = object : LocalRuntimeService.Stub() {
        override fun info(): String = engine.info()
        override fun runPython(requestJson: String, workspacePath: String): String =
            engine.runPython(requestJson, java.io.File(workspacePath))

        override fun runJava(requestJson: String, workspacePath: String): String =
            engine.runJava(requestJson, java.io.File(workspacePath))

        override fun cancel() {
            // This service has no app state. Killing its dedicated process is the
            // only reliable way to stop native/compiler/Python code immediately.
            Process.killProcess(Process.myPid())
        }
    }

    override fun onBind(intent: Intent): IBinder = binder
}
