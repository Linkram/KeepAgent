# ADR-0011: Background agent service and test-result recovery

Date: 2026-09-05
Status: Accepted, partial delivery of AG-12/AG-13

Agent service startup belongs to dispatch after model validation and the running
state transition, so retries receive the same protection as new messages and a
missing model cannot leave an idle service. Service startup rejection is reported
in the activity log. The service is not sticky: recreating it cannot restore an
in-memory inference turn and must not advertise nonexistent work.

Notifications contain no project content on the lock screen, open MainActivity,
show elapsed time, and address the stop receiver explicitly. Tools provides the
Android notification permission/settings entry point. Permission denial does not
prohibit a foreground service but can hide its notification from the drawer.

Agent turns and project checks register named claims with one application-level
coordinator. The service remains active until the final claim is released, so a
completed agent turn cannot remove protection from a concurrent local check. Stop
requests cancellation from every claim owner; the service remains until cleanup
finishes, including while an in-process runtime unwinds.

The service holds one non-reference-counted partial wake lock only for its own
lifetime. This keeps CPU work eligible to progress after the display turns off;
the lock is released in `onDestroy`, and the coordinator stops the service once
the last active claim finishes.

Chat checkpoints explicitly persist whether the last turn was still executing.
The first checkpoint is queued immediately after dispatch and later checkpoints
are serialized through one writer. After process recreation, such a turn is shown
as interrupted at its last saved phase and is never presented as completed or
silently replayed.

Local check results use an AtomicFile journal in app-private storage. The last
20 results survive restart. A saved running result becomes an interrupted failure;
project code is never replayed automatically. Read/write work occurs off the UI
thread. Save failures are reported without preventing execution.

Remaining gates: isolated forcibly-cancellable runtime workers, foreground protection for
servers/pack jobs, OEM battery-restriction guidance and device endurance tests, background approval alerts,
automatic phase-aware continuation, and physical-device interruption/notification tests.
This change does not claim those gates passed.

Validation (2026-09-05): app unit tests, Android lint, and debug APK assembly
passed. On the dedicated x86_64 emulator, a bundled Python test passed, its
app-private journal was verified, and the result remained visible after force-stop
and relaunch. A serialization round-trip regression test covers the journal model;
the app module must apply the Kotlin serialization compiler plugin. Screen-off
execution and mid-run process-death recovery have not yet been device-validated.

References:
- https://developer.android.com/develop/background-work/services
- https://developer.android.com/develop/ui/compose/notifications/notification-permission
