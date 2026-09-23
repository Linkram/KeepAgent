# ADR-0006: Keystore secrets and staged public Git clone

Date: 2026-09-05. Status: accepted and implemented.

## Context

Provider records contained API keys inside readable SharedPreferences JSON. Project
continuation also required manually making a ZIP even for public Git repositories.
Mobile imports can be interrupted and must not make partial projects look usable.

## Decision

`SettingsStore` preserves its namespaced API but encrypts `model.apiKey` and the
complete `connections.list` value with AES-GCM. The AES key is generated and retained
by Android Keystore under a versioned alias and is non-exportable. Existing plaintext
values are encrypted synchronously on first read. If restored ciphertext has no usable
Keystore key, the secret is cleared rather than crashing or returning ciphertext.
Non-secret settings retain asynchronous writes.

Workspaces accept unauthenticated HTTP(S) Git clone URLs. Reject local/SSH schemes,
missing hosts, and embedded user information. JGit clones into a unique staging
directory outside the visible project list, then the workspace manager atomically
renames it into place. A failure cleans staging and cannot overwrite an existing name.

## Consequences

Connections remain decrypted in process while in use, and a compromised unlocked app
process can still access them. Android backup cannot transfer the Keystore key, so a
restored install may require re-entry. Rotating the key and biometric-gated access are
future migrations.

Clone currently supports public HTTP(S) repositories only. Private authentication,
credential helpers, pull/push, submodules, LFS and resumable/background clone jobs
remain explicit release work. The five-minute JGit network timeout is bounded but the
screen-owned coroutine does not survive process death.

## Validation

App JVM tests cover accepted and rejected URL forms. The Android app and tests compile.
On the dedicated API 35 emulator, a synthetic legacy plaintext key and provider record
were migrated to distinct randomized ciphertexts; a direct read of the app's own
preferences contained no plaintext marker. The physical phone and Google Drive were
not accessed.
