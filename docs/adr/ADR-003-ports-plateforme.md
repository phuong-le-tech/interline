# ADR-003 - Platform ports: no OS call in the core, and how to add an OS

Status: Accepted, 2026-11-13.
Related: ADR-001, ADR-002, `docs/ARCHITECTURE.md` sections 6 and 7, `docs/PLATFORM.md`.

## Context

v1 is macOS only, but Interline must port to Windows and Linux without touching the core or the UI. Portability decays silently: one `if (os == mac)` for a path, one `java.awt.Desktop` call to open a URL, one Keychain helper "just for now", and the core is no longer the same jar everywhere. We need rules that are enforced by the build, not by memory.

## Decision

### 1. One port, one adapter, OS work outside the JVM
- `core-domain` defines `PlatformCapabilities` with sub-ports `AudioCapture`, `Permissions`, `CalendarSource`, `Notifications`, `TrayIndicator`, `SecretStore`.
- Production implementation: `AgentPlatformCapabilities` in `adapter-agent-protocol`, **one class for all OSes**, which talks Agent Protocol v1 to the OS agent. `Notifications` and `TrayIndicator` are implemented by `UiBridge` (SSE to the UI, which calls Tauri's cross-platform plugins). `IcsCalendarSource` is pure Java and portable.
- Test implementation: the `MockAgent` process (and in-memory fakes for unit tests).
- OS differences reach the core only as **data**: `hello.capabilities`, opaque strings (`settings_uri`), and paths given by the shell (`--data-dir`, `--log-dir`, `--agent`).

### 2. Rules enforced by the build
ArchUnit tests in `app` (they fail `./mvnw verify`):
1. No class outside `adapter-agent-protocol` may use `java.lang.ProcessBuilder`, `java.lang.Runtime#exec` or `ProcessHandle`.
2. No class anywhere in the core may read `os.name`, `os.arch`, `user.home`, or environment variables such as `APPDATA`, `HOME`, `XDG_*` (exception: none; the shell passes paths).
3. No dependency on `java.awt..`, `javax.swing..`, `com.apple..`, `com.sun.jna..`, `sun.misc..`, or any `*.win32.*` / `*.mac.*` package, in any module.
4. `core-domain` depends on no framework and on no other module (`..spring..`, `..jdbc..` forbidden).
5. File paths in the domain are `java.nio.file.Path` resolved from the injected data dir; no string concatenation with `/` or `\` (checked by a Checkstyle/PMD rule [tool to choose at M0]).
6. Native libraries: the only native code loaded by the JVM is `sqlite-jdbc`'s bundled SQLite. Adding any other native dependency requires amending this ADR.

Maven enforcer: banned dependencies list (JNA, JNI wrapper libraries, platform-specific artifacts) in the parent POM.
CI: the `core` job runs on Linux; a `core-windows` job runs `./mvnw verify` nightly from M2 and on every PR from M5. A core that only works on macOS cannot merge.

### 3. Agents carry the OS
Each agent lives in `agents/<os>-capture/`, in the language best suited to its OS, implements Agent Protocol v1 and passes the **conformance kit**: the Java contract tests run against the real agent in `--test-input=<wav>` mode (file input replaces live capture, so the kit runs on CI without sound hardware).

## Checklist: adding an operating system (6 steps)

1. **Write the platform sheet.** Fill the OS row of `docs/PLATFORM.md`: capture API, permission model, calendar source, secret store, packaging format, signing. Open questions become spikes. Update `agents/<os>-capture/README.md` with the planned APIs.
2. **Build the agent skeleton and pass the handshake.** `hello` with honest `capabilities` (unsupported features omitted, never faked), heartbeats, lifeline on stdin EOF, `shutdown`. Conformance kit subset "handshake + lifecycle" green on the OS's CI runner.
3. **Implement capture + spool + STT in test-input mode.** Resample to 16 kHz mono, audio frames with `seq` and timeline, window/ack backpressure, spool before send, whisper.cpp with the OS's accelerator, `stt.final`. Full conformance kit green in `--test-input` mode, including crash/resume.
4. **Implement live capture, permissions and device events on real hardware.** System loopback + mic, `permission.status/request/denied` (including any "silent stream" detection the OS needs), `device.changed`. Manual checklist: a 30 min real call, unplugging headphones, denying each permission.
5. **Implement calendar and secrets, or declare them absent.** Native calendar if the OS has one usable without OAuth secrets, otherwise rely on the core's `IcsCalendarSource`; secret store native, otherwise declare `secrets` absent and let the core refuse to store keys in clear text (the UI explains). No core change is allowed in this step; if one seems needed, stop and amend ADR-002 instead.
6. **Package, sign, and add the CI jobs.** jlink runtime and CDS archive for the OS, Tauri bundle target (MSI, AppImage/deb...), signing, clean-quit smoke test (no leftover JVM), `core-<os>` and `bundle-<os>` jobs required on PRs. Update `docs/PLATFORM.md` status and the README download section.

Definition of done for a new OS: the byte-identical `interline-core.jar` from the Linux job runs in the new bundle, the conformance kit and the smoke test are green on that OS, and the diff of the port touches only `agents/<os>-capture/`, CI files, packaging config and docs.

## Consequences

- Positive: the portability promise is testable on every PR; ports are bounded projects; reviewers have mechanical rules instead of judgement calls.
- Negative: a few things that would be one line of Java (open a URL, find the home directory) must go through the shell or the agent; ArchUnit rules need maintenance when packages move; some features (for example a global hotkey) may need new capability flags and protocol messages instead of a quick local hack.
