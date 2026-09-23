# ADR-0004: Portable projects and explicit execution targets

Date: 2026-09-04. Status: accepted; ZIP import/export, public clone, bounded project
context and schema-v1 handoff are implemented. Complements ADR-0003 in DEV_SPEC.md.

## Context

The product must continue real desktop coding workflows on a phone with local
models. Android cannot execute arbitrary Windows/macOS applications, and desktop
harness chat stores are not a common interchange format. Loading whole repositories
into each prompt wastes context and harms responsiveness.

## Decision

Provide project copies through the Android document picker. Extract into private
staging with containment, entry and expanded-byte limits; publish into a new workspace
only after success. Preserve included dotfiles, never overwrite existing workspaces,
and do not automatically run imported configuration or change the active project.

Load bounded root guidance at each turn and let tools retrieve further detail.
Treat repository guidance as subordinate to user instructions and tool permissions.
Keep explicit source/truncation labels. Embed a versioned `.keepagent/handoff.json`
in exported source ZIPs without mutating the workspace. Schema v1 carries project and
Git identity, dirty-file hashes, the current goal, durable decisions, recent completed
work and instruction-file paths. Emit no claimed test result without captured evidence.
On import, compact understood fields into context and omit hashes/raw syntax. This is
a continuation bridge, not a claim that proprietary external chats or runtimes migrate.

Use capability-advertising execution targets for scripts, browsers and OS apps.
The phone is the default execution target: source operations, supported embedded
runtimes and WebView-based browser testing must not require another computer. A
compatible remote machine or hosted runner is an optional add-on for desktop-only
APIs, native applications, incompatible toolchains and explicitly offloaded work.
A desktop browser viewport is explicitly a browser test profile, not proof that a
desktop operating system executed the project.

## Consequences

Users can import sources now, but dependencies and platform runtimes need separate
setup. ZIP loses Unix permission/symlink semantics; worktrees/submodules/LFS need
dedicated handling. Large/interruptible imports need persistent job infrastructure.
Source text is not automatically executable: the phone still needs a compatible
runtime, dependencies, Android-accessible OS APIs, and ARM64/Android native builds.
Guidance excerpts are character-bounded, not exact token budgets; measure their cost
and task success on the actual model before tuning defaults.

Rejected alternatives: full repository prompt injection (unbounded context), importing
into the active directory (partial overwrite risk), and representing desktop previews
as desktop execution (invalid test evidence).

## Validation

Unit tests cover ordinary wrapped repositories, Git metadata, collisions, unsafe paths,
expanded-size and entry limits, empty input, cleanup, bounded/refreshed guidance, and
schema-v1 handoff export/context compaction.
Physical-device smoke tests cover installation and the import UI. See PRODUCT_SPEC.md
for release gates that remain unimplemented.
