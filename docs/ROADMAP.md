# ROADMAP - Milestones M0 to M5

Status: draft 2, 2026-11-13. Replaces draft 1 (Rust core). Estimates in person-days (pd) for a developer comfortable with Java/Spring and React, learning the Swift audio APIs, review excluded. Each milestone ends with a usable build and a criterion checkable by hand or in CI.
Total v1 (M0 to M4): **~45.5 pd** (9 weeks full time, 5 to 6 months of evenings and weekends); no Apple account delay (ADR-004). Ports (M5): **28 to 39 pd**.
Order rationale: M0 proves the architecture and its portability with no audio at all; M1 then attacks the riskiest platform piece (real macOS capture and TCC) on a skeleton that is already tested end to end.

## M0 - Monorepo scaffold and end-to-end pipeline with the MockAgent (<= 5 pd)

- **Goal**: prove that the hybrid architecture works and is portable before writing any native code: a WAV goes through agent -> core -> SQLite -> UI on a Linux CI runner without any audio device.
- **Content**:
  - Monorepo tree (ARCHITECTURE section 15), MIT `LICENSE`, `CLAUDE.md` / `AGENTS.md` (main branch, build, test, lint commands for every part).
  - `services/core` Maven multi-module skeleton: `core-domain`, `core-persistence`, `core-ai` (empty stub port), `adapter-agent-protocol`, `mock-agent`, `app`. Spring Boot 4.1, Java 21.
  - `protocol/agent/v1/` schema and golden transcripts for the M0 subset: `hello`/`hello.ack`/`hello.reject`, `heartbeat`, `session.start`/`started`/`stop`/`stopped`, `audio.chunk`, `audio.ack`, `vad.segment`, `stt.final`, `error`, `shutdown`.
  - Java frame codec, `AgentProcess` supervisor (spawn, heartbeat watchdog, restart with backoff), `AgentPlatformCapabilities` (capture only).
  - MockAgent: replays `fixtures/audio/standup-fr-2min.wav` (stereo mic|system) with its scripted transcript, `--speed`, `--crash-at`, `--deny`.
  - Core: Flyway `V1` (meetings, capture_runs, speakers, transcript_segments, timeline_markers, settings), chunk files + acks, segment dedup, crash resume.
  - Local API: start/stop recording, list meetings, get transcript, SSE events; 127.0.0.1 + random port + per-launch token + Origin/Host checks.
  - UI: minimal React page (meeting list, Record/Stop, live transcript Me/Them) talking to the core over HTTP.
  - Tauri shell: spawns the core (with `--agent=<mock-agent launcher>` in dev), passes the token, READY line, clean shutdown. Target < 300 lines.
  - ArchUnit platform-leak rules (ADR-003) active from day one.
  - CI: `core` job on `ubuntu-24.04` (`./mvnw verify` including the contract and end-to-end tests), `ui` job (lint, typecheck, one Playwright smoke test against core + MockAgent). `release.yml` skeleton with empty macOS/Windows/Linux jobs.
  - ADR-011 (core owns the data; was numbered ADR-004 in draft 2) and ADR-010 (MIT, short).
- **Exit criterion**: on the Linux runner, `./mvnw verify` passes a test where the MockAgent plays the 2 min fixture at 20x and the meeting ends `done` with Me and Them segments in SQLite, no missing `seq`, and a `--crash-at=45s` variant resumes with no sequence hole; the API security tests pass; the ArchUnit rules pass; on a Mac, `tauri dev` opens the window, the shell starts the core, Record/Stop shows the fixture transcript live, and quitting leaves no `java` process.
- **Risks**: Spring Boot 4 / Spring AI 2 / Flyway SQLite module combinations [to verify at scaffold time]; scope creep on the UI (keep it ugly).
- **Duration**: 4 to 5 pd.

## M1 - Real macOS capture and local transcription (12.5 pd)

- **Goal**: a real call is captured and transcribed live, fully offline, with the Swift agent passing the same contract as the MockAgent.
- **Content**:
  - Day 1 spikes (timeboxed 1 pd total): (a) TCC attribution when the Swift agent is a child of the JVM child of the Tauri app (fallback: shell-launched agent over a Unix socket); (b) process tap in Swift; (c) `AVAudioEngine` voice processing for echo.
  - Spike (d), timeboxed 0.5 pd, after (a): confirm on a real Mac which install path keeps the microphone and system-audio permissions across a rebuild, and write the answer into ADR-004 (matrix and reset commands in ADR-004 "Spike results"). From then on, dev builds are signed with a stable self-signed identity, never ad-hoc.
  - Swift Package `agents/macos-capture`: protocol codec, process tap + mic, 16 kHz resampling, spool writer, window/ack, `level`, `device.changed`, silent-tap detection -> `permission.denied`, `permission.status/request`.
  - whisper.cpp (Metal) + Silero VAD in the agent, chunk policy from `session.start`, `stt.partial`, `stt.final`, `transcribe.file` (audio import).
  - `--test-input` mode and the conformance kit on the `agent-macos` CI job.
  - Core: model catalog, resumable download, SHA-256; echo dedup; recovery of orphaned spool at startup; audio compression decision (FLAC in Java vs agent Opus).
  - UI: recording bar (states, Me/Them meters), live transcript panel, permission banners, model download screen.
  - Benchmark: real-time factor per model on an M1 8 GB and an M2/M3.
  - ADR-009 (macOS capture choices).
- **Exit criterion**: a 30 min Zoom or Meet call transcribed live, median latency < 5 s on an M1 8 GB with `small`; WER on 3 FR and EN reference files recorded; `kill -9` of the agent mid-call resumes within 2 s with no missing transcript outside the marked gap; denying system audio is shown in < 10 s; no network request during the call (`nettop` or Little Snitch); conformance kit green on macOS CI.
- **Risks**: TCC attribution through a JVM parent; thin documentation of taps; whisper.cpp Swift packaging; M1 base performance; memory of two streams plus a model; self-signed identity not keeping TCC grants (ADR-004 plan B).
- **Duration**: 12.5 pd.

## M2 - Notes and AI enhancement (10 pd)

- **Goal**: the core promise: my notes, completed, sourced, reversible.
- **Content**:
  - TipTap 3 editor: blocks with stable `id`, `anchorMs`, `origin`; per-block persistence with `client_rev`; IndexedDB journal and replay after a core restart; `raw_user` snapshot.
  - `core-ai`: Spring AI providers (OpenAI-compatible, Anthropic, Ollama), structured output, validation in `core-domain`, one retry; keys in the Keychain through `secret.*` messages.
  - Alignment blocks <-> segments, `enhance` prompt v1 (versioned files), windowed mode for small contexts.
  - AI rendering (secondary ink, rule, accept/delete, Mine/Enhanced toggle, undo all), clickable citations.
  - Action items, follow-up email draft, 4 built-in templates.
  - FTS5 search on blocks and segments, Cmd+K palette, Markdown export.
  - AI settings (providers, connection test), `ai_runs` with tokens and estimated cost.
  - 5 anonymised golden meetings + evaluation script. ADR-005, ADR-006.
  - `core-windows` nightly job starts (cheap portability check).
- **Exit criterion**: on the 5 golden meetings, with a mid-range cloud model and an 8B Ollama model: 100 % of user blocks preserved byte for byte (automated), 100 % of additions with at least one valid source, actions reviewed by hand >= 80 % relevant; "Undo enhancement" restores the exact original notes; enhancement of a 1 h meeting < 60 s in the cloud; killing the core while typing loses no keystroke (Playwright test).
- **Risks**: model quality variance (local especially); 45 s look-back calibration; TipTap `UniqueID` license (MIT or rewrite).
- **Duration**: 10 pd.

## M3 - Calendar and pre-meeting brief (7 pd)

- **Goal**: the "before" moment.
- **Content**:
  - Agent: EventKit full access, `calendar.query`, `calendar.changed`. Core: `IcsCalendarSource` (portable), sync -1 day / +14 days, `calendar_events`, `people`, `meeting_participants`.
  - Home "Today", note from an event (title + participants pre-filled), manual meetings with participants.
  - Notification 1 min before (core -> SSE -> UI -> Tauri notification plugin), never auto-recording; tray state.
  - Brief without AI (related meetings, open actions) and with AI; cache and invalidation.
  - Consent reminder + template sentence. ADR-007.
- **Exit criterion**: with a Google account added to macOS Calendar, today's events appear < 5 s after authorisation; an ICS URL gives the same result on the Linux CI (MockAgent without calendar); for a recurring meeting noted twice, the brief lists the open actions of both; calendar denied = fully usable in manual mode.
- **Risks**: attendees without email; duplicate people; brief quality with little history.
- **Duration**: 7 pd.

## M4 - Packaging, self-signed distribution and public beta (9 pd)

- **Goal**: an app anyone installs with one Terminal command (or a documented "Open Anyway" step), that updates itself, keeps its permissions across updates, and is complete enough to hand to a stranger. No Apple Developer Program (ADR-004). **The signing method itself is not decided yet: Phuong deferred it, pick it at the M4 gate between ADR-004 options 1 to 4.**
- **Content**:
  - Packaging: jlink runtime (module list confirmed by `jdeps`), CDS training run, bundle layout (ARCHITECTURE 14.3), JVM options, extracted and signed SQLite dylib.
  - Signing and release (ADR-004 release steps): project self-signed identity in CI, pinned designated requirement, inside-out signing of every Mach-O, hardened runtime or not depending on library validation [decision M4], DMG + `Interline.app.tar.gz`, signed `latest.json` for the Tauri updater, `SHA256SUMS`, `install.sh` with the pinned certificate fingerprint; `make signing-identity` and `make install` for source builds; own Homebrew tap only if the M1 spike retained it.
  - Startup/shutdown hardening: process group, `run/pids.json`, smoke test "quit and `kill -9` leave no process".
  - Budgets measured and published (ARCHITECTURE 14.6).
  - Product completeness: 4-step onboarding, full settings, audio retention + purge, trash 30 days, cascade delete, FileVault check, "What leaves your machine" screen, i18n FR/EN, accessibility pass (keyboard, VoiceOver, contrast, reduced motion). ADR-008.
  - README (install section with the four ADR-004 paths and screenshots of "Open Anyway"), contributing guide, privacy policy, permission troubleshooting page, issue templates (no telemetry).
- **Exit criterion**: on a clean Mac (macOS 14.2 and the current release, 27 today), the ADR-004 install script installs and launches the app with no Gatekeeper dialog, and the DMG path works by following the README "Open Anyway" steps; update 0.1.0 -> 0.1.1 installs through the updater and **microphone and system audio permissions and Keychain access are kept** (checked by recording a call right after the update: no new prompt, neither stream silent); cold start <= 2 s and idle RAM <= 350 MB on an M1 8 GB; installed size <= 200 MB; a first-time tester installs, authorises, downloads a model and records a first meeting in < 5 min without help.
- **Risks**: self-signed identity and library validation without a Team ID; users put off by the "Open Anyway" step; signing key or updater key loss.
- **Duration**: 9 pd.

## M5 - Windows and Linux agents (post-v1, 28 to 39 pd)

Details per OS: `docs/PLATFORM.md`. Each port follows the 6-step checklist of ADR-003.
- **M5a Windows (15 to 20 pd)**: Windows agent (WASAPI process or endpoint loopback, mic, Credential Manager, whisper.cpp Vulkan/CUDA/CPU), ICS calendar, MSI + Authenticode, `core-windows` and `bundle-windows` CI jobs required.
- **M5b Linux (13 to 19 pd)**: Linux agent (PipeWire monitor, mic, libsecret, whisper.cpp CPU/Vulkan), ICS calendar, AppImage + `.deb`, `bundle-linux` CI job required.
- **Exit criterion per OS**: the byte-identical core jar runs in the new bundle; the conformance kit and the clean-quit smoke test are green on that OS's runner; a 30 min real call is transcribed; the port's diff touches only `agents/<os>-capture/`, CI, packaging config and docs.

## After v1 (v2, not estimated finely)

| Block | Content | Order of magnitude |
|---|---|---|
| MCP server (read-only) | Spring AI MCP server, loopback, user-visible token, search/notes/actions tools | 3 to 5 pd |
| Chat over history | local embeddings, `sqlite-vec` (native extension per OS to validate), hybrid search, side panel | 8 to 12 pd |
| Experimental diarisation | agent-side ONNX (`diarize.file` capability), post-processing, renaming | 5 to 8 pd |
| Encryption at rest | SQLCipher-compatible driver [to verify for JDBC] + encrypted audio, key in the OS secret store | 4 to 6 pd |
| Markdown mirror | synced folder (Obsidian) | 3 pd |
