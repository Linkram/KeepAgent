# Project import and context validation

Updated 2026-09-05 with the runner and mobile UX implementation.

## Computer checks

Command from `keepagent/`:

```powershell
.\gradlew.bat :core:workspace:testDebugUnitTest :core:agent:test :app:assembleDebug --offline
```

Result: BUILD SUCCESSFUL, including the final nullable-parent cleanup and removal
of the unused system-prompt method. New tests: four importer tests and one project
context test. Existing core agent tests also passed. Fixtures live in
`test-fixtures/project-import/`.

Final Android verification command was `gradlew test lint :app:assembleDebug
--continue --offline`: **BUILD SUCCESSFUL**, 592 tasks (34 executed, 558 up-to-date).
The companion's eight tests all passed, covering persistent command jobs, auth,
path containment, idempotency, reconnection, timeout/cancel, output paging, passing
and failing Chromium assertions, real Electron interaction/native screenshot, and
MCP discovery/invocation.

The exact-serial Android worker passed against the dedicated API 35 emulator
`emulator-5556`: KeepAgent was foreground, expected UI text was present, and the job
retained `page.png`, `report.json`, and `ui.xml`. This serial was explicitly supplied;
the worker does not select from connected devices.

The app unit suite now includes public Git URL acceptance and rejects local schemes,
scp/SSH syntax and embedded URL credentials. A local JGit fixture confirms cloning into
the workspace manager's pre-created empty staging directory retains its checkout and
`.git` data. Handoff tests cover schema versioning, bounded fields, instruction-file
references, and omission of raw instruction contents/hashes from prompt context.

Credential migration was exercised on `emulator-5556` with the synthetic marker
`SYNTHETIC_SECRET_9f7a`. After app startup, `run-as io.keepagent` showed neither
plaintext occurrence in SharedPreferences: the model key and complete connection
record were separate randomized `kae1:` AES-GCM ciphertexts. No physical-phone or
cloud files were accessed for this test.

After these additions, `gradlew test lint :app:assembleDebug --continue --offline`
completed successfully: 592 actionable tasks (117 executed, 475 up-to-date). The final
APK was installed only on `emulator-5556`; the project-clone chooser passed visual and
UI-hierarchy inspection. Hardware Back from the file explorer now returns to **Switch
project** (`back-to-project-chooser=PASS`) instead of jumping to Chat.

## Physical device smoke check

An earlier build of this change installed successfully on the connected Galaxy A35
and launched. Workspaces showed the import action, naming field, explanatory copy,
and existing projects. The Android system document picker opened.

End-to-end project ZIP extraction on Android was **not verified**. Picker navigation was inconclusive
and stopped at the owner's request. Do not browse the owner's device files or cloud
storage to continue validation. No Drive location was opened. Further testing should
use computer unit tests or a dedicated emulator with synthetic storage.

Test artifacts left on the phone: `Download/keepagent-import-smoke.zip` and
`/sdcard/keepagent-import-ui.xml`. These are test-generated artifacts, not user data.
They were left in place to avoid further device file access. The final computer APK
contains a small cleanup after the installed build; it has not been reinstalled.

## Test tab information hierarchy

The Test tab was redesigned around one linear workflow: choose a page, open and
inspect it, then send evidence to the agent. Viewport selection moved into one
labeled menu; reload, history, browser, fullscreen, rotation, refresh, and save
actions moved into overflow. Empty workspaces now show one starter-page action,
and all primary custom targets have a 48 dp minimum size. Screenshot evidence is
now PNG-compressed before it is labeled and attached as `image/png`.

`gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline`
completed successfully after the redesign (381 actionable tasks; 21 executed,
360 up-to-date). Emulator interaction and final physical-device installation are
recorded below once completed.

## Standalone runtime and keep-theme validation

Version `0.1.0-m1.4j` bundles Chaquopy Python and pinned pytest/requests packages
for ARM64 phones and x86_64 emulators alongside QuickJS. The on-device runtime card
was exercised on `emulator-5556`; its live result was Python 3.13.9, pytest 9.1.1,
and requests 2.34.2. The redesigned shell was visually inspected at 1080 × 2400:
header battlements, low-contrast masonry, stone navigation and sage actions remain
behind the same four-destination hierarchy. Screenshot: `app/build/keep-runtime-tools.png`.

`gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --offline`
completed successfully after the runtime and theme work (390 actionable tasks;
58 executed, 332 up-to-date). The debug APK is 59,586,082 bytes because the two
native Python ABIs and bundled packages are included for offline first use.

## Not established

Project-aware Test follow-up: app unit tests, lint and debug assembly passed.
On the dedicated emulator, discovery found the synthetic `test_mobile_checks.py`
alongside HTML preview. Tapping Run Python tests reported exit 0 and
`1 passed in 0.27s`; expanding output revealed the actual pytest result.
Visual inspection found and corrected inherited black text on the dark background.
Discovery unit tests cover mixed projects and excluded dependency/build fixtures.
See ADR-0009 for current session-history and cancellation limitations.

- Performance or task-success gains on the owner's local model.
- Full desktop harness conversation migration or dependency restoration.
- Arbitrary PyPI/native dependency installation, Node/npm compatibility, GNU
  userland, Android SDK builds, and hard timeout/cancellation for Python execution.
- Process-death recovery for import, executable bits, symlinks, Git worktree pointers.
- General script execution, headless browser automation, native desktop testing,
  and subagents were subsequently implemented through the companion or agent core.
  Native Windows/macOS UI automation beyond actual Electron and project-provided test
  commands, hosted execution, external handoff import, and release hardening remain.
