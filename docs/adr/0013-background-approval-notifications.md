# ADR-0013: Background approval notifications

Date: 2026-09-05
Status: Accepted, partial delivery of AG-14

The app observes the approval gate and posts exactly one notification while a
tool decision is pending. Its lock-screen visibility is secret and its text names
only the tool; arguments, commands, source, workspace names, prompts, and secrets
are omitted. Opening it returns to KeepAgent for the full explanation. Explicit
Allow once and Deny actions use non-exported broadcast intents and never modify
the session allowlist.

Resolving or cancelling the request removes the notification. Android 13+
notification denial is respected: the in-app approval remains usable, and no
permission bypass or desktop fallback occurs. Grouped completion/failure notices
and independent parallel approval branches remain future AG-14 gates.
