# KeepAgent

A modular AI agentic development app for Android. A thin core runtime + a
versioned Addon API; add-ons are the product, the host is small enough to
audit. Ambition: desktop-agent parity (opencode / Claude Code class) on any
phone — the on-device dev loop, with three test targets (web, desktop,
Android) and a remote runner (ADR-0003). Design doc:
[`../DEV_SPEC.md`](../DEV_SPEC.md) · Feature backlog:
[`../FEATURES.md`](../FEATURES.md) · Addon guide:
[`docs/ADDON_API.md`](docs/ADDON_API.md).

> **Status: M1 — chat agent with tools.** An OpenAI-compatible provider
> add-on streams a real agent loop: model → tool calls → approval gate →
> file tools (read/write/edit/glob/grep) → back to the model. One workspace
> (create/switch/delete), the quickjs-ng sandbox now runs in a helper
> process over AIDL IPC (with automatic in-process fallback). LLM chat,
> thinking blocks, and the approval UI are live. The on-device toolchain and
> the three test targets (web / desktop / Android) land in M2; the remote
> runner in M3. See [What's in M1](#whats-in-m1).

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

## First run

1. Open the **Chat** tab. The header shows `no model configured`.
2. Tap the gear icon → enter the model profile: **base URL**, **API key**,
   **model** (any OpenAI-compatible endpoint — OpenAI, DeepSeek,
   OpenRouter, Ollama, vLLM, local gateways).
3. The model dropdown loads from `GET /models`; pick a model, type a
   message, send.
4. File tools ask before every write/exec/network/device action
   (approval mode `ask`, the default). Watch the Console tab for the full
   event stream.

Settings keys (namespaced JSON in `SettingsStore`):

| Namespace | Key | Values |
|-----------|-----|--------|
| `model` | `baseUrl`, `apiKey`, `model` | model profile |
| `general` | `approvalMode` | `ask` (default) · `auto-allow` · `never-ask` |
| `general` | `toolAllowlist` | comma list, e.g. `read,glob,grep` — passes silently in `auto-allow` |
| `general` | `fileAccess` | `workspace` (default, ADR-0002) · `full` |
| `general` | `engineMode` | `process` (default — sandbox in `:js` helper) · `in-process` |
| `sessions` | `activeWorkspace` | active workspace name |
| `connections` | `list`, `activeId` | API connections (JSON array) + the active one, mirrored into `model` |

## What's in M1

- **Shell**: 6 tabs — `Chat · Test · Workspaces · Console · Add-ons ·
  Connections` — with the mockup's stone top bar, model header + context
  gauge, and a subtle wall backdrop. The tab tiles carry the mockup's sigil
  icons (bubble, play, folder, terminal, puzzle, plug) as vector drawables.
  The theme is **visual only**: tab labels and all code identifiers are plain
  (no themed naming).
- **Connections (F-010 surface)**: named OpenAI-compatible API endpoints —
  OpenRouter, a local server, or any custom base URL. Add/edit/delete, and
  set the active one: its `baseUrl` / `apiKey` / `model` mirror into the
  `model` profile that the provider and the chat model chip read, so
  switching connections changes the model without a restart. **Test**
  (on each row and in the add/edit dialog) hits `GET /models` and reports
  `OK — N models` or the real error (`HTTP 401`, host failure, …). The
  chat model dropdown lists the fetched models and offers **Retry fetch**
  when the fetch failed. The app holds `INTERNET` (and cleartext traffic
  for local endpoints); targetSdk 35's edge-to-edge is handled with
  `imePadding()`/`navigationBarsPadding()` so the keyboard only compresses
  the chat area.
- **LLM provider (F-001)**: `core/llm` — an OpenAI-compatible SSE client
  (streaming deltas, tool calls, `reasoning_content` thinking, final usage)
  wrapped by the Tier-1 `provider-openai` add-on. The provider re-reads the
  model profile on every use — changes apply without a restart.
- **Streaming chat (F-004)**: token-by-token text, a collapsible "Thought"
  block for reasoning tokens, live tool-call lines (read/glob/grep/write/
  edit with ok/error/denied states), and an error line when a turn fails.
- **Agent loop + approval gate (F-002/F-009)**: `core/agent` runs up to 12
  model rounds per turn. `ASK` (default) pauses the loop on an amber
  approval card showing the tool, its permission class, and its arguments;
  `AUTO_ALLOW` passes allowlisted tools silently; `NEVER_ASK` is a full-trust
  toggle.
- **File tools (F-007)**: `core/fs` (`read`, `write`, `edit`, `glob`,
  `grep`) behind the Tier-1 `tools-core` add-on. The ADR-0002 toggle in the
  header confines every path to the active workspace (escapes are blocked
  and logged) or lifts it.
- **Workspaces**: `core/workspace` — one active workspace, create/switch/
  delete from the Workspaces tab; the selection survives restarts. Switching
  re-roots the file service.
- **Sandbox in a helper process (spec §13, ADR-0001)**: the quickjs-ng
  engines run in the `:js` process behind a small AIDL boundary
  (`KeepJsService` + `IKeepJsCallback`) — one engine per add-on, so a
  crashing or hanging add-on costs the helper, not the app. If the helper
  is unreachable the runtime falls back to the in-process engine (same
  prelude, same add-on code).
- **Tier-2 add-on loop (the M0 spike, still working)**: manifest validation,
  isolated JS context, tool registration, and the Add-ons tab's **Run tool**
  button — now over IPC.
- **Event stream**: append-only JSONL (`core/events`), live view in the
  Console tab. Every step — add-on init, validation failures, approvals,
  tool invocations, model errors — flows through it.
- **hello-tool sample add-on**: seeded from assets into the on-device addons
  dir on first run; registers the `hello` tool.

## Module map

```
app          Compose shell: 6 tabs, theme, wiring, ChatController (io.keepagent)
addons-api   Addon API v1 contract — pure Kotlin/JVM, no Android
core/llm     OpenAI-compatible SSE client (streaming, tools, reasoning)
core/agent   AgentLoop, AgentRun, ApprovalGate (ask / auto-allow / never-ask)
core/events  AgentEvent + EventBus (SharedFlow) + EventLog (JSONL)
core/settings  namespaced JSON settings + FileAccess toggle (ADR-0002)
core/storage   app data layout + content-addressed attachments
core/fs      policy-scoped file access: read/write/edit/glob/grep/listDir
core/workspace   workspace create/switch/delete, active selection
core/host    AddonManager: discover → validate → enable → init; CapabilityRegistry;
             JsAddonRuntime (in-process) + HelperAddonRuntime (IPC + fallback)
runtime-js   quickjs-ng JNI bridge (CMake + NDK) + JsHost + JsSandboxService
             (helper process, AIDL) + the shared `ka` prelude
addons/      shipped add-ons: provider-openai, tools-core, hello-tool
```

## Add-ons

See [`docs/ADDON_API.md`](docs/ADDON_API.md). Tier 1 is Kotlin compiled into
the APK (`provider-openai`, `tools-core`); Tier 2 is a directory with
`addon.json` (manifest) + `index.js` (bundled JS) running in the quickjs-ng
sandbox (helper process). The only Tier-2 host surface is the `ka` object
(`log`, `workspacePath`, `settingsGet`, `registerTool`). Reference:
`addons/hello-tool`.

## Vendored code

`runtime-js/third_party/quickjs-ng/` is a submodule of
[quickjs-ng/quickjs](https://github.com/quickjs-ng/quickjs). Fresh clones
need:

```powershell
git submodule update --init
```

Only four library sources are compiled (`dtoa.c libregexp.c libunicode.c
quickjs.c`); `quickjs-libc.c` and the CLI tools are **not** included.

## Known gaps (tracked in FEATURES.md)

- **Sandbox handlers are synchronous** — `await` in a Tier-2 handler hangs
  (async host calls: M2).
- **No filesystem/network inside the sandbox** — Tier-2 tools have no I/O
  yet; all file tools are Tier-1 (host calls with `workspace:read/write`
  permissions: M2).
- **One chat session, no persistence** — multi-session + session history:
  M2. Images in chat: M2.
- **No test targets yet** — web (WebView), desktop profile, and native
  Android are M2 (F-006/F-013/F-015); the on-device toolchain (F-016) and
  the remote desktop runner (M3, F-018) are not built.
- **Tier-3 runtime (MCP-like endpoints)** is a stub (M3).
- **UI polish**: the full keep-themed pass (F-014) is M2.
