# ADR-0009: Project-aware Test workspace

Status: accepted, 2026-09-05.

Test opens a project overview. Bounded, read-only discovery identifies pytest suites,
HTML previews and unsupported Node/Gradle projects. Discovery never executes code.
WebView remains a nested preview, with Back returning to the overview. A direct
Run Python tests tap authorizes that run; agent tool calls retain their approval gate.

Runs capture their workspace before dispatch, use an app-owned coroutine, and remain
visible across tab changes. Recent results include timestamp, actual success/failure
and expandable output. Sending evidence appends to the existing Chat draft for review.
History currently lasts for the app process and is limited to 20 runs. Persistent
history, separately killable Python workers, cancellation, and local browser automation
remain necessary follow-ups. Do not show unsupported toolchains as runnable.

Python output is bounded during capture, rather than only after execution. Entry-path
containment is not a Python security sandbox: scripts retain application permissions.

UX follows contextual actions and indeterminate progress for unknown durations:
https://developer.android.com/develop/ui/compose/components/progress
