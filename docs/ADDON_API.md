# KeepAgent Addon API v1

The contract add-ons build against. Home modules: `addons-api` (Kotlin contract)
and `core/host` (lifecycle). Reference implementation: `addons/hello-tool`.

## Tiers

| Tier | Runs as | Isolation | Status |
|------|---------|-----------|--------|
| 1 | In-process Kotlin module (signature-bound) | Process | M1–M2 |
| 2 | Interpreted TypeScript (bundled to JS) | quickjs-ng JS sandbox | **M0 (in-process)** |
| 3 | External process (MCP-like endpoint) | OS process | M3 |

M0 runs Tier-2 in-process; M1 moves the engine to a helper process (same
prelude, IPC transport). See ADR-0001.

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

## Permissions (known names, M0)

`log` · `settings:read` · `settings:write` · `workspace:read` ·
`workspace:write` · `network` · `device:screenshot`

The host grants nothing beyond what a valid manifest declares.

## Capabilities (known ids)

`tool` · `llm.provider` · `ui.panel` · `chat.renderer` · `workspace.source` ·
`test.runner` · `console.command` · `connection` · `notification` ·
`settings.schema`

M0 implements registration for `tool`; the others slot in as their
milestones land.

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
Return a value — it is JSON-serialized to the host (M0: objects with a `text`
field are conventional).

## Lifecycle

```
discover (scan addons dir)
  -> validate (manifest)            invalid -> status INVALID, never runs
  -> enable                          tier 1/3 -> UNAVAILABLE in M0
  -> initialize (eval entry)         error  -> status FAILED, error in Console
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

## M0 limits (by design)

- **Synchronous tool handlers only.** The C bridge has no Promise resolution
  yet; `await` in a handler will hang. Async handlers land in M1.
- **No network / filesystem / timers** in the sandbox. I/O goes through
  `ka.*` and host permissions (M1: `workspace:read`/`write` host calls).
- **In-process engine.** The sandbox is the JS world, not a separate process
  (M1, per ADR-0001).
- **No ESM / modules** — one flat script per add-on for M0.
