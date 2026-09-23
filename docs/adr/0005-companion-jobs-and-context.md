# ADR-0005: Companion jobs, scoped subagents, and durable context

Date: 2026-09-05. Status: accepted and implemented for the capabilities listed below.

## Context

Android cannot run arbitrary Windows/macOS binaries. Long commands must survive a
phone connection drop without accidental retries. Loading every remote tool schema or
full conversation into a local model reduces reliability. Chat restoration must not
silently point at another project.

## Decision

Provide the authenticated companion as an optional built-in desktop-runner add-on,
never as a prerequisite for the phone's core coding, embedded runtime, or WebView
capabilities. When explicitly used, every remote command, desktop browser, Electron,
Android, and MCP operation becomes a persistent, addressable job. Commands
use argv, bounded cwd, timeout, output and concurrency. Client request IDs are
idempotent. Reconnection reads job state; cancellation terminates the process tree.
Artifacts are named, bounded, and retrieved separately.

Expose the desktop runner's tools to the model only while paired. Configure MCP servers on
the companion and discover one selected server at a time. Treat desktop browser,
Electron, native command tests, and Android as distinct evidence.

Provide bounded read-only subagents: three per parent turn, six model rounds and three
minutes each, with only read/glob/grep. They receive a focused task rather than the
parent transcript and return compact findings.

Persist compaction summaries with chats, retain three recent turns beside the summary,
reserve context for images and completion, and bind sessions to workspaces. A summary
failure is reported once and does not repeatedly call the failing summarizer.

## Consequences

The companion grants code execution as its launching OS user; cwd containment is not
an OS sandbox. Operators must use account/container isolation as needed and encrypted
transport beyond loopback. Python jobs survive app disconnects but not companion
machine loss; startup marks unfinished jobs interrupted. The Android worker requires
ADB and an exact serial. Electron automation follows Playwright's experimental API.

Subagents cannot write or run tests, which avoids edit races but limits delegation.
Summary quality remains model-dependent and needs the endpoint-specific eval matrix.

## Validation

Companion tests cover auth, paths, idempotency, restoration, exits, timeouts,
cancellation, output paging, browser pass/fail, real Electron interaction/screenshots,
and MCP discovery/calling. The Android worker passed against a dedicated API 35
emulator using visible-text and foreground-package assertions with screenshot/UI XML.
Android JVM/agent tests and APK assembly pass. Emulator screenshots verify compact
phone navigation; no personal-phone files or cloud storage were used.
