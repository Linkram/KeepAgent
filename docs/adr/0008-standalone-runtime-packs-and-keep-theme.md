# ADR-0008: Standalone runtimes and restrained keep theming

- Status: Accepted
- Date: 2026-09-05

## Context

Mobile development cannot be standalone if ordinary source execution always routes
to a paired PC. Conversely, downloading arbitrary desktop executables or Python
wheels at startup is unreliable, unsafe, and often incompatible with Android/ARM64.
The simplified Material UI also lost too much of Mockup2's recognizable fortress
identity.

## Decision

Ship the small, foundational runtimes with the app so they work on first launch and
offline. The first pack contains the existing QuickJS engine plus Python 3.13,
pytest 9.1.1, requests 2.34.2, and their pinned transitive build artifacts. Expose
Python through one approval-gated, workspace-contained tool with script and pytest
modes, a 1 MB source limit, and 64 KiB output limits. The desktop runner remains an
optional built-in add-on and its schemas stay out of model context until configured.

Large future toolchains may use explicit on-demand packs with progress, integrity
verification, retry, storage estimates, and removal. Never silently download them
on every launch, and never claim an incompatible dependency can execute on Android.

Restore the keep identity through a low-contrast brick backdrop, compact header
battlements, stone navigation, sage actions, and speech-tail messages. Preserve the
four-destination mobile hierarchy, 48 dp targets, plain labels, progressive
disclosure, and readable contrast. Decoration must not introduce controls.

## Consequences

Python projects and tests which use the standard library or packaged dependencies
can run fully on-device immediately. The base APK is larger and currently targets
ARM64 phones plus x86_64 emulators. Packages requiring unavailable native wheels,
Node-compatible APIs, full GNU userland, Android SDK builds, or desktop OS APIs need
additional runtime packs or the optional desktop runner.

Python currently executes serially within the app process and restores argv, module
path, and working directory after each run. Moving it to a dedicated bounded worker
process with hard timeout/cancellation is a release-hardening requirement.

## Validation

Build unit tests, lint and both supported ABIs. On an x86_64 emulator, verify the
runtime card reports Python, pytest and requests versions, run a synthetic script and
pytest fixture from the private workspace, and visually inspect compact and expanded
layouts for decoration that does not reduce usability.
