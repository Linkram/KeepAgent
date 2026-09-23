# ADR-0007: Test tab progressive disclosure

- Status: Accepted
- Date: 2026-09-05

## Context

The Test tab exposed page loading, browser history, reload, external browser,
four viewport choices, rotation, fullscreen, capture, save, refresh, and console
controls at the same visual level. On a compact phone this created horizontal
scrolling, weak hierarchy, undersized targets, and uncertainty about the next
action. The primary job is simpler: open a page, inspect it, and give the agent
evidence.

## Decision

Keep only the current page selector, **Open**, console summary, and one full-width
**Send evidence to agent** action in the main flow. Put viewport presets in a
labeled dropdown and infrequent preview operations in overflow. Show one contextual
starter-page action when no HTML page exists. Use at least 48 dp for primary custom
touch targets and content descriptions for icon-only controls.

Captured screenshots must be PNG-compressed before being attached with the
`image/png` media type. Console lines are attached alongside the capture.

## Consequences

The common workflow is visible without scrolling through toolbars, while advanced
operations remain two taps away. Adding another always-visible action now requires
evidence that it belongs to the primary workflow; otherwise it goes in the relevant
menu. Dropdown and overflow discoverability must be included in emulator and
accessibility checks.

## Validation

Run Android unit tests, lint, and debug assembly. On a compact emulator, verify the
empty state, starter-page creation, page rendering, viewport and overflow menus,
console disclosure, and evidence handoff to Chat. Confirm the resulting attachment
is a valid encoded PNG rather than raw bitmap memory.
