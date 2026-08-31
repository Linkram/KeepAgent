# KeepAgent

A modular AI agentic development app for Android. A thin core runtime + a
versioned Addon API; add-ons are the product, the host is small enough to
audit. Design doc: [`../DEV_SPEC.md`](../DEV_SPEC.md) · Feature backlog:
[`../FEATURES.md`](../FEATURES.md) · Addon guide: [`docs/ADDON_API.md`](docs/ADDON_API.md).

> **Status: M0 scaffold.** The app shell (6 tabs, castle-keep visual theme,
> stone top bar) is real Compose UI; the Tier-2 add-on loop (quickjs-ng
> sandbox → manifest validation → tool registration → tool invocation) is
> implemented and wired to the UI. LLM chat, workspaces, and the test runner
> land in M1–M3. See [What's in M0](#whats-in-m0).

## Prerequisites (to build)

| Tool | Version | Notes |
|------|---------|-------|
| JDK | 17 | JDK 8 will not work (AGP 8.7 requires 17) |
| Android SDK | platform 35, build-tools 35 | via Android Studio or `sdkmanager` |
| NDK | r26+ | builds `libkeepagent_js.so` (quickjs-ng) |
| CMake | 3.22.1 | installed with the NDK / SDK packages |
| Gradle | 8.10.2 | via the included wrapper — no install needed |

`local.properties` must point at the SDK:

```
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

## Build

```powershell
# from this directory
.\gradlew.bat :app:assembleDebug
```

First build downloads Gradle 8.10.2 and the AGP/Compose dependencies, then
compiles the native bridge for the ABIs in `runtime-js/build.gradle.kts`
(arm64-v8a, armeabi-v7a, x86_64). The APK lands in `app/build/outputs/apk/debug/`.

Install and run:

```powershell
adb install app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n io.keepagent/.MainActivity
```

## What's in M0

- **Shell**: 6 tabs — `Chat · Test · Workspaces · Console · Add-ons ·
  Connections` — with the mockup's stone top bar, model header + context
  gauge, and a subtle wall backdrop. The theme is **visual only**: tab labels
  and all code identifiers are plain (no themed naming).
- **Tier-2 add-on loop (the spike, spec §13)**: quickjs-ng embedded in-process
  (ADR-0001) behind a JNI bridge. `core/host` discovers add-ons, validates
  `addon.json` against Addon API v1, evaluates the entry in an isolated JS
  context, collects tool registrations, and the Add-ons tab has a
  **Run tool** button that invokes a tool end to end — no LLM involved.
- **Event stream**: append-only JSONL (`core/events`), live view in the
  Console tab. Add-on init, validation failures, tool invocations, and logs
  all flow through it.
- **hello-tool sample add-on**: seeded from assets into the on-device addons
  dir on first run; registers the `hello` tool.
- **Settings + storage primitives**: namespaced JSON settings, content-addressed
  attachment store (used by M1+).

M0 deliberately in-process: the JS world is the sandbox; separate-process
isolation + IPC is the first M1 task (the JNI bridge is already shaped so the
`.so` can move to a helper process without touching add-on code).

## Module map

```
app          Compose shell: 6 tabs, theme, wiring (io.keepagent)
addons-api   Addon API v1 contract — pure Kotlin/JVM, no Android
core/events  AgentEvent + EventBus (SharedFlow) + EventLog (JSONL)
core/settings  namespaced JSON settings + FileAccess toggle (ADR-0002)
core/storage   app data layout + content-addressed attachments
core/host    AddonManager: discover → validate → enable → init; CapabilityRegistry
runtime-js   quickjs-ng JNI bridge (CMake + NDK) + Kotlin JsHost wrapper
addons/      shipped add-on source (hello-tool)
```

## Add-ons

See [`docs/ADDON_API.md`](docs/ADDON_API.md). Short version: a directory with
`addon.json` (manifest) + `index.js` (bundled JS). The only host surface is
the `ka` object (`log`, `workspacePath`, `settingsGet`, `registerTool`).
Reference: `addons/hello-tool`.

## Vendored code

`runtime-js/third_party/quickjs-ng/` is a shallow clone of
[quickjs-ng/quickjs](https://github.com/quickjs-ng/quickjs). Only four
library sources are compiled (`dtoa.c libregexp.c libunicode.c quickjs.c`);
`quickjs-libc.c` and the CLI tools are **not** included.

## Known gaps (tracked in FEATURES.md)

- No LLM provider yet — Chat shows a static mockup sample (M1, F-001/F-004).
- Tool handlers are synchronous in M0; `await` in a handler hangs (M1).
- No file/network I/O in the sandbox yet (M1 host calls behind permissions).
- Tier-1 and Tier-3 runtimes are stubs (M1–M3).
- UI polish: the full keep-themed pass (F-014) is M2.
