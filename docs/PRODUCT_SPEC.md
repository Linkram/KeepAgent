# KeepAgent product and engineering specification

Status: normative pre-release product specification  
Version: 1.0-draft. “Draft” permits reviewed changes before 1.0; every SHALL/MUST, requirement ID, acceptance gate, security boundary, and definition is binding now unless an ADR explicitly supersedes it.  
Updated: 2026-09-05

This document is sufficient context for designing, implementing, testing, and reviewing KeepAgent. When another document conflicts with it, this specification wins unless a newer ADR explicitly amends it. A capability is not shipped merely because UI, detection, a schema, or documentation exists: its acceptance tests must pass on supported physical phones.

## 1. Product definition

KeepAgent is an open-source, mobile-first agentic software-development harness. It combines the practical coding-agent capabilities of Codex, Claude Code, Hermes, and DeepSeek-oriented harnesses with an interface designed to be preferable on a phone. A developer can import a real repository, continue its existing task, ask an agent to change it, run and interact with the result, diagnose failures from evidence, review the diff, and sync or export the work without requiring a computer.

The intended experience is: **“It can do anything I reasonably ask a coding agent to do, and the phone is a first-class development machine.”**

### 1.1 Non-negotiable promises

1. **Standalone by default.** Supported source projects build, run, display, accept input, and produce debugging evidence on the phone. A paired computer is never a prerequisite for a supported mobile workflow.
2. **Real execution.** A preview, parser, syntax check, detected filename, mock result, or console-only runner does not count as application/game support.
3. **Desktop reach without dependence.** Windows, macOS, Linux GUI, Electron, and other desktop-only artifacts can use an optional paired-runner add-on. Pairing UI and schemas stay out of ordinary model context until enabled.
4. **Harness parity.** File operations, search, scripts, shells, tests, browsers, subagents, plans, approvals, long-running jobs, history, artifacts, providers, MCP/add-ons, Git, and recovery form one coherent agent loop.
5. **Import and continue.** Repositories used with desktop harnesses can be imported without restructuring. Source, Git state, instruction files, plans, and neutral handoff state are preserved or clearly reported missing.
6. **Small-model first.** The system is deliberately legible to local small and medium models, including the owner's roughly 27B model. Context efficiency is measured through task success, not assumed from prompt length.
7. **Truthful capability reporting.** The UI distinguishes ready, installable, incompatible, permission-blocked, and failed states. It never labels planned work as available.
8. **User-owned boundaries.** KeepAgent accesses only projects and locations the user deliberately imports or grants. It does not enumerate unrelated phone files or cloud drives.

### 1.2 Product goals

- Make completing a real coding task from a phone feel faster and calmer than remotely driving a desktop agent.
- Support end-to-end development across web, Python, JVM/Android, JavaScript, C/C++, and extensible additional ecosystems.
- Let agents autonomously run, observe, fix, and re-run within explicit budgets.
- Preserve user control through reviewable plans, diffs, approvals, job controls, permission explanations, and reversible operations.
- Be a dependable open-source foundation with stable extension contracts, reproducible runtime packs, ADRs, tests, and migrations.

### 1.3 Non-goals and hard platform boundaries

- Android does not directly execute Windows `.exe`, macOS bundles, or binaries built for another OS/architecture. Those use the optional matching desktop target; this is the only acceptable reason a compatible computer is required.
- Compatibility does not mean every dependency runs unchanged. If a dependency has no Android build, explain the exact blocker and offer a compatible replacement, source build, isolated environment, or optional target—never a silent fallback.
- Graphical support cannot be claimed from headless logic tests.
- Automatic setup must not become an unreviewed arbitrary-code supply chain.

## 2. Vocabulary and capability states

- **Workspace:** selected project root and KeepAgent metadata.
- **Execution target:** this phone, an isolated local environment, or an optional paired/hosted target with declared OS, architecture, and capabilities.
- **Runtime pack:** signed, versioned, reproducible tools and libraries installed in KeepAgent-private storage for a language or build ecosystem.
- **Run profile:** saved entry point plus target, arguments, environment references, permissions, device/viewport, timeout, and evidence policy.
- **Check:** reproducible assertion producing pass/fail and evidence.
- **Artifact:** addressable APK, binary, screenshot, trace, coverage file, log, recording, report, or patch.
- **Playable/interactive:** the real rendered program accepts touch, keyboard, controller, rotation, lifecycle, and audio as applicable.

| State | Meaning | Allowed primary action |
|---|---|---|
| Ready | Installed and passed a real self-test | Run |
| Installable | Compatible signed pack is available | Install, with size/network disclosure |
| Setting up | Downloading/verifying/installing; resumable | View progress or cancel |
| Permission needed | Runtime exists but Android consent is missing | Grant permission |
| Incompatible | Device/project cannot use it | Explain and offer valid alternatives |
| Failed | Attempt failed with retained diagnostics | Retry, repair, or remove |

“Coming soon” cards and disabled fake Run buttons are not capabilities.

## 3. North-star journeys

Journeys 3.1–3.5 begin on a clean supported physical phone with no paired computer. Journey 3.6 begins on the phone but deliberately exercises the optional paired target because the artifact belongs to a desktop OS.

### 3.1 Import–fix–verify–ship

1. Import a Git repository, ZIP, or user-selected folder.
2. Read its status, instructions, prior neutral handoff, active branch, and tests.
3. Ask the agent to diagnose and implement a change.
4. Observe a concise plan and live, interruptible work.
5. Let it edit files, install an approved dependency/runtime pack, run the project, inspect failures, and iterate.
6. Review semantic summary and diff, open affected files, and inspect evidence.
7. Commit, push, share a patch, or export a portable project and handoff.
8. Kill/restart the app mid-task and resume without rerunning successful work.

### 3.2 Playable game on the phone

For both a documented Python game fixture and Java/Android game fixture:

1. Import or create the project.
2. Install any required signed runtime pack inside KeepAgent.
3. Build or interpret it locally.
4. Launch its real graphical surface from Test.
5. Play using touch and at least one additional applicable input mode.
6. Hear audio when used; background/foreground and rotation do not corrupt state.
7. Agent captures logs, frames/screenshots, performance, and crash details.
8. Agent changes visible behavior, relaunches, interacts, asserts it, and links evidence to the changed revision.

Console output alone fails this journey.

### 3.3 Android application entirely on-device

Import a conventional Gradle Android project; resolve dependencies; compile Java and Kotlin; process resources; dex, package, align, and sign an APK; request system installation consent; launch it; drive it by semantic selectors; capture Logcat, UI hierarchy, screenshot, and instrumentation results; edit and repeat. Common supported Gradle layouts work without conversion to a KeepAgent format.

### 3.4 Web application

Resolve and execute package scripts locally, start the development server, open a phone or desktop viewport, inspect DOM/accessibility tree/network/console/storage, click/type/scroll/upload, take screenshots/traces, run checks, and let the agent repeat until they pass. **App preview** is one Test surface, not the product's identity.

### 3.5 C/C++ project

Install the compatible Clang/LLD/CMake/Ninja pack, configure/build for Android or a local CLI target, execute tests, stream diagnostics, debug crashes with symbols, and interact with an Android/SDL graphical target when the project provides one.

### 3.6 Desktop application

Explicitly enable and pair the desktop add-on, bind it to one project and OS, launch the actual app in an interactive/virtual session, inspect its accessibility tree/windows/logs, drive input, collect screenshots/video/traces, cancel safely, and reconnect to the same job. Reports name the real target OS and never infer desktop success from phone or browser success.

## 4. Functional requirements

Requirement IDs are stable. Tests and ADRs reference them.

### 4.1 Agent loop and work management

- **AG-01:** Stream assistant text, reasoning when provided, tool calls, approvals, progress, results, and recovery events in causal order.
- **AG-02:** Support read/write/edit/patch/search/list, structured code navigation, shell/process execution, Git, runtimes, browsers, app control, artifacts, and add-on tools through one permission-aware registry.
- **AG-03:** Repair common malformed tool arguments without changing semantic intent; record repairs; never invent missing destructive targets.
- **AG-04:** Plans, decisions, checkpoints, open questions, and acceptance checks persist per workspace in model-readable form.
- **AG-05:** Long work continues through navigation, screen lock where Android permits, connection loss, and process recreation. Completed idempotent jobs reattach rather than repeat.
- **AG-06:** Stop is always reachable and terminates the complete local/remote process tree or isolated worker.
- **AG-07:** Run–observe–fix loops have explicit step, time, token, cost, output, thermal, and battery budgets with a useful stop reason.
- **AG-08:** Subagents support parallel read-only investigation and isolated writable branches/worktrees, scoped context, per-agent budgets, status, results, interruption, and merge/reject review. The coordinator owns a global budget.
- **AG-09:** Multiple independent chats, branching, rename/archive/delete/search, workspace binding, and chronological history survive restart and migration.
- **AG-10:** Users can review or undo mutations; destructive actions name exact scope and use recoverable behavior where feasible.
- **AG-11:** The agent can research the public web from the phone: search through user-configurable providers, open/fetch pages and documentation, follow links, extract bounded relevant passages, read supported PDFs, compare dates/versions, and return durable source URLs plus claim-level citations. Research distinguishes search snippets from opened sources, prioritizes primary/official technical sources, records retrieval time, respects network/privacy policy, treats page content as untrusted, and never grants webpages tool authority. Search, fetch, and browser automation share caches/artifacts without forcing the model to ingest entire pages.
- **AG-12:** Agent turns, downloads, dependency installation, builds, tests, local servers, and headless checks continue while the user switches to other apps. Work uses Android-compliant foreground services or scheduled work as appropriate, with a persistent private notification showing project, current phase, elapsed time, and Pause/Stop/Open actions. Returning to KeepAgent reattaches to the same live job and event position without restarting it.
- **AG-13:** Background work survives screen-off and ordinary process recreation where Android permits. Before an operation that cannot survive an OS kill, KeepAgent checkpoints enough state to resume safely or marks the exact interrupted phase. Battery optimization, background-data, notification, thermal, low-battery, and OEM restrictions are detected and explained with an optional guided system-settings action; denial never corrupts work or silently changes to desktop execution.
- **AG-14:** An approval or decision needed in the background produces one actionable, privacy-safe notification and pauses only the dependent branch. Independent approved work may continue. No sensitive prompt, source, secret, or command appears on the lock screen by default. Completion/failure notifications are grouped per project, and unchanged state never generates repeated alerts.

### 4.2 Files, projects, Git, and handoff

- **PR-01:** Create/open private projects and import through Android's system picker without browsing unrelated storage. Support ZIP, Git HTTP(S)/SSH, and user-granted folder trees.
- **PR-02:** Preserve dotfiles, executable intent, symlinks where safe, Git history/branches/tags/remotes, submodules, and LFS—or report every omission before activation.
- **PR-03:** Clone/fetch/pull/push/branch/merge/rebase/stash/commit/diff/blame/log, credential helpers, host verification, conflicts, and interrupted-operation recovery work without a desktop.
- **PR-04:** Large repositories use indexed, cancellable, incremental operations; UI remains responsive and reports progress.
- **PR-05:** Read root and scoped `AGENTS.md`, `CLAUDE.md`, `PLAN.md`, `HANDOFF.md`, and `README.md`. Repository text is untrusted data, never a permission grant.
- **PR-06:** Import/export a versioned vendor-neutral handoff containing goal, decisions, completed/pending work, tests/evidence, Git identity and dirty hashes, artifacts, runtime lock, and instruction paths. Unknown fields round-trip; credentials and unrelated transcripts never enter it.
- **PR-07:** Detect Codex/Claude Code/Hermes/compatible metadata and offer non-destructive adapters with an import preview.

### 4.3 Providers and inference

- **LLM-01:** Support multiple named providers and native adapters for major APIs plus OpenAI-compatible custom endpoints, Ollama, llama.cpp, vLLM, and LAN endpoints.
- **LLM-02:** Negotiate models, context, streaming, tools, images, reasoning, prompt caching, structured output, and token accounting. Unsupported features degrade explicitly.
- **LLM-03:** Provider/model switching preserves a valid conversation representation and explains incompatible content.
- **LLM-04:** Credentials use Android Keystore encryption, are independently revocable, never enter logs/prompts/export, and can be referenced by runtimes without exposing values to the model.
- **LLM-05:** Offline local inference is first-class; UI explains memory/context tradeoffs plainly.

### 4.4 Standalone runtimes and package management

- **RT-01:** The base APK works offline immediately for file/Git operations, QuickJS, Python standard-library scripts/pytest, and Java source compilation. Large Node, Python graphics/dependency-build, Android, and C/C++ toolchains are install-on-first-use signed packs with explicit download/storage consent and resumable setup. The first-run app never silently downloads them. Users may prefetch/export packs for later offline installation. Every bundled or installed runtime verifies by executing a real fixture rather than printing versions.
- **RT-02:** Pack manager discovers compatible packs, shows exact download/installed sizes, licenses and trust source, supports metered-network choice, resumes, verifies signatures and hashes, stages and atomically activates, rolls back, repairs, updates, pins, and removes.
- **RT-03:** Manifests declare ID/version, app/API/ABI range, dependencies, executable entries, environment, capabilities, licenses, hashes, unpacked size, self-tests, and migrations. Builds are reproducible and arm64-v8a physical-device tested.
- **RT-04:** Native packages are built for KeepAgent's package/prefix. Never mix prefix-bound packages from Termux or another app.
- **RT-05:** Local processes support relative cwd, argv, explicit shell, stdin, PTY, bounded streaming output, environment/secret references, status, signals, timeout, process-tree kill, artifacts, and persisted job identity.
- **RT-06:** Untrusted project code runs in the strongest available isolated Android process/VM/sandbox. Disclose filesystem, network, sensor, and private-storage access; approval is not a sandbox.
- **RT-07:** Python supports per-project dependency locks and an Android wheel/source-build policy. Graphics include SDL2-compatible rendering, touch/keyboard/controller, audio, lifecycle, rotation, assets, frame capture, and a documented framework path.
- **RT-08:** Java supports multi-file compilation, dependencies, tests, stack traces, and Android API targeting. Java/Android graphical projects use real app build/launch, not AWT/Swing claims.
- **RT-09:** JavaScript/TypeScript supports Node-compatible scripts, package locks, common bundlers/test runners, servers, source maps, and browser checks.
- **RT-10:** C/C++ supports Clang, LLD, libc++, headers/sysroot, CMake, Ninja, tests, symbolized diagnostics, and Android/SDL targets.
- **RT-11:** Android builds support common Gradle projects and a lightweight direct pipeline where appropriate: dependency resolution, javac/kotlinc, AAPT2, D8/R8, resources/assets/native libraries, APK packaging/alignment/signing, install consent, launch, incremental cache, clean, and actionable diagnostics.
- **RT-12:** Additional ecosystems are extension packs. Agents discover only relevant installed schemas.

### 4.5 Test, preview, debug, and evidence

- **TE-01:** Test begins with detected runnable/checkable targets, not a toolbar. One recommended action is primary; advanced configuration uses run profiles.
- **TE-02:** Runnable requires build/interpret, real surface launch, applicable input, logs, evidence, stop, and repeat on the named target.
- **TE-03:** Browser automation exposes semantic locators, DOM/accessibility, console, network, storage, viewports, click/type/scroll/drag/upload, screenshots and traces. Headed mode uses the user-visible WebView. Background mode is genuinely non-visible and survives leaving Test; it must execute the same engine/profile or label all engine differences, produce the same evidence schema, and never call a merely minimized visible view “headless.”
- **TE-04:** Android automation uses user-consented installation, Accessibility or instrumentation, semantic selectors, touch/type/key/gesture, rotation/lifecycle, screenshots/video, UI hierarchy, Logcat, crash/ANR, performance and reports.
- **TE-05:** Game checks inject deterministic input/time where supported, compare frames with tolerances, inspect adapter state/logs, sample frame pacing/memory, and retain replayable evidence.
- **TE-06:** Results bind target, profile, source revision/dirty hash, runtime lock, timestamps, assertions, logs, truncation counts, and artifact hashes. Results visibly become stale after relevant edits.
- **TE-07:** Failures send compact diagnostics and artifact pointers to the agent. Passed/pending/running/failed/canceled remain distinct.
- **TE-08:** Desktop add-on supports real Windows/macOS/Linux native UI and Electron with clearly labeled OS-specific evidence.

### 4.6 Add-ons and interoperability

- **EX-01:** Versioned APIs cover tools, providers, runtimes, targets, importers, UI contributions, context providers, hooks, and artifact renderers.
- **EX-02:** Support trusted in-process, isolated script/WASM-style add-ons, and MCP servers with capability negotiation and text/resources/images/audio/structured results.
- **EX-03:** Install shows publisher/signature, permissions, dependencies, compatibility, storage and network. Disable/revoke removes tools/context immediately; crashes cannot take down the agent host.
- **EX-04:** Tool collisions and aliases are deterministic; schemas are validated, compacted, cached, and loaded only when relevant.

### 4.7 Mobile code editing and terminal

- **ED-01:** Files opens a searchable project tree and multi-file editor with persistent tabs, cursor/selection/scroll/folds, recent locations, split view on wide screens, and instant switching among workspaces without cross-project state or tool leakage.
- **ED-02:** Provide syntax highlighting, bracket/quote assistance, indentation, formatting, diagnostics, completion, signature help, hover documentation, go-to definition/type/implementation, find references, rename, symbols, breadcrumbs, and project text/file search through versioned language-service add-ons. Each feature reports indexing/readiness and never fabricates semantic precision from text matching.
- **ED-03:** Agent and user edits share one revision-aware buffer model. Unsaved changes, external/agent conflicts, encoding and line-ending changes are explicit. Diff-aware edits, per-hunk accept/reject, staged/unstaged comparison, edit history, undo/redo across agent actions, and recovery after process death preserve user work.
- **ED-04:** Phone editing supports IME composition, selection handles, clipboard, hardware keyboard shortcuts, configurable extra-key row, tab/escape/arrows/modifiers, find/replace, multi-cursor where reliable, and no keyboard-induced loss of composer/editor actions. TalkBack exposes lines, diagnostics, suggestions, and diff state without reading decorative syntax spans.
- **ED-05:** Files at least 5 MiB and 100,000 lines open in paged/read-only mode within two seconds on reference hardware; normal files up to 1 MiB remain editable without visible typing lag. Binary/generated/minified files are detected and offer appropriate viewers or explicit override.
- **ED-06:** A first-class local terminal opens at the active workspace, supports multiple named sessions, PTY resize, scrollback search/copy, extra keys, stdin, signals, process status, background notification/stop, and restoration after navigation. It uses the same runtime packs, jobs, permissions, secrets, and evidence as agent execution—never an unrelated shell environment.
- **ED-07:** The complete manual journey—switch workspace, search symbol, open/edit multiple files, inspect diagnostics, run a command, review a diff, undo, save, and return to Chat—works with touch alone and with a hardware keyboard.

### 4.8 Skills, learning, and new-language extensions

- **SK-01:** A skill is a portable, versioned instruction package with a concise trigger/description, complete operating instructions, optional templates/assets/reference material, declared tools/runtimes/permissions, compatibility range, provenance, license, tests, and evaluation fixtures. Skills can be created and edited on the phone, imported from a user-selected file or Git source, exported, forked, enabled per project or globally, pinned, updated, disabled, and removed.
- **SK-02:** Skill discovery is progressive: only compact names/descriptions enter ordinary context; full instructions load after a deterministic or model-selected trigger. Conflicts, precedence, nested references, token cost, missing dependencies, and stale versions are visible. Project content cannot silently install or enable a skill.
- **SK-03:** Users and agents can add any coding language without changing KeepAgent core by combining a language extension (file types, parser/tree-sitter/TextMate grammar, language server/diagnostics/navigation/formatting), runtime pack (compiler/interpreter/debugger/package manager), project detector, run/test profiles, artifact viewers, and conformance fixtures. The extension UI explains which layers are present; syntax color alone never counts as language support.
- **SK-04:** KeepAgent may propose self-improvements from repeated failures, user corrections, successful workflows, new project conventions, or evaluation regressions. A proposal is a reviewable skill diff with evidence, scope, expected benefit, risks, permissions, provenance, and rollback—not a silent mutation of system prompts, tools, policies, trust roots, or installed skills.
- **SK-05:** Proposed skill changes run in an isolated evaluation workspace against positive, negative, regression, prompt-injection, privacy, and small-model fixtures. Activation requires explicit user approval and creates a signed local version/checkpoint. Users can compare, reject, edit, pin, revert, export, or delete it. Failed/canceled evaluations leave the active skill unchanged.
- **SK-06:** Self-improvement has bounded frequency, compute, network, storage, and model cost; never weakens safety/approval/file boundaries; never trains on or exports private project data without explicit informed consent; separates broadly reusable knowledge from project-local instructions; and records why a change was proposed and which evidence caused it.
- **SK-07:** An optional maintainer workflow can submit an approved, redacted skill improvement upstream as an ordinary reviewable contribution. No automatic global publishing, cross-user learning, telemetry upload, or remote activation occurs by default.

## 5. Context architecture for local models

Build each turn from independently budgeted layers: invariant safety/policy; compact agent contract; user request; workspace goal/plan/checkpoint; scoped project instructions; recent decisions; relevant source excerpts/symbols; selected schemas; and bounded tool/evidence results. Every layer records source and truncation. Raw repository dumps and full tool catalogs are forbidden.

- Reserve output and tool-follow-up capacity before assembling input.
- Preserve assistant/tool pairing across compaction and provider conversion.
- Prefer identifiers, diffs, symbols, and artifact pointers over repeated files.
- Use deterministic extraction before model summarization; never summarize secrets.
- Persist accepted decisions and verified facts separately from hypotheses.
- Summary failure falls back to a smaller deterministic checkpoint without looping.
- Subagents get minimum task context and return conclusions/evidence pointers.
- Context telemetry is locally inspectable/exportable with secrets redacted.

Evaluate representative 7B, 14B, 27–32B local models and a strong hosted reference across tool templates and quantizations. Record exact model, quantization, server/version, chat template/parser, context, reasoning, sampling and generation limits. Measure task success, incorrect mutations, needless calls, recovery, tokens, time to first useful action, latency, and user interventions. Smaller prompts that reduce success fail.

## 6. Mobile-first UX

### 6.1 Information architecture

The four persistent destinations are Chat, Files, Test, and Tools. Project, model, execution target, active job, and dirty state stay identifiable without consuming the message viewport. Secondary screens return to their parent and preserve scroll, cursor, draft, preview, selection, and job. The golden loop—ask → observe → approve → evidence → diff → continue—requires at most two destination changes.

### 6.2 Interaction rules

- One obvious primary action per card/screen; rare actions use contextual menus.
- Use plain verbs such as “Run tests”, “Install Python graphics”, and “Stop”.
- Never present a wall of equal buttons or a blank terminal as onboarding.
- Progressive disclosure never hides capability state or risk.
- Async actions acknowledge immediately and provide persistent progress, cancel, background continuation, and useful completion/failure notification.
- Switching apps, opening a notification, or returning through Android Recents preserves the active workspace, chat draft, agent stream, job controls, Test surface state, and last-read position. The user never has to keep KeepAgent visible to finish ordinary work.
- Approvals state action, target, scope, persistence, and risk. Batch repeated safe operations without vague grants.
- Optimistic UI never claims build/test success.
- Empty states teach the detected project's next meaningful action.
- Keep's brick/fortress identity is restrained decoration that cannot reduce contrast, density, touch size, or clarity.

### 6.3 Accessibility and adaptation

- Minimum 48 dp targets; meet WCAG 2.2 AA; large fonts cannot clip actions.
- Complete TalkBack names/roles/state/order, keyboard/switch navigation, visible focus, reduced motion, high contrast, and non-color status.
- Support portrait, landscape, foldables/tablets, hardware keyboard, mouse/stylus, and DeX-style windows. Wide layouts add useful split views without changing navigation.
- Composer/approvals remain above IME; Back is predictable; rotation/process recreation preserve work.

### 6.4 Performance budgets

- Touch acknowledgement ≤100 ms; normal navigation response ≤200 ms.
- No blocking work on UI thread; streaming stays smooth during file/process events.
- Warm reopen shows usable cached state within 1 second on reference hardware.
- Lists virtualize and logs/files/diffs page with bounded memory.
- Thermal, battery, storage, and network impact are visible for long work.

## 7. Security, privacy, and trust

- Default file scope is the active workspace. Broader access is explicit, revocable, and visible. Never enumerate Google Drive or unrelated phone storage without a user-selected document/tree grant.
- Use system pickers and scoped storage; do not request broad storage access for convenience.
- Separate read, write, execute, network, secrets, device automation, install, and external-target permissions. Session grants expire; persistent grants are editable.
- Runtime/add-on updates use TLS, signed metadata, hashes, rollback protection, reproducible build records, SBOMs, licenses, and vulnerability response.
- Imported instructions, filenames, output, webpages, and MCP content are untrusted data and cannot grant authority.
- Logs/artifacts/telemetry are local by default, bounded, secret-redacted, retention-controlled, and explicitly exported. No behavioral analytics by default.
- Run code with least privilege and disclose Android limits. Offer enforceable network-off isolation where technically possible.
- Threat-model injection, traversal/symlink races, archive bombs, malicious Git metadata, dependency substitution, pack compromise, localhost exposure, intents, WebView bridges, job reattachment, and paired-runner impersonation.

## 8. Reliability and lifecycle

Jobs persist through queued → preparing → running → succeeded/failed/canceled/interrupted with stable ID, workspace, target, profile, revision, phase progress, timestamps and artifacts. Reconnection reattaches; retry is explicit. Cancellation reaches children. Temporary output is staged, bounded, cleaned after failure, and never overwrites a workspace implicitly.

Database, handoff, provider, add-on, and pack formats are versioned with tested forward/rollback migrations. App upgrade, OS kill, low storage, lost network, revoked folder grant, mirror failure, and corrupted cache have actionable recovery.

## 9. Compatibility and release tiers

| Ecosystem | Base/pack | Release-level outcome |
|---|---|---|
| Python | Base + dependency/graphics packs | Scripts, pytest, project dependencies, graphical SDL2 game launch/input/audio/evidence |
| Java | Base + JVM/Android packs | Multi-file apps/tests/dependencies; Android app/game build, install, launch and evidence |
| Kotlin/Android | Android pack | Common Gradle Android apps build and run locally |
| JavaScript/TypeScript | Node/web pack | Package scripts, bundling, tests, servers and autonomous browser testing |
| C/C++ | Native pack | Clang/LLD/CMake/Ninja, tests, symbols and Android/SDL graphical targets |
| HTML/CSS/browser JS | Base web surface | Interactive and autonomous phone/desktop-viewport checks |
| Git | Base | Authenticated daily workflow, conflicts, submodules/LFS and recovery |
| Web research | Base + configurable providers | Search, fetch, document/PDF reading, version comparison, durable URLs and claim-level citations |
| Skills/languages | Extension SDK + optional packs | Create/import/evaluate/enable/revert skills and add complete language stacks without core changes |
| Desktop GUI | Optional paired pack | Real matching-OS launch/automation; never required above |

Additional languages may be community packs. “Mainstream” is not achieved while any row is detection-only. Publish an Android API/ABI/RAM/storage matrix. arm64-v8a physical phones are mandatory; emulator evidence cannot replace phone evidence. Low-memory behavior must fail gracefully and recover.

## 10. Verification strategy

- Unit/property tests for parsers, paths, budgets, migrations, and manifests.
- Contract tests for providers, tools, packs, targets, handoffs, and add-ons.
- Integration fixtures for every ecosystem and failure mode.
- Instrumented tests for persistence, permissions, installers, and automation.
- Physical-device journeys for graphics, input, audio, and lifecycle.
- Accessibility, performance, thermal, battery, storage-pressure, network-loss, kill/restart, upgrade/rollback, security, and soak suites.
- Small-model evaluations from immutable fixtures with retained traces.

Every shipped requirement links code, automated checks, device/run identity, logs, and artifacts. Evidence records source revision and runtime lock. Manual observation alone cannot close regression-prone gates.

### Release-blocking gates

1. All six north-star journeys pass on supported physical phones.
2. Every mainstream compatibility row reaches its stated outcome.
3. A representative 14B model completes import–edit–run–observe–fix–review with no hidden desktop help in at least 80% of fixed trials; the 27–32B target reaches at least 90%.
4. App switching and screen-off do not stop ordinary agent work, pack installs, builds, servers, or headless tests. App/process kill during those operations resumes or reports a precise recoverable interruption without corruption.
5. No critical/high unresolved security findings; pack/update and workspace-boundary suites pass.
6. TalkBack, large-font, keyboard, portrait/landscape, and touch-target suites pass.
7. No UI labels planned/detected capability runnable; capability-state tests cover every runtime and target.
8. New users complete import, first change, run, evidence review, and export without facilitator explanation; critical-error rate is zero and median satisfaction is at least 4/5.

## 11. Delivery order

Work in vertical phone journeys, not isolated infrastructure:

1. Truthful foundation: durable jobs/artifacts, process isolation, pack manager, capability states, approvals, runtime self-tests.
2. Playable baseline: Python SDL2 and Java/Android fixtures build, launch, accept input/audio, capture evidence, and enter the agent fix loop.
3. Android projects: common Gradle import/build/install/automation, dependencies, incremental cache, and recovery.
4. Web/Node loop: package scripts and autonomous browser evidence/fix loop.
5. Native projects: signed Clang/LLD/CMake/Ninja pack and C/C++ CLI/SDL fixtures.
6. Daily Git/handoff: authenticated remotes, conflicts, submodules/LFS, desktop-harness adapters, portable state.
7. Agent parity: writable isolated subagents, cited web research, providers/MCP/add-ons, portable and guarded self-improving skills, new-language SDK, semantic navigation, and complete history/search/review.
8. Desktop reach: optional OS-specific GUI runner packs and cross-target reports.
9. Mainstream hardening: accessibility, device matrix, recovery, security, local-model evaluations, user studies, performance, and release operations.

An increment is Done only with implementation, tests, physical-device evidence, user documentation, traceability, and an ADR for changed architectural contracts.

## 12. Conformance profiles and fixed fixtures

KeepAgent publishes a machine-readable capability report and compatibility page for every release. A project/runtime is **supported** only when its pinned fixture and documented version range pass all applicable conformance checks. **Best effort** means KeepAgent attempts execution but makes no release guarantee and labels limitations before running. **Unsupported but extensible** means no Run action is shown; the UI links the missing capability to a runtime/add-on contract. “Anything” is the extensible product direction, not permission to make untestable compatibility claims.

The release fixture repository contains immutable commit IDs, expected setup, interactions, assertions, artifacts, and license provenance. At least half of ecosystem fixtures must be independently maintained upstream examples imported unchanged; KeepAgent-specific fixtures may test integration details but cannot be the sole proof.

### 12.1 Required ecosystem fixture matrix

| Ecosystem | Minimum unchanged project shapes | Required outcome |
|---|---|---|
| Python | CLI package with `pyproject.toml`; pytest suite; pygame-ce/pygame SDL2 game; Kivy sample or an explicit, user-visible incompatibility decision | Resolve lock, run/tests; game displays, accepts touch/keyboard, plays audio, backgrounds/resumes, and yields evidence |
| Java/JVM | Multi-file Maven-style source/dependency fixture; JUnit fixture; LibGDX Android sample | Resolve dependencies, compile/test; LibGDX build/install/play/evidence |
| Android | Java Views app; Kotlin app; Compose app; multi-module/flavor app; JNI sample | Select variant, resolve/build/sign/install/launch, instrument/drive/debug |
| JS/TS | npm, pnpm, and Yarn lock fixtures; Vite React; Next-compatible supported mode; Node CLI/test suite | Deterministic install, scripts/tests/server, source maps, autonomous browser evidence |
| C/C++ | single-file C/C++; CMake/Ninja tests; multi-file libc++; JNI/NDK; SDL2 game | Compile/link/test/symbolize; Android/SDL launch/input/audio/evidence |
| Git/handoff | large history, dirty tree, submodule, LFS, merge/rebase conflict; Codex, Claude Code, Hermes, generic metadata fixtures | Round-trip without silent loss; exclusions and unsupported metadata itemized |

The compatibility page pins tested Gradle, Android Gradle Plugin, Kotlin, JDK, compile/target SDK, NDK, CMake, Node, Python, package-manager, and framework ranges. Android coverage includes Maven Central and Google repositories, offline cache, annotation processors, KSP, Compose, resources/assets, product flavors, build types, multi-module dependencies, JNI/NDK, unit tests, and instrumentation. Unsupported plugins/features fail before mutation with the specific missing contract.

### 12.2 Local shell baseline

Standalone parity includes a POSIX-like shell environment with shebang execution, pipes/redirection, signals, exit codes, environment variables, PTY, and common repository scripts. The supported baseline includes shell, core file/text utilities, `find`, `grep`/`rg`, `sed`, `awk`, `patch`, archive/compression tools, checksum tools, TLS HTTP download, Git/SSH, process inspection, and build helpers required by installed packs. Exact implementations and divergences from GNU/POSIX behavior are published. Package installs are project-lockable, signed, resumable, storage-aware, and never mutate another Android app's environment.

### 12.3 Shared automation contract

Browser, Android, game, and optional desktop adapters implement the same small agent-facing verbs:

1. `launch(profile)` returns target, revision, runtime lock, surface and job IDs.
2. `observe(scope)` returns a bounded semantic/accessibility tree, visible text, logs/state summary, and artifact pointers.
3. `locate(query)` returns stable ranked handles with confidence and adapter source.
4. `act(handle, action, value)` supports applicable tap/click/type/key/gesture/drag/scroll/upload/controller/lifecycle actions.
5. `wait(condition, timeout)` uses events/semantics before polling.
6. `assert(condition)` returns structured pass/fail with evidence.
7. `capture(kind)` records screenshot/frame/video/trace/log/profile artifacts.
8. `stop()` cancels the surface and descendants.

Adapters report whether evidence came from instrumentation/framework hooks, Accessibility, DOM, image matching, or coordinates. Fallback reduces the advertised fidelity and confidence; a coordinate-only observation never masquerades as semantic automation.

### 12.4 Quantitative playability profile

On the published mid-range reference phone, warmed release fixtures must meet:

- first interactive frame within 5 seconds after build/install (excluding disclosed first-time pack/dependency download);
- sustained median at least 55 FPS for the reference 60 FPS 2D scene, 95th-percentile frame time at most 30 ms, and no more than 1% frames above 50 ms during a five-minute scripted play;
- touch-to-visible-response median at most 100 ms and 95th percentile at most 180 ms;
- audio starts within 150 ms of the scripted event and has no audible underrun in the fixture capture;
- stable state through rotation and a 30-second background/foreground cycle;
- bounded memory with no monotonic leak above 10% after five repeated level/relaunch cycles;
- no thermal shutdown; throttling and battery drain are recorded, and a severe-thermal event fails the run;
- a scripted touch/key sequence reaches a known state, a perceptual image assertion passes within a declared tolerance, and the agent-visible report proves a requested gameplay change against a pre-change capture.

Reference-device model, OS/build, refresh rate, power state, temperature, and measurement method are stored with evidence. Lower-end profiles may publish reduced targets but cannot omit interaction, audio, lifecycle, or crash capture.

### 12.5 Normative device baseline and storage classes

The minimum supported base-app device is Android 8.0/API 26, arm64-v8a, 4 GiB RAM, four performance-capable CPU cores, and 2 GiB free storage. It must complete Chat, Files, Git, base Python/Java/QuickJS, import/export, editing, and bounded tests without unrecoverable process death. The full standalone-development profile is Android 11/API 30 or newer, arm64-v8a, 6 GiB RAM, and 12 GiB free storage; it must support every official runtime pack and north-star phone journey. Android 14/API 34 on a 6 GiB mid-range 1080 × 2340-class phone is the performance reference. Exact named devices accompany release evidence. Packs whose disclosed space would leave less than 1 GiB free cannot install; setup offers pack selection or cleanup instead of failing mid-extraction. x86_64 is required for emulator testing but cannot replace arm64 evidence.

### 12.6 Provider compatibility declarations

Official conformance covers OpenAI Responses/Chat-style APIs, Anthropic Messages, Google Gemini, and OpenAI-compatible local servers represented by Ollama, llama.cpp, and vLLM. Each adapter publishes tested API/server versions and declares streaming, tools/parallel tools, images, reasoning, structured output, caching, usage, cancellation, retry/idempotency, and context behavior as native, translated-with-differences, or unsupported. A custom endpoint exposes base URL, encrypted-secret header references, model ID, timeout, TLS policy, parser/template profile, and a self-test. A provider category is not supported until its contract fixture passes; additional providers remain extension adapters.

### 12.7 Requirement traceability and acceptance mapping

Every requirement ID has a same-named executable acceptance record (`AC-<requirement-id>`) in the conformance manifest. It contains fixture commit/setup, device/profile, actions, observable and failure assertions, automation level, owning tests, artifacts, last passing app/runtime revision, and linked ADR/docs. CI fails if a requirement lacks this record, linked tests disappear, or evidence predates a changed implementation/runtime lock.

| Requirement family | Mandatory acceptance suites |
|---|---|
| AG-01…14 | streamed ordering, repair, durable jobs, cancel-tree, budgets, subagent isolation, chat migration, mutation undo, cited web research/injection resistance, app-switch/screen-off continuation, OS-kill checkpoint recovery, private actionable notifications |
| PR-01…07 | scoped picker, malicious archive/Git, full Git, large repo, instruction injection, handoff and four-harness round trips |
| LLM-01…05 | provider declaration matrix, negotiation, cross-provider replay, secret revocation/redaction, offline local-model journey |
| RT-01…12 | real self-tests, pack signing/lifecycle/rollback, ABI/prefix, PTY/process kill, isolation disclosure/escape, every §12.1 fixture |
| TE-01…08 | target-state UX, runnable lifecycle, headed/background browser, Android semantics, quantitative games, stale evidence, compact diagnostics, OS-labeled desktop fixtures |
| EX-01…04 | API migration, isolation/content types, permissions/revocation/crash, schema-token and collision tests |
| ED-01…07 | workspace isolation, intelligence, conflict/undo/recovery, IME/TalkBack/keyboard, large-file budgets, durable PTY, touch/keyboard journeys |
| SK-01…07 | portable package round trip, progressive context, new-language fixture, proposal diff, isolated regression/security evaluation, privacy/budget invariants, explicit upstream submission |

Words such as “common,” “major,” “strongest available,” and “where technically possible” do not establish conformance. Compatibility declarations replace them with enumerated versions/features, a tested fixture, or an explicit unsupported limitation. Isolation records name the Android primitive, verified denied operations, residual permissions, and API/device exceptions.

## 13. Import-adapter and context-evaluation protocols

### 13.1 Harness continuity mappings

Each Codex, Claude Code, Hermes, and generic adapter documents a field-level mapping for instructions, plans/tasks, checkpoints, tool/test evidence, conversation references, worktrees/branches, dirty state, ignored files, add-ons/MCP references, and settings. Import previews classify every discovered item as imported, referenced, excluded-sensitive, unsupported, or invalid. Credentials, global configuration, caches, and unrelated chats are excluded by default. Worktree `.git` pointers and symlink/executable semantics are either preserved safely or block activation with recovery instructions. Round-trip tests compare source hashes, Git graph/refs/status, mapped semantic fields, and the complete omission report.

### 13.2 Fixed local-model evaluation protocol

The public evaluation corpus pins at least 20 repositories and 60 tasks spanning languages, repository sizes, unfamiliar code, nested instructions, edit/test/fix, graphical evidence, Git conflicts, malformed tool calls, cancellation, compaction, provider switching, and recovery. It includes hidden tests and contamination checks; fixture revisions change on a published schedule. Each model/profile runs at least five seeded trials. Automated assertions score repository state and tests; blinded human review scores ambiguous behavior. Report mean, confidence interval, failures, interventions, cost/energy, and full redacted traces.

Compare against the previous KeepAgent release and at least two current desktop harness baselines using equivalent authority/tools. Regressions greater than three percentage points in task success, or statistically meaningful increases in destructive/incorrect edits, block release.

Per-turn budgets are measured, not hard-coded forever: invariant harness instructions target at most 1,500 tokens; initially selected tool schemas at most 2,000 tokens; deterministic workspace/checkpoint context at most 10% of configured context; tool result injected per call at most 2% unless explicitly expanded. Retrieval evaluation requires at least 95% recall for files/symbols needed by the fixed tasks while keeping irrelevant retrieved tokens below 20%. Compaction fidelity must retain all accepted decisions, unresolved blockers, modified paths, and verified test status across a 100-turn synthetic project; context growth must plateau after compaction rather than increase linearly.

## 14. Measurable mobile usability

Reference compact testing includes a 360 × 800 dp viewport plus representative 6-inch hardware. Primary Chat send/stop, approval decision, run/stop, evidence open, and diff review actions sit in the lower two-thirds or have an equivalent reachable affordance. One-handed studies include both handedness settings.

From an already imported project: start the recommended test in at most two taps; stop it in one tap from any primary destination; open its latest failure in at most two taps; send evidence to the agent in one additional tap; reach the relevant diff in at most two destination changes. First-run setup explains storage/network cost before a pack download and reaches a meaningful agent conversation without terminal knowledge.

Usability validation uses at least 12 participants per major release, including at least four primarily mobile developers and four accessibility/assistive-technology users across the study program. Compare matched import–change–test–review tasks against at least two current desktop coding agents and one remote-desktop workflow. KeepAgent must win or tie median task success and median completion time for phone-appropriate tasks, require no more interventions, achieve at least 4/5 satisfaction, and be explicitly preferred over each baseline for continued mobile use by at least 60% of participants. Report confidence intervals, device/familiarity effects, results, and failures. Test calls, notifications, IME changes, rotation, split-screen, screen lock, low battery, and network switching for interruption/resume. Notifications are actionable, grouped, private on lock screen by default, and quiet for unchanged background state.

## 15. Open-source, release, and supply-chain readiness

Before public beta, the repository includes an OSI-approved license, contribution guide, governance/maintainer and decision policy, code of conduct, security policy and private disclosure route, support/compatibility policy, issue/PR templates, architecture/extension documentation, changelog and migration guide. Public releases are reproducible in documented clean environments, CI-built, signed, checksummed, SBOM-attached, provenance-attested, and available through documented stable/beta channels with rollback/recovery. Localization infrastructure, translatable strings, RTL, locale/time/number handling, and contributor translation workflow are release requirements. Crash reporting is opt-in, redacted, inspectable before send, and replaceable/self-hostable.

Official runtime packs are signed by a documented KeepAgent release role using offline-root and rotating online keys. The app ships a trust root and signed revocation/expiry metadata, supports threshold-signed key rotation and emergency freeze/rollback, and retains the last verified working index. Mirrors serve identical content-addressed artifacts and cannot redefine metadata. Reproducible builders independently verify official artifacts. Community packs use a visibly separate trust tier and explicit key opt-in; they cannot claim “official.” If signing/index infrastructure disappears, installed verified packs continue working, export remains possible, and the documented recovery/governance process can rotate trust without a forced unsafe bypass.

Install/update reliability is measured across clean install, upgrade, interrupted download, corrupt mirror, low storage, key rotation, rollback, and offline launch. Stable release requires at least 99% successful first-run initialization and 99.5% successful app/pack update completion across the supported device test fleet, excluding confirmed external store/network outages.

## 16. Current implementation truth (2026-09-05)

Implemented foundations include workspace-contained file tools, persisted chats, OpenAI-compatible connections, local JGit basics, public clone/ZIP import and handoff, bounded context/checkpoints, QuickJS add-ons, Python 3.13/pytest and basic Java compile-to-dex execution in a disposable helper process, manual WebView App preview, bounded read-only subagents, foreground/screen-off protection for agent turns and local checks, explicit interrupted-turn recovery, and an authenticated optional companion with persistent jobs and some browser/Electron/ADB support.

The product is **not feature-complete**. Graphical Python games, conventional Android/Gradle builds, Node workflows, C/C++ toolchains, autonomous on-phone browser/app driving, first-class cited web research, writable isolated subagents, portable/self-improving skill workflows, complete Git remotes, native desktop adapters, pack installation, strong process isolation, durable local run history, and the recovery/accessibility matrix remain release blockers. UI must say so plainly.

## 17. Research and decisions

Revalidate engineering choices near implementation because mobile runtimes, models, and providers change quickly. Prefer primary specifications, official documentation, maintained repositories, and reproducible experiments. Record date, version, applicability, caveats, and rejected alternatives in `RESEARCH.md` and an ADR. Competitive projects prove feasibility; never copy code without license/security review.

Relevant records include ADR-0004 (targets), ADR-0005 (jobs/context), ADR-0006 (secure settings/clone), ADR-0007 (Test UX), ADR-0008 (packs/theme), ADR-0009 (Test workspace), and ADR-0010 (toolchains). This specification supersedes the old web-first north-star and ordering in `ROADMAP.md`; align that file before using it for planning.
