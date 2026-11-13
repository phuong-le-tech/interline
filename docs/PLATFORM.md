# PLATFORM - Operating system support

Status: 2026-11-13. Rules: `docs/adr/ADR-003-ports-plateforme.md`. Contract every agent implements: `docs/ARCHITECTURE.md` section 5.
What never changes between OSes: the core jar (byte-identical), the React UI, the Tauri shell code, the database, prompts and models (same ggml files).

## Summary

| | macOS | Windows | Linux |
|---|---|---|---|
| Status | **v1 (M1 to M4)** | Planned (M5a) | Planned (M5b) |
| Agent | `agents/macos-capture`, Swift | `agents/windows-capture`, Rust or C++ [decision M5a] | `agents/linux-capture`, Rust or C [decision M5b] |
| Minimum OS | macOS 14.2 (process taps) | Windows 10 21H2+ / 11 [to verify per API below] | Distros with PipeWire (or PulseAudio) and WebKitGTK 4.1 |
| Port effort | in v1 roadmap | **15 to 20 days** | **13 to 19 days** |

## macOS (v1)

- **System audio**: Core Audio process tap (`CATapDescription` global, excluding Interline's own processes, `AudioHardwareCreateProcessTap`) inside a private aggregate device. ScreenCaptureKit audio only as an opt-in fallback [decision M1].
- **Microphone**: `AVAudioEngine` input node; voice-processing mode for echo cancellation if the M1 spike succeeds.
- **Permissions asked**: Microphone (`NSMicrophoneUsageDescription`, entitlement `com.apple.security.device.audio-input`); System audio recording only (`NSAudioCaptureUsageDescription`, prompt appears at first tap creation, no API to ask earlier; denial = silent stream, detected by the agent); Calendars full access (`NSCalendarsFullAccessUsageDescription`), optional.
- **Calendar**: EventKit (`requestFullAccessToEvents`), covers Google, Exchange and iCloud accounts added in System Settings. ICS URL also available (core).
- **Secrets**: Keychain generic passwords.
- **STT acceleration**: whisper.cpp with Metal.
- **Packaging**: `.app` + DMG + `Interline.app.tar.gz`, **no Apple Developer Program** (ADR-004): every Mach-O (JVM included) signed with the project's self-signed identity, not notarized; recommended install = one-command `install.sh` from the GitHub release (no quarantine, checks SHA-256 and certificate fingerprint), DMG with "Open Anyway" as fallback, own Homebrew tap only if the M1 spike validates it; updates through the Tauri updater (minisign).
- **Main risks**: TCC attribution when the agent is a child of the JVM (fallback: agent launched by the shell over a Unix socket); whether a self-signed identity keeps microphone and system-audio grants across updates (M1 spike, ADR-004 plan B otherwise).

## macOS version support

| macOS | What works | What it costs |
|---|---|---|
| **14.2 and later (v1 target)** | Core Audio process taps: system audio without a bot, microphone, permission prompts "Microphone" and "System Audio Recording Only" as above | Nothing extra: this is the v1 path |
| 13.0 to 14.1 | No process taps. System audio only through ScreenCaptureKit, which requires the "Screen Recording" permission: the user is asked to let a notepad record their screen, an alarming wording for what we do | 3 to 4 days of extra work (second capture path in the agent, its permission flow and UI copy, tests) |
| 12 and earlier | No supported system audio capture API for an app like ours. Only a virtual audio device (BlackHole or similar) that the user installs and routes by hand in Audio MIDI Setup | **Out of scope**: we neither support nor document it |

v1 decision: minimum **macOS 14.2**; ScreenCaptureKit stays an opt-in fallback, built only if users ask for it; earlier versions are unsupported (`LSMinimumSystemVersion` 14.2 in `Info.plist`, so macOS refuses to launch the app; `install.sh` checks the version first and says so).
Hardware: **Apple Silicon first** (tested, Metal acceleration, budgets measured on an M1 8 GB). Intel Macs: capture, Metal transcription speed and the release architecture (arm64-only or universal) [to verify; decision M4].

## Windows (planned, M5a)

- **System audio**: WASAPI loopback. Preferred: process loopback (`ActivateAudioInterfaceAsync` with `AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK`, excluding our own process tree) [to verify minimum build, believed Windows 10 2004+ / 11]; fallback: endpoint loopback on the default render device (`AUDCLNT_STREAMFLAGS_LOOPBACK`), which also captures our own sounds.
- **Microphone**: WASAPI capture on the default communications device; optional Windows voice capture DSP for echo cancellation [to verify].
- **Permissions asked**: none for loopback; microphone governed by Settings > Privacy > Microphone, "Let desktop apps access your microphone" (no per-app prompt for unpackaged desktop apps; the agent reports `denied` when the global switch is off) [to verify].
- **Calendar**: no OAuth secret in an open source app, so ICS subscription (core) by default; WinRT `Windows.ApplicationModel.Appointments` [to verify whether usable without package identity] or Microsoft Graph with a user-provided app registration as later options.
- **Secrets**: Credential Manager (`CredWriteW` / `CredReadW`).
- **STT acceleration**: whisper.cpp with Vulkan or CUDA, CPU fallback [to verify which builds to ship].
- **Packaging**: MSI (Tauri WiX target) or NSIS [decision M5a], Authenticode signing of shell, agent and JVM launcher, WebView2 bootstrapper, jlink runtime for Windows.
- **Effort, 15 to 20 days**: agent capture + spool + protocol 6 to 8, whisper.cpp integration and accelerator choice 2 to 3, permissions/devices/secrets 2 to 3, packaging + signing + CI 3 to 4, real-call testing 2.

## Linux (planned, M5b)

- **System audio**: monitor source of the default sink. Preferred: PipeWire native (`pw_stream` on the sink monitor); fallback: PulseAudio API (works on PipeWire through `pipewire-pulse`) recording `@DEFAULT_MONITOR@` [to verify naming].
- **Microphone**: default PipeWire/PulseAudio source; echo cancellation through PipeWire's `echo-cancel` module if loaded [to verify].
- **Permissions asked**: none for native packages (`.deb`, AppImage). Flatpak would require the audio portal / PulseAudio socket permission; Flatpak is out of scope until investigated.
- **Calendar**: ICS subscription in the core (portable, default); CalDAV in the core later; Evolution Data Server optional [to verify interest].
- **Secrets**: Secret Service API via libsecret (GNOME Keyring, KWallet). If no Secret Service is running, the agent omits `secrets` and the UI explains that keys cannot be stored (never stored in clear text).
- **STT acceleration**: whisper.cpp CPU (AVX2) with Vulkan optional.
- **Packaging**: AppImage + `.deb` (Tauri targets), checksums + signature (minisign or GPG), jlink runtime for Linux.
- **Effort, 13 to 19 days**: agent capture + spool + protocol 5 to 7, whisper.cpp build matrix 2 to 3, secrets + device events 2 to 3, packaging + CI 2 to 3, testing on 2 to 3 distros (Ubuntu LTS, Fedora, one KDE) 2 to 3.

## Already abstracted in the core (zero work per OS)

- Domain, pipeline orchestration (alignment, echo dedup), enhancement, brief, search, export.
- Persistence (SQLite through `sqlite-jdbc`, which ships natives for all three OSes in the same jar), Flyway migrations.
- LLM providers, prompts, MCP server, local API and its security.
- Model catalog, download, verification (the agent only loads a path).
- ICS calendar source, notifications and tray (through the UI and Tauri plugins), data and log paths (given by the shell).
- `PlatformCapabilities` port with a single agent-backed implementation; the MockAgent runs the full pipeline on any OS.
- Contract tests and conformance kit that every new agent must pass.
