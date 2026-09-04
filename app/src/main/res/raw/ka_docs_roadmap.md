# KeepAgent — Execution Roadmap

Where the project is going and in what order. The product vision lives in
`DEV_SPEC.md` (§1, ADR-0003); the feature IDs below are defined in
`../FEATURES.md`. This doc turns that into a sequence with exit criteria.

## Mission

The first AI agent designed from the ground up for mobile: everything a
desktop harness does — plan, build, run, **test and debug on its own** —
with a mobile experience, and small local models as the primary target.

**Definition of done (owner's words):** the app isn't done until developing
whole programs and working on real codebases is comfortable from a phone —
no computer in the loop.

## North-star acceptance test

The agent, from the phone alone, with a local or LAN model:

1. Is given a project goal and writes a plan the user can inspect and edit.
2. Writes a web app in a workspace, bundles it with an on-device toolchain.
3. Serves it, loads it in the web runner (phone + desktop profiles), and
   reads console/DOM/screenshot evidence itself.
4. Finds the bug from that evidence, fixes it, re-runs, until the checks pass.
5. Reports results with links to the commands, logs, and screenshots.

That is M2's exit criterion (DEV_SPEC §13) plus F-028. Everything below
orders the work toward it.

## Priority rule: small local models first (F-026)

Local 1–14B-class models are the default optimization target. Consequences
for every workstream:

- **Forgive malformed tool calls.** Models emit trailing commas, code fences,
  unquoted keys, prose around JSON, raw newlines in strings. The harness
  repairs rather than failing (WS-1).
- **Compact context.** Tool results stay short and bounded; long output is
  summarized with a pointer to the source. History replay keeps what a weak
  model needs, not the full transcript (WS-1, WS-4).
- **Few, unambiguous tools.** Tool descriptions and schemas are written for
  a weak reader; overlapping tools are merged before new ones are added.
- **Explicit state over remembered intent.** Plans/checkpoints are files the
  model can re-read, because context gets truncated (WS-4).
- **Budgets everywhere.** Rounds, tokens, and wall-clock budgets are visible
  and interruptible; a small model that loops must stop cleanly (WS-2).

## Workstreams

### WS-1 — Small-model reliability (F-026) — *start now*
1. ✅ Lenient tool-argument repair in the agent loop (fences, trailing
   commas, unquoted keys, prose-wrapped JSON, raw control chars) — canonical
   JSON downstream, event logged when repair happened.
2. Small-model system prompt: tool guidance, "one action at a time", plan
   file pointer. Toggled per connection (small-model profile).
3. Compact tool results: caps + truncation notes already exist; add
   summarize-then-pointer for large read/grep results under the profile.
4. History policy: include recent tool rounds (last N) so the model sees
   what it already read — currently only user/assistant text is replayed.
5. Robustness debt: helper-process death rebind, sandbox settings snapshot
   staleness, session autosave size (images → attachment store), cold-start
   addon readiness gate, provider/tool-name collision rules.
6. Eval: a scripted small-model task (read → edit → test → fix) with
   reproducible traces, run against Ollama/vLLM endpoints.

**Exit:** the north-star test passes against a ≤14B model on a LAN endpoint.

### WS-2 — Close the loop: agentic web testing (F-006, F-028, F-013)
1. `target-web` Tier-1 addon exposing the WebView runner to the agent:
   `web_load`, `web_console`, `web_dom`, `web_screenshot`, `web_click`,
   `web_type` — one runner contract, events on the stream.
2. Headless checks: run a page, assert console errors/visible text/element
   presence, return a structured pass/fail report (the "test" verb for web).
3. Desktop profile on the same runner: desktop viewport + UA + input
   (F-013 phase 1). TestTab viewport presets already exist — promote them
   into the runner contract.
4. Autonomous run–observe–fix: a bounded loop tool (`run_checks`) that runs
   → observes → reports within one agent turn, interruptible, with a step
   budget; final report links each check to its evidence (F-028).
5. Job plumbing: long checks become foreground-service jobs (the existing
   `AgentForegroundService` is the seed).

**Exit:** the north-star test steps 3–5 work without human screenshot taps.

### WS-3 — On-device toolchain (F-016)
1. `core-process`: spawn with cwd, streamed stdout/stderr to the event
   stream, kill, job registry. Android is Linux — arm64 userland binaries.
2. Toolchain addons (`dev.toolchain`): esbuild (static Go binary), Node.js,
   git (JGit already covers repo ops; native git later for remotes).
3. `run` + `shell` tools (exec class, allowlist-gated) behind the approval
   gate — with `never-ask` as the power-user posture.
4. `core-process` is the same service WS-2's headless checks and WS-5's
   builds use.

**Exit:** the north-star test step 2 (bundle) runs on-device.

### WS-4 — Project plans & resumable sessions (F-027)
1. Per-workspace `PLAN.md` (structured: goals, tasks with state, decisions,
   acceptance checks) — a file, editable by user and agent, always
   re-readable.
2. `plan` tool surface: read/update task states; the agent's system prompt
   points at it.
3. Checkpoints: compact session summary written on turn completion; a new
   session (or a different, weaker model) resumes from the checkpoint +
   plan, not the transcript.
4. Chat history context: WS-1 item 4 feeds this — history + plan + checkpoint
   is the small model's working memory.

**Exit:** kill the app (or switch to a smaller model) mid-project; the next
session continues correctly from the plan.

### WS-5 — Android target + reach (F-015, F-017, F-018)
1. APK install via system installer, launch by intent, drive via
   AccessibilityService, screenshot via MediaProjection — one-time guided
   permission setup.
2. SAF export/import of workspaces; git remote flow.
3. Remote runner (Tier-3, Go daemon): desktop browser, emulators, CI — the
   escape hatch.

**Exit:** an Android app written on the phone is installed, launched,
driven, and screenshotted from the Test tab.

### WS-6 — Mobile experience (F-025, F-023)
1. Two-tab rule: any edit → run → inspect → fix takes ≤2 tab changes; state
   (scroll, selection, active job) survives switches.
2. Job control surface: active jobs always visible (header dot exists),
   tap → detail → kill/output.
3. Full theme pass, empty states, app icon; split view on wide/landscape
   (F-019) when space allows.
4. Feel: animation and latency budget for streaming (tokens, tool lines,
   screenshots); haptics on approval decisions.

**Exit:** the owner uses it daily for real projects and stops reaching for a
desktop.

## Sequencing

```
now ──► WS-1 (reliability) ──► WS-3 (process/toolchain) ──► WS-2 (web loop)
              │                        │
              └── WS-4 (plans) weaves in as WS-2 grows
                        WS-5 (Android target) ──► WS-6 (feel) ──► done
```

WS-1 and WS-3 first: nothing in the north-star test works without reliable
tool calls and a process to run things. WS-2 is the product climax. WS-4
lands early enough that multi-session work is already comfortable by the
time WS-2 ships.

## Verification

- Every workstream item lands with: unit tests where the module is pure JVM,
  a build that passes, and an event-stream-visible trace of the behavior.
- On-device verification (install the debug APK, drive the north-star
  scenario) at the end of each workstream, logged in this file.
- `build-m*.log` at the repo root is the current build trail; keep it current.
