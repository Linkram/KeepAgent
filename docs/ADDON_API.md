# KeepAgent Addon API v1

The contract add-ons build against. Home modules: `addons-api` (Kotlin contract)
and `core/host` (lifecycle). Reference implementation: `addons/hello-tool`.

## Tiers

| Tier | Runs as | Isolation | Status |
|------|---------|-----------|--------|
| 1 | In-process Kotlin module (signature-bound) | Process | **M1** — `provider-openai`, `tools-core` |
| 2 | Interpreted TypeScript (bundled to JS) | quickjs-ng JS sandbox | **M1 (helper process)** |
| 3 | External process (MCP-like endpoint) | OS process | M3 |

M1 runs Tier-2 in a dedicated helper process (`:js`) behind the AIDL
boundary `KeepJsService` / `IKeepJsCallback` — one quickjs-ng engine per
add-on, so a crashing or hanging add-on costs the helper, not the app. If
the helper is unreachable the host falls back to the in-process engine;
the prelude and the add-on code are identical either way (setting:
`general/engineMode = in-process` forces the fallback explicitly).
See ADR-0001.

## The manifest — `addon.json`

```json
{
  "id": "io.example.my-addon",
  "name": "My Addon",
  "version": "0.1.0",
  "apiVersion": 1,
  "tier": 2,
  "entry": "index.js",
  "permissions": ["log", "workspace:read"],
  "provides": ["tool"],
  "depends": [],
  "minHost": "0.1.0"
}
```

| Field | Required | Notes |
|-------|----------|-------|
| `id` | yes | Reverse-domain, unique. `[a-z0-9]+(\.[a-z0-9-]+)+` |
| `name` | yes | Display name |
| `version` | yes | Semver `MAJOR.MINOR.PATCH` |
| `apiVersion` | yes | Must be in the host's supported set (M0: `1`) |
| `tier` | yes | `1`, `2`, or `3` |
| `entry` | yes | Tier-2: a `.js` file (bundle your TS) |
| `permissions` | no | Subset of the known permission names; unknown names **fail validation** |
| `provides` | no | Subset of the known capability ids; unknown ids **fail validation** |
| `depends` | no | `[{ "id": "io.example.x", "version": "1" }]` |
| `minHost` | no | Minimum host version |

Validation is a hard gate: an invalid manifest never initializes (spec §5.4).

## Permissions (known names, v1)

`log` · `settings:read` · `settings:write` · `workspace:read` ·
`workspace:write` · `network` · `device:screenshot` ·
`device:uiautomation` · `package:install` · `process:spawn`

The host grants nothing beyond what a valid manifest declares. The three
target permissions map to system flows (spec §3.1, §10): `device:screenshot`
→ MediaProjection, `device:uiautomation` → AccessibilityService + intent
launch, `package:install` → system installer UI (never silent),
`process:spawn` → subprocess execution under the `exec` approval class.

## Capabilities (known ids)

`tool` · `llm.provider` · `ui.panel` · `chat.renderer` · `workspace.source` ·
`test.runner` · `dev.toolchain` · `console.command` · `connection` ·
`notification` · `settings.schema`

M1 implements registration for `tool` (all tiers) and `llm.provider`
(Tier-1: the `provider-openai` add-on, id `openai-compatible`). The others
slot in as their milestones land.

## Test targets (`test.runner`, M2+)

A `test.runner` registration declares the target kinds it supports
(`web`, `desktop`, `android`, custom) and provides the observation contract
(spec §3.1): a screenshot stream, an event stream (console/logs), input
control (click/type/navigate), and structured capture (DOM, UI tree). Every
target event flows through the host event stream, so sessions are replayable
in the Console.

## Toolchains (`dev.toolchain`, M2+)

A toolchain add-on registers a language/runtime (id, version probe, binary
source, environment requirements) that the `run` tool and the build loop can
use (spec §8.1). First ships: esbuild, Node.js, git (native arm64 binaries).

## The `ka` host API (Tier-2)

Provided by the host prelude before your entry file runs. This is the **only**
surface a Tier-2 add-on can reach.

```js
ka.log(msg)                  // append to the host event stream (Console tab)
ka.workspacePath()           // active workspace root (string)
ka.settingsGet(namespace)    // JSON settings document for a namespace, or null
ka.registerTool(spec)        // register a tool; spec:
// {
//   name: string,               // required, unique per add-on
//   description?: string,
//   permission?: "read" | "write" | "exec" | "network" | "device",  // default "read"
//   inputSchema?: object,       // JSON Schema for args (informational in M0)
//   handler: (args, ctx) => any // required; returns the tool result
// }
```

The handler receives `(args, ctx)` where `ctx = { log, workspacePath }`.
Return a value — the prelude normalizes it to the host result contract:

| Handler returns | Host sees |
|-----------------|-----------|
| `"some string"` | `{"ok":true,"text":"some string"}` |
| `{ "text": "..." }` | `{"ok":true,"text":"..."}` |
| `{ "error": "..." }` | `{"ok":false,"error":"..."}` |
| anything else | `{"ok":true,"text": JSON.stringify(value)}` |

The same contract is what Tier-1 handlers return directly, so the agent
loop sees one shape from every tier.

## Lifecycle

```
discover (scan addons dir)
  -> validate (manifest)            invalid -> status INVALID, never runs
  -> enable                          tier 3  -> UNAVAILABLE (M3)
  -> initialize                      tier 1  -> in-process (M1)
                                     tier 2  -> sandbox: helper process over IPC,
                                                in-process fallback (M1)
                                     error   -> status FAILED, error in Console
  -> running                         registerTool calls populate the registry
```

Every step emits an event to the event stream — watch the Console tab.

## Writing a Tier-2 add-on

1. `addon.json` with a valid manifest.
2. `index.js` (IIFE; no ESM — quickjs-ng evaluates it as a global script).
3. Declare every permission you need; unknown names fail validation.
4. Test with the Add-ons tab: **Run tool** invokes your tool straight from
   the UI — no LLM required.
5. Bundle TypeScript with esbuild:
   `npx esbuild src/index.ts --bundle --format=iife --outfile=index.js`

## M1 limits (by design)

- **Synchronous tool handlers only.** The C bridge has no Promise resolution
  yet; `await` in a handler will hang. Async handlers land in M2.
- **No network / filesystem / timers** in the sandbox. `ka.*` is the only
  surface; file and network I/O are Tier-1 host capabilities today
  (`tools-core`), with sandbox-side host calls for `workspace:read`/`write`
  in M2.
- **One engine per add-on**, in the `:js` helper process (AIDL IPC), with
  automatic in-process fallback. Engines do not talk to each other (M2+).
- **No ESM / modules** — one flat script per add-on.
