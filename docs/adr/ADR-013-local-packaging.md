# ADR-013 - Local Apple Silicon bundle and process identities

Status: Accepted for the local M4-A ad-hoc build, 2026-11-27. Release signing and hardened-runtime policy remain undecided here and belong to ADR-004.

## Decision

Tauri assembles the shell, UI, jlink runtime, unchanged Boot jar, trained CDS archive and extracted SQLite dylib. Generated resource and sidecar paths live in a build overlay so that a clean `tauri dev` and Rust checks keep working. whisper.framework travels beside the Swift sidecar to satisfy its existing relative load path.

Use JDK 21 `--compress=zip-6` and the module roots recorded in `docs/PACKAGING.md`. Static analysis of the nested dependencies is a candidate set, not a runtime guarantee. Startup experiments require `java.security.jgss` and `java.instrument` for Tomcat; optional compiler, scripting, JFR and rowset/tooling modules are omitted. Preserve EC TLS support for user-requested connections.

Train CDS on the original executable jar with Boot 4.1.2 `spring.context.exit=onRefresh`, an stdin test token and temporary data, after creating the runtime's default base archive. Verify actual Spring class sharing with an enforced-CDS launch after assembly. The jar stays byte-identical and no extracted application copy is needed. Runtime and archive sizes are measured rather than inferred from the initial architecture estimates.

Process recovery belongs to the existing agent adapter on the Java side and to the shell's supervisor on the Rust side. Persist pid, millisecond start time and canonical executable path atomically, and require every field to match before killing. Record the shell owner as well, so a second live instance cannot reap an active core. Hold a file lock before the core opens the database, but allow recovery of a matched orphan still holding that lock after its owner died. Existing stdin lifelines remain the normal crash-exit mechanism.

## Consequences

Local ad-hoc builds clear hardened-runtime flags to avoid dyld library validation rejecting the ad-hoc whisper framework. This does not select the release hardened-runtime or entitlement policy and touches no signing identity or keychain. No dependencies were added.

The first measured runtime plus base archive is 64,140 KiB and the dynamic CDS archive is 49,296 KiB. The jar alone is about 156 MiB, so the installed-size budget is exceeded; dependency removal or a packaging-format change would be separate work. The local build and smoke measurements are recorded in `docs/PACKAGING.md`; they do not substitute for M1 8 GB, second-machine or Gatekeeper verification.
