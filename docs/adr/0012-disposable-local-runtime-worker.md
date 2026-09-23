# ADR-0012: Disposable local runtime worker

Date: 2026-09-05
Status: Accepted, partial delivery of AG-06 and RT-06

Bundled Python and Java execution runs in the private `:runtime` application
process through a small AIDL interface. The main process sends only the request
JSON and captured canonical workspace path. Runtime initialization, Python state,
Java compilation, dex loading, and project code stay outside the UI/agent process.

Stop asks the worker to kill its own process. Binder failure is converted to a
bounded stopped/crashed result, the client drops the dead connection, and a later
run binds a clean worker. The foreground-service claim remains held until the
calling coroutine records its final result, so Android protection is not removed
during cleanup.

This is crash containment and force-cancellation, not a filesystem security
sandbox: Android processes belonging to one application share its UID. The worker
can access the app-private workspace path it is given and currently inherits the
app's network permission. Strong network-off/filesystem isolation still requires
a narrower UID/VM design or a brokered file API and remains an RT-06 release gate.

Validation (2026-09-05): unit tests, Android lint, and debug assembly passed. On
the dedicated API 35 emulator, pytest executed in an independently visible
`io.keepagent:runtime` process and returned a passing result. That worker was
terminated under the app UID while the UI and `:js` process remained alive; the
next test created a new worker PID and passed.
