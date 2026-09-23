# ADR-0010: On-device language toolchains

- Status: Accepted
- Date: 2026-09-05

## Context

KeepAgent is a standalone mobile coding harness. The Test workspace must not be a synonym for a browser preview, and ordinary source projects must not require a paired computer merely because their desktop tools normally produce executables.

Android can execute interpreted source, dex bytecode, WebAssembly, and native binaries compiled for the device ABI. It cannot directly execute Windows `.exe` files or arbitrary desktop binaries. Native tool archives are also tied to an Android application package prefix, so another terminal app's packages cannot safely be copied into KeepAgent.

## Decision

- Python 3.13, pytest, and common HTTP support ship in the base app and run inside the selected workspace.
- Java source is compiled in the base app with an ART-compatible compiler, converted to dex, marked read-only, and loaded locally. The initial compatibility level is Java 8 source syntax against the Android API surface.
- HTML is an optional **App preview** surface, not the identity of the Test workspace.
- C, C++, Node, additional Python packages, Android build tools, and other large ecosystems use signed, versioned, ABI-specific runtime packs. Packs install into app-private storage, verify hashes/signatures before activation, and never require desktop pairing.
- The desktop runner remains an optional add-on for desktop-only programs, desktop UI automation, or workloads too large for the phone.

## Runtime-pack contract

A pack manifest must declare an immutable ID and version, supported Android API range and ABIs, installed size, executable entry points, environment additions, license metadata, and SHA-256 hashes for every payload. Installation is staged, verified, atomically activated, and safely removable. Tools run with the active workspace as their only project root and bounded output/time limits.

The C/C++ pack will contain KeepAgent-prefix builds of Clang, LLD, libc++ headers/libraries, and an Android sysroot for `arm64-v8a` and `x86_64`. A downloaded desktop NDK is not considered an on-device runtime.

## Consequences

The base install remains usable offline for Python and Java while large toolchains are opt-in. Adding a language means implementing a runtime pack and runner, not adding a misleading detection card. Desktop pairing never becomes a prerequisite for supported on-device languages.
