# ARCHITECTURE - Interline technical architecture (hybrid stack "B")

Status: draft 2, 2026-11-13. Replaces draft 1 (pure Tauri + Rust core). Decision record: `docs/adr/ADR-001-stack-hybride.md`.
Legend: **[to verify]** = version or platform fact not confirmed at writing time (never treat it as fact). **[decision M<n>]** = deliberately deferred, settled by a spike in that milestone.
v1 ships on macOS only. The architecture is portable from day one: Windows and Linux are added by writing a new capture agent, never by touching the core or the UI (`docs/PLATFORM.md`, `docs/adr/ADR-003-ports-plateforme.md`).

## 1. Constraints that shape everything

1. **No OS API leaks into the core.** The Spring Boot core is one jar that runs unchanged on macOS, Windows and Linux. Everything OS-specific lives in the capture agent (native, one per OS) or in Tauri (already cross-platform).
2. **The shell is dumb.** Tauri owns windows, the webview, the tray and process supervision. No business logic, no audio, no SQL, no network in Rust. Budget: 200 to 400 lines of Rust.
3. **Local-first, zero implicit network.** Unchanged from draft 1: audio never leaves the machine, LLM calls only to the provider the user configured.
4. **Never lose notes, never lose more than ~2 s of audio.** Notes never transit through the agent. Audio is spooled to disk by the agent before it is sent, and acknowledged by the core only once durable.
5. **The whole pipeline must run in CI on a Linux runner with no audio device**, through a `MockAgent` that replays a WAV fixture.

## 2. Process view

```
+--------------------------------------------------------------------------------+
| (1) Webview - React 19 + TypeScript (WKWebView / WebView2 / WebKitGTK)         |
|     Editor, transcript, brief, settings. No SQL, no secrets, no OS calls.      |
+---------------+-----------------------------------------+----------------------+
                | Tauri IPC (3 commands only:             | HTTP/JSON + SSE
                | get_core_endpoint, set_tray_state,      | 127.0.0.1:<random port>
                | restart_core)                           | Bearer <per-launch token>
+---------------v-----------------+                       |
| (2) Shell - Tauri 2 (Rust)      |                       |
|     window, tray, notifications |                       |
|     plugin, supervises core     |                       |
+---------------+-----------------+                       |
                | spawn + stdin/stdout "lifeline"         |
                | (token in, READY line out)              |
+---------------v-----------------------------------------v----------------------+
| (3) Core - Spring Boot 4 on Java 21 (jlink runtime, same jar on every OS)      |
|     domain, SQLite (JDBC + Flyway), pipeline orchestration, LLM providers,     |
|     model downloads, local API, MCP server (off by default)                    |
+---------------+----------------------------------------------------------------+
                | spawn + Agent Protocol v1 over stdio
                | (length-prefixed frames: JSON control + binary PCM)
+---------------v----------------------------------------------------------------+
| (4) Capture agent - native, one per OS                                         |
|     macOS v1: Swift (Core Audio process tap + AVAudioEngine mic, whisper.cpp   |
|     Metal, Silero VAD, EventKit, Keychain, TCC)                                |
|     Windows post-v1: WASAPI loopback ; Linux post-v1: PipeWire monitor         |
|     CI: MockAgent (Java) replays a WAV fixture + scripted transcript           |
+--------------------------------------------------------------------------------+
        |                                          |
   <data dir> given by the shell               Network: only the configured LLM,
   db.sqlite, audio/, spool/, models/, logs/   Hugging Face (models), GitHub (updates)
```

Who talks to whom (and nothing else):

| From -> To | Channel | Content |
|---|---|---|
| UI -> Shell | Tauri `invoke` | Endpoint lookup, tray state, "restart core" button |
| UI -> Core | HTTP/JSON, SSE | Everything functional |
| Shell -> Core | process spawn, stdin/stdout | Launch args, token, `shutdown`, READY line |
| Core -> Agent | process spawn, Agent Protocol v1 on stdio | Sessions, permissions, calendar, secrets, transcription |
| Agent -> OS | native APIs | Audio, TCC, EventKit, Keychain |
| UI -> Agent, Shell -> Agent | **none** | Forbidden: the agent has a single client, the core |

Why the core (not the shell) spawns the agent: the core owns the session lifecycle and the recovery logic (section 5.8); stdio gives free, portable death detection (EOF). Fallback if macOS TCC attributes permissions badly to a grandchild process: the same protocol over a Unix domain socket with the shell as parent (section 5.2).

## 3. Responsibilities

| Concern | UI | Shell | Core | Agent |
|---|---|---|---|---|
| Rendering, editor, keyboard | x | | | |
| Window, tray, OS notifications (Tauri plugins) | triggers | x | asks via SSE | |
| Process supervision | | core | agent | |
| Domain, persistence, search | | | x | |
| Pipeline orchestration (alignment, echo dedup, enrichment) | | | x | |
| LLM calls, prompts, JSON validation | | | x | |
| Model catalog, download, SHA-256 | | | x | loads the file it is given |
| Audio capture, resampling, VAD, STT (whisper.cpp) | | | policy only | x |
| Permissions, calendar, secret storage | | | ports | x |
| ICS calendar subscription (portable) | | | x | |

STT lives in the agent on purpose: whisper.cpp needs native acceleration (Metal, CUDA, Vulkan) and keeping it out of the JVM is what lets the core stay one portable jar with no per-OS native inference library. Cost: each agent links whisper.cpp (C/C++, already portable). Chunking policy (silence, chunk length, overlap, language, initial prompt) is decided by the core and sent in `session.start`, so agents implement mechanics, not policy.

## 4. Core (Spring Boot)

### 4.1 Modules (Maven multi-module, `services/core/`)

| Module | Depends on | Content | Spring? |
|---|---|---|---|
| `core-domain` | nothing | Entities, pipeline logic (alignment, echo dedup, enrichment validation), **ports**: `PlatformCapabilities`, `MeetingRepository`, `LlmPort`, `ModelStore`, `Clock` | No (plain Java 21) |
| `core-persistence` | domain | `JdbcClient` repositories, Flyway migrations, FTS5 queries | Spring JDBC |
| `core-ai` | domain | Spring AI providers (OpenAI-compatible, Anthropic, Ollama), versioned prompts, structured output, retry | Spring AI |
| `adapter-agent-protocol` | domain | Frame codec, message types, `AgentProcess` supervisor, `AgentPlatformCapabilities` | No |
| `mock-agent` | protocol codec | Standalone `main`: speaks Agent Protocol v1, replays WAV + scripted transcript, fault injection | No |
| `app` | all | Boot wiring, REST + SSE API, security filter, MCP server, config, CDS training entry point | Yes |

Hexagonal rule: `core-domain` knows ports, never adapters. Enforced by ArchUnit (section 16) and by Maven module boundaries.

### 4.2 Runtime choices
- Java 21 (LTS), virtual threads on (`spring.threads.virtual.enabled=true`).
- No JPA/Hibernate: `JdbcClient` + SQL. Reasons: startup time, memory, and SQLite dialect friction.
- SQLite via `sqlite-jdbc` (bundled natives for macOS, Windows, Linux inside the same jar), WAL mode, `foreign_keys=ON`, `busy_timeout=5000`. **One writer connection** (dedicated pool of size 1) + a small read pool. All writes go through a single executor: no `SQLITE_BUSY` in practice.
- Flyway migrations `V<n>__*.sql`, forward-only, never destructive (columns are added, renamed via copy, never dropped in the same release).
- Paths: the core never computes OS paths. The shell passes `--data-dir`, `--log-dir`, `--agent=<path>` (Tauri path API resolves them per OS).
- Logs: local rolling files only, no remote sink.

## 5. Agent Protocol v1 (agent <-> core contract)

This section is the normative contract. The JSON Schema and golden transcripts live in `protocol/agent/v1/` and are shared by the Java and Swift test suites. Changes follow `docs/adr/ADR-002-contrat-agent-capture.md`.

### 5.1 Transport and framing
- **Transport: the agent's stdin/stdout**, the core is the parent process. stderr = agent logs (the core copies them to `logs/agent.log`, never parses them). Nothing else may write to stdout: agents must route all diagnostic output to stderr (an agent printing to stdout is a protocol violation, caught by the conformance kit).
- **Why stdio**: identical on the three OSes and in Java (`ProcessBuilder`), no port, no firewall prompt (Windows), no authentication to design (only the parent holds the pipes), death of either side is visible immediately as EOF, and it is trivially recorded and replayed for tests. Alternatives rejected in ADR-002.
- **Frame**: `u32 length (big-endian, bytes after this field) | u8 kind | payload`.
  - `kind = 0x01` control: UTF-8 JSON object, max 1 MiB.
  - `kind = 0x02` audio: binary header + PCM (5.4), max 64 KiB.
  - Any other kind, or a length over the limit: receiver sends `error{code:"bad_frame"}` and closes the stream (fatal, the core restarts the agent).
- **Control envelope**:
  ```json
  {"v":1, "t":"session.start", "id":"c-42", "ts":1763030400123, "body":{...}}
  ```
  `t` = message type, `id` = request id (requests only, unique per sender, prefix `c-` core, `a-` agent), `re` = id of the request being answered (responses only), `ts` = sender wall clock in epoch ms (diagnostics only, never used for alignment). Unknown fields are ignored. Unknown `t`: requests get `error{code:"unsupported_message", re}`, events are ignored and logged once.

### 5.2 Process lifecycle and handshake
1. Core spawns `<agent> --protocol=1 --log-level=info` (the path comes from the shell, never guessed).
2. Agent writes `hello` within 1 s. Core answers `hello.ack` (or `hello.reject` + agent exits with code 3).
3. Agent sends `heartbeat` every 2 s, idle or not. 3 missed heartbeats (6 s) = hung: the core kills it (SIGKILL / TerminateProcess via `ProcessHandle.destroyForcibly`) and restarts it.
4. Agent exits within 2 s of stdin EOF (lifeline: parent death detection that works on every OS, even when the parent was SIGKILLed). Before exiting it finalises its spool files.
5. Core sends `shutdown` on normal stop; agent stops any session (5.8 rules), answers `shutdown.ok`, exits 0.
6. Restart policy: backoff 0.5 s, 1 s, 2 s, 4 s; more than 5 crashes in 60 s = give up, capture marked unavailable, UI shows the error with "Retry".

Fallback binding (only if the M1 TCC spike requires the shell to be the agent's parent): same frames over a Unix domain socket (`java.net.UnixDomainSocketAddress`, supported by Java 16+ on macOS, Linux and Windows 10 1803+ [to verify on Windows]), socket path inside the data dir, mode 0600, path and a one-time token passed by the shell. The message layer does not change.

`hello` (agent -> core):
```json
{"v":1,"t":"hello","id":"a-1","ts":0,"body":{
  "protocol":{"min":1,"max":1},
  "agent":{"name":"interline-capture-macos","version":"0.1.0","os":"macos","os_version":"15.4","arch":"arm64"},
  "capabilities":{
    "capture":{"streams":["mic","system"],"aec":true,"device_events":true},
    "stt":{"engine":"whisper.cpp","engine_version":"1.9.5","partials":true,"accel":"metal","transcribe_file":true},
    "permissions":["microphone","system_audio","calendar"],
    "calendar":{"source":"eventkit"},
    "secrets":{"store":"keychain"},
    "test_input":true
  }}}
```
`hello.ack` (core -> agent): `{"re":"a-1","body":{"protocol":1,"core_version":"0.1.0","session_defaults":{...}}}`.
Negotiation: the core picks the highest major present in both ranges. No overlap -> `hello.reject{reason:"protocol_version_unsupported", core_supports:{min,max}}`. Optional features are discovered through `capabilities`, never through version numbers.

### 5.3 Message catalogue

| Type | Dir | Kind | Purpose |
|---|---|---|---|
| `hello` / `hello.ack` / `hello.reject` | A->C / C->A | req/resp | Version negotiation, capabilities |
| `heartbeat` | A->C | event | Liveness, `stt_backlog_ms`, `rss_mb` |
| `session.start` / `session.started` | C->A / A->C | req/resp | Start (or resume) capture + STT |
| `session.pause` / `session.resume` | C->A | req | Pause really stops capture (US-1.4) |
| `session.stop` / `session.stopped` | C->A / A->C | req/resp | Stop, drain STT, final sequence numbers |
| `audio.chunk` | A->C | audio frame (0x02) | PCM 16 kHz mono s16le, 100 ms |
| `audio.ack` | C->A | event | Durable up to `seq` + window (backpressure) |
| `level` | A->C | event | RMS per stream, 10 Hz, for the VU meters |
| `vad.segment` | A->C | event | Speech region opened / closed |
| `stt.partial` | A->C | event | Provisional text of the open region (replaced later) |
| `stt.final` | A->C | event | Final text of a closed region |
| `device.changed` | A->C | event | Default device changed, stream rebuilt |
| `permission.status` / `permission.request` | C->A / A->C | req/resp | Read or trigger the OS prompt |
| `permission.denied` | A->C | event | Denied explicitly or detected (silent tap) |
| `calendar.query` / `calendar.events` | C->A / A->C | req/resp | Events in a window |
| `calendar.changed` | A->C | event | OS calendar store changed |
| `secret.get` / `secret.put` / `secret.delete` | C->A | req | OS secret store (Keychain, Credential Manager, libsecret) |
| `transcribe.file` | C->A | req + events | Import of an audio file (US-1.7), emits `stt.final` |
| `error` | both | event or resp | See 5.7 |
| `shutdown` / `shutdown.ok` | C->A / A->C | req/resp | Clean exit |

### 5.4 Audio streaming
- **Format on the wire**: PCM signed 16-bit little-endian, **16 000 Hz, mono**, per stream. The agent captures at device rate (44.1 or 48 kHz, stereo), downmixes and resamples natively (AVAudioConverter on macOS). The core never does DSP.
- **Chunk**: 100 ms = 1 600 samples = 3 200 bytes. Two streams = 20 frames/s, ~64 KiB/s. Small enough for 10 Hz meters and low latency, large enough that framing overhead is < 1 %.
- **Audio frame header** (24 bytes, little-endian), then PCM:
  `u8 stream (0 mic, 1 system) | u8 flags (bit0 recovered, bit1 discontinuity_before, bit2 silence_fill) | u16 reserved | u32 seq | u64 first_sample | u64 host_time_ns`
  - `seq`: per stream, starts at 0, +1 per frame, never reused within a session (survives agent restarts, see 5.8).
  - `first_sample`: index on the **session timeline** (sample 0 = `session.started.t0`). Both streams share the timeline: the agent pads the later-starting stream so both start at sample 0. A gap in `first_sample` with `discontinuity_before` = audio lost or device switch; the timeline never shifts.
  - `host_time_ns`: monotonic host clock, diagnostics only.
- **Backpressure: sliding window, acknowledgement = durability.** The core sends `audio.ack {stream, seq, window}` once the frames up to `seq` are fsynced into its chunk files (at least every 500 ms). The agent may have at most `window` (default 50 frames = 5 s) unacked frames in flight per stream. When the window is full, the agent **does not drop and does not block the audio callback**: frames keep going to the spool file (5.8) and are read back from disk when the window reopens. In-memory ring per stream: 10 s; spool on disk: unbounded except disk space. If the backlog exceeds 60 s the agent emits `error{code:"backpressure", severity:"warn"}` (UI shows "processing is behind", nothing is lost).
- **Mute vs pause**: on `session.pause`, capture stops (not muted, stopped); on resume, `first_sample` jumps by the paused duration with `discontinuity_before`.

### 5.5 VAD and STT events
`session.start.body.stt` carries the policy: `model_path`, `model_sha256`, `vad_model_path`, `language` (`"fr"`, `"en"` or `null` = detect once on the first 30 s of speech, then fixed), `initial_prompt` (title + participant names), `min_silence_ms: 500`, `target_chunk_s: [5, 15]`, `max_chunk_s: 30`, `overlap_ms: 500`, `partials: true`.

**Where `language` comes from.** The default is the config property `interline.stt.language` (`fr` when unset; `auto` means detect and is sent as JSON `null`). `POST /api/v1/recording/start` takes an optional body `{"language": "en"}` that overrides the default for that meeting only. The effective value is stored in `meetings.language` and is what the core then sends in every `session.start` for that meeting, including the resumed one after a core or agent restart, and in `transcribe.file` for recovery and import jobs. Anything other than `auto` or a 2 to 3 letter lowercase code is rejected with HTTP 400 (`invalid_language`).

```json
{"t":"vad.segment","body":{"session_id":"m_01J...","stream":"system","seg":"s-118","start_sample":1923200,"end_sample":null,"state":"open"}}
{"t":"stt.partial","body":{"session_id":"m_01J...","stream":"system","seg":"s-118","rev":2,"text":"on garde le budget du Q1"}}
{"t":"stt.final","body":{"session_id":"m_01J...","stream":"system","seg":"s-118","start_sample":1923200,"end_sample":2081600,
  "text":"On garde le budget du Q1 tel quel.","lang":"fr","avg_logprob":-0.31,"model":"large-v3-turbo-q5_0",
  "words":null}}
```
- `stt.partial` is best effort: re-decode of the open region every ~2 s while compute allows (`partials:false` on slow machines). Each `rev` replaces the previous one. The core never persists partials.
- `stt.final` is the only persisted transcript event. Its `seg` closes the matching `vad.segment`. Delivery is at-least-once: the core deduplicates on `(session_id, stream, seg)`.
- Whisper does not stream word by word; displayed latency target 2 to 5 s (unchanged from draft 1).

### 5.6 Device and permission events
```json
{"t":"device.changed","body":{"session_id":"m_01J...","stream":"system","reason":"default_output_changed",
  "device":{"name":"AirPods Pro","sample_rate":48000,"channels":2},"discontinuity_at_sample":4800000}}
{"t":"permission.denied","body":{"permission":"system_audio","detection":"silent_stream",
  "evidence":"5 s of exact zeros while another process produces output","can_prompt_again":false,
  "settings_uri":"x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture"}}
```
`detection` is `explicit` (API returned denied) or `silent_stream` (macOS process taps return zeros when denied, there is no error). `settings_uri` is opaque to the core: it is passed as is to the UI, which asks the shell to open it. The exact macOS anchor is [to verify].

### 5.7 Errors
```json
{"t":"error","re":"c-42","body":{"code":"model_load_failed","severity":"fatal","retryable":true,
  "session_id":"m_01J...","message":"ggml model file truncated"}}
```
Codes: `bad_frame`, `unsupported_message`, `protocol_version_unsupported`, `invalid_request`, `device_unavailable`, `permission_missing`, `model_load_failed`, `stt_failed`, `spool_io`, `backpressure`, `secret_not_found`, `calendar_unavailable`, `internal`. `severity`: `warn` (session continues) or `fatal` (session or agent unusable; the core decides whether to restart). Messages never contain secrets or transcript text. Agent exit codes: 0 normal, 2 bad arguments, 3 protocol mismatch, >= 10 internal.

### 5.8 Session lifecycle, spool and crash recovery

`session.start` (core -> agent):
```json
{"v":1,"t":"session.start","id":"c-42","body":{
  "session_id":"m_01JCX...","streams":["mic","system"],
  "audio":{"sample_rate":16000,"format":"s16le","channels":1,"chunk_ms":100,"window":50},
  "spool_dir":"<data>/spool/m_01JCX.../run-2",
  "stt":{ "...see 5.5..." },
  "resume":null }}
```
`session.started` returns `t0_wall_ms`, the devices in use, and the permission state seen at start.

**Spool (the rescue WAV).** Every captured frame is written by the agent to `spool_dir/<stream>-<firstSeq>-<firstSample>.wav` (16 kHz mono s16le, at most 60 s per file, frames contiguous inside a file so a discontinuity opens a new file, header size rewritten every second; readers take `seq` and `first_sample` from the file name and tolerate a stale header) **before** it is sent. Spool files whose frames are all acked are deleted. The spool is the agent's only state; the core's chunk files are the source of truth.

**Core side.** Received frames are appended to `audio/<meeting>/<stream>/<n>.pcm` (10 s chunk files, fsync per chunk boundary or every 1 s), then acked. Meeting state, transcript segments and the `stt_done_until_sample` watermark per stream are in SQLite.

**Agent dies during a meeting** (EOF, non-zero exit, or heartbeat timeout):
1. Core marks the session `degraded`, UI shows "Capture restarting" (the editor is untouched: notes never go through the agent).
2. Core restarts the agent (backoff 5.2) and sends `session.start` with
   `resume: {"previous_spool_dirs":[...], "acked":{"mic":1832,"system":1830}, "stt_done_until_sample":{"mic":2880000,"system":2864000}, "next_seq":{"mic":1833,"system":1831}}`.
3. The new agent first replays unacked frames found in the previous spool, flagged `recovered`, then starts live capture: the first live frame carries `discontinuity_before` (the gap is the restart time, target < 2 s, recorded as a marker in the transcript).
4. It re-runs VAD/STT from `stt_done_until_sample` on recovered + live audio, so no transcript hole.
5. If the restart budget is exhausted: the session stays open in "notes only" mode, the user is told plainly, and the core imports every spool file it can read when the session is stopped (via `transcribe.file` on a later agent start, or at next launch).

**Core dies during a meeting**: the agent sees stdin EOF, finalises its spool and exits within 2 s. The shell restarts the core (6.3); at startup the core finds the meeting in `recording` with a recent heartbeat, imports orphaned spool frames, and automatically resumes capture into the same meeting (new run, discontinuity marker). The UI keeps unsaved note edits in an IndexedDB journal and replays them once the core answers (section 8.4).

**App or machine crash**: at next launch the core scans `spool/` and `audio/` for meetings left in `recording`, rebuilds what it can, transcribes the recovered audio through `transcribe.file`, and shows "Recovered meeting" in the list (US-1.6).

**Never lose notes**: notes are written UI -> core only (debounced 500 ms, plus the IndexedDB journal); the agent cannot affect them by crashing.

### 5.9 Non-capture requests
- `permission.status {permissions:[...]}` -> `{microphone:"granted|denied|not_determined|restricted", system_audio:"granted|unknown|denied", calendar:...}`. `permission.request {permission}` triggers the OS prompt when the OS allows it. macOS has no API to ask for the system-audio permission in advance: the prompt appears on first tap creation, so `system_audio` stays `unknown` until a session runs (draft 1 finding).
- `calendar.query {from_ms, to_ms}` -> `calendar.events {events:[{external_id, title, starts_at, ends_at, attendees:[{name,email}], conference_url, calendar_name}]}`.
- `secret.get {account}` -> `{value}` or `error secret_not_found`. Keys transit only on this private pipe and are never logged on either side.

## 6. The IPC port as seen from the core

### 6.1 Total OS insensitivity
The domain sees two things only: the `PlatformCapabilities` port (section 7) and domain events (`AudioChunkPersisted`, `TranscriptSegmentFinalised`, `CaptureDegraded`, `PermissionDenied`...). Nothing above `adapter-agent-protocol` knows that a process, a pipe or an OS exists.

```
core-domain            : PlatformCapabilities, CaptureSession, events      (ports)
adapter-agent-protocol : AgentPlatformCapabilities -> AgentClient -> FrameCodec -> Transport
                         Transport = StdioTransport (default) | UnixSocketTransport (fallback)
                         AgentProcess = ProcessBuilder + restart policy + heartbeat watchdog
```

### 6.2 What changes between macOS, Windows and Linux
Only the value of `--agent=<path>` given by the shell. Same jar (byte-identical, built once in CI and checksummed), same config, same database schema. The core contains no `os.name` check, no `java.awt`, no `com.apple.*`, no native library of its own other than the JDBC driver's bundled SQLite (which `sqlite-jdbc` ships for all three OSes in the same jar). Optional OS differences reach the core only as data: the `hello.capabilities` object and opaque strings such as `settings_uri`.

### 6.3 Shell <-> core contract (for completeness)
- Launch: `<res>/core/runtime/bin/java <jvm opts> -jar <res>/core/interline-core.jar --data-dir=<p> --log-dir=<p> --agent=<p> --parent=stdio`.
- The shell writes one line on the core's stdin: `{"token":"<256-bit base64url>"}`. Never in argv or environment (both visible to other processes of the same user through `ps`).
- The core answers one line on stdout: `{"event":"ready","port":53122,"pid":4242,"version":"0.1.0"}`. Nothing else is ever written on stdout.
- Shutdown: the shell writes `{"cmd":"shutdown"}` (or closes stdin, which means the same).

## 7. The platform capabilities port

Defined in `core-domain`. Sketch (documentation, not production code):

```java
public interface PlatformCapabilities {
    PlatformInfo info();                        // os, arch, agent version, capability flags
    AudioCapture audioCapture();                // start/stop/pause/resume, events, transcribeFile
    Permissions permissions();                  // status(), request(Permission)
    Optional<CalendarSource> calendar();        // empty when the agent has none
    Notifications notifications();              // notify(title, body, action)
    TrayIndicator tray();                       // setState(IDLE | RECORDING | WARNING)
    SecretStore secrets();                      // get/put/delete(account)
}
```

| Sub-port | Production implementation | Who does the OS work | CI implementation |
|---|---|---|---|
| `AudioCapture`, `Permissions`, `SecretStore` | `AgentPlatformCapabilities` (one Java class for all OSes) | the OS agent | `MockAgent` process |
| `CalendarSource` | `AgentCalendarSource` + `IcsCalendarSource` (pure Java, portable, used by Linux and as a fallback everywhere) | agent or core | `MockAgent` scripted events |
| `Notifications`, `TrayIndicator` | `UiBridge` (publishes an SSE event; the UI calls the Tauri notification plugin / `set_tray_state`) | Tauri (cross-platform) | recorded by a test listener |

"One implementation per OS" is realised as **one agent binary per OS behind a single Java adapter**. This is stricter than one Java class per OS: the Java side has no OS branch at all, so it cannot drift.

**MockAgent (mandatory).** Module `mock-agent`, a runnable jar speaking Agent Protocol v1:
- `--wav=fixtures/audio/standup-fr-2min.wav` (stereo: left = mic, right = system) and `--script=fixtures/audio/standup-fr-2min.transcript.json` (VAD regions and STT text per stream, so no whisper is needed in CI).
- `--speed=1|10|50` (real time or accelerated), `--calendar=fixtures/calendar/week.json`, in-memory secret store.
- Fault injection: `--crash-at=45s`, `--hang-at=30s`, `--deny=system_audio`, `--silent-system`, `--device-change-at=20s`, `--ignore-window` (backpressure abuse), `--corrupt-frame-at=10s`, `--protocol=2`.
- Used by contract tests, the end-to-end pipeline test, UI Playwright tests, and local development of the UI without any audio (`./dev --mock`).

Rules that keep it honest: the MockAgent uses the same codec as the core but is launched as a separate process through the same `AgentProcess` class, so the transport, framing and supervision paths are the real ones.

## 8. Local API (core <-> UI) and its security

### 8.1 Shape
- REST/JSON under `/api/v1` (meetings, blocks, transcript, actions, briefs, search, settings, providers, models, recording control, platform status). Errors as `application/problem+json` (RFC 9457).
- Server events: one SSE stream `/api/v1/events` (recording state, levels, `stt.partial`/`stt.final` relays, `transcript.suppressed` when the echo dedup hides a mic final, enrichment progress, notifications, tray state). Consumed with a fetch-based SSE client because `EventSource` cannot send an `Authorization` header.
- OpenAPI generated by the core (springdoc [to verify version for Boot 4]); TypeScript types generated from it (`openapi-typescript`); CI fails on drift.

### 8.2 Security rules
1. **Bind 127.0.0.1 only** (never `0.0.0.0`, never `::`), **port 0** (random, chosen by the OS), reported to the shell in the READY line.
2. **Per-launch token**: 256 bits from the OS CSPRNG, generated by the shell, sent to the core on stdin, handed to the webview by `get_core_endpoint`. Required as `Authorization: Bearer` on every request, compared in constant time. A new token on every launch; never persisted, never logged.
3. **No network auth, no users, no sessions, no cookies.** Anything that is not loopback cannot connect at all.
4. **Host header check**: requests whose `Host` is not `127.0.0.1:<port>` are rejected (DNS rebinding defence).
5. **No open CORS**: allowed origins are exactly the Tauri webview origins (`tauri://localhost` on macOS and Linux, `http://tauri.localhost` on Windows [to verify for Tauri 2.12]); no wildcard, no credentials mode. Requests with any other `Origin` are rejected before reaching a controller.
6. Webview CSP: `connect-src http://127.0.0.1:*`, no remote scripts, fonts bundled.
7. The API never returns secrets (keys are write-only through the API, read only by the core via the agent).
8. **MCP server** (external clients: Claude Desktop, Cursor): disabled by default; when enabled it listens on loopback with a **separate, long-lived, user-visible token** (Settings, revocable), read-only tools first (search meetings, get notes, list open actions). Transport: MCP Streamable HTTP through the Spring AI MCP server starter [to verify exact starter for Spring AI 2.0]. Scheduled after v1 (SPEC section 6), but the module slot exists from M0 so the API stays MCP-friendly.

### 8.3 Startup and readiness seen by the UI
The shell shows the window immediately with the UI skeleton; the UI polls `get_core_endpoint` until the core is READY. After 1.5 s it shows "Starting Interline...". Target: usable in < 2 s cold on an M1 (section 14.6).

### 8.4 When the core does not answer
- Detection: 3 consecutive failed requests or the SSE stream down for more than 5 s.
- The UI shows a non-blocking banner "Interline is restarting its engine", keeps the editor editable, and writes every unsaved edit to an **IndexedDB journal** keyed by meeting and block (each entry has a client revision).
- The shell restarts the core (max 3 restarts in 60 s). On reconnection the UI replays the journal in order; the core applies edits idempotently (client revision), conflicts resolved last-writer-wins per block (one user, one window).
- After 3 failed restarts: full-screen error with "Restart", "Open logs folder", "Copy diagnostics" (versions, last 200 log lines, no content). Notes still visible and copyable from the journal.
- Recording indicator while the core is down: "state unknown" in `warning` tone, never a fake "recording" or "stopped".

## 9. Capture and transcription pipeline

Decided 2026-11-13 by Phuong: STT stays in the agent.

### 9.1 macOS agent (v1)
Decisions carried over from draft 1, now implemented in Swift:
- **System audio: Core Audio process tap**, global tap of all processes except our own, wrapped in a private aggregate device (`CATapDescription`, `AudioHardwareCreateProcessTap`), **macOS 14.2 minimum**. Permission "System audio recording only" (`NSAudioCaptureUsageDescription`).
- **ScreenCaptureKit: fallback only, off by default** [decision M1]. The brief lists it; draft 1 showed why it is a poor primary path: "Screen and system audio recording" permission is alarming for a notepad, and macOS Sequoia re-prompts monthly. It stays in the agent as a fallback if a tap cannot be created on a given configuration, behind a setting, and it is the only way to support macOS 13 if Phuong ever wants it.

| | Core Audio process tap | ScreenCaptureKit |
|---|---|---|
| macOS minimum | 14.2 | 13 (audio) |
| Permission | System audio only | Screen + system audio |
| Re-prompt | No | Sequoia: "allow for one month" prompt |
| On denial | Tap created but returns zeros | Error -3801 |

- **Microphone**: `AVAudioEngine` input node; spike on voice-processing mode (native echo cancellation) [decision M1]; `NSMicrophoneUsageDescription` + `com.apple.security.device.audio-input`.
- Resampling to 16 kHz mono with `AVAudioConverter`; real-time callbacks do no allocation and no I/O (lock-free ring buffer per stream, a writer thread for spool + pipe).
- `kAudioHardwarePropertyDefaultOutputDevice` listener: rebuild tap and mic on device change without stopping the session -> `device.changed`.
- **Silent tap detection**: exact zeros for 5 s while another process reports output (`kAudioProcessPropertyIsRunningOutput`) -> `permission.denied{detection:"silent_stream"}`.
- **STT**: whisper.cpp (Metal) linked through its official XCFramework or a SwiftPM C target [decision M1], Silero VAD bundled with whisper.cpp (`ggml-silero-v6.2.0.bin`, ~0.9 MB). One whisper context per agent; mic and system regions share a FIFO queue. Capture and STT run on separate threads so slow STT never stalls capture (backlog reported in `heartbeat`).
- Calendar: EventKit full access (`NSCalendarsFullAccessUsageDescription`; write-only access cannot read). Secrets: Keychain generic passwords, service `app.interline`.
- Degradation matrix (system/mic denied) unchanged from draft 1: mic only, system only, or notes + file import.
- Same agent, WhisperKit rejected for v1: it would make transcripts differ by OS (different model formats and decoding) while the core caches one set of ggml models for every OS.

### 9.2 Core side (portable)
```
audio.chunk (mic, system) --> chunk files + ack            (durability)
vad/stt events ------------> transcript_segments            (dedup on seg)
                              |
                              v
                       echo dedup (mic vs system, +/-2 s, > 70 % overlap -> suppressed=1)
                              |
note blocks (anchor_ms) ----> alignment: block window = [anchor - 45 s, next anchor)
                              |
                       LLM "enhance" pass (section 10) -> AI blocks + citations + actions
                              |
                       FTS5 indexing (triggers)
```
- `anchor_ms` comes from the UI (time the user started typing the block), converted to the session timeline by the core (`t0_wall_ms`). 45 s look-back [to calibrate at M2 on real data].
- Echo dedup stays a text heuristic in the core (portable, testable on Linux). If the AEC spike succeeds, the agent advertises `capture.aec:true` and the core lowers dedup aggressiveness.
- **Live suppression**: when the dedup flags a mic final, the core publishes `transcript.suppressed` `{meetingId, stream: "mic", seg}` after the `stt.final` of that segment, in both delivery orders (a system final that mirrors an already stored mic final announces that earlier `seg`). The live panel removes the row of that `stream:seg` key and remembers it, so a later partial or final on the same key does not bring it back. The row itself stays in `transcript_segments` with `suppressed = 1`, out of the public transcript.
- Model management in the core: catalog embedded in the jar, download from Hugging Face with HTTP Range resume, SHA-256 check, stored in `<data>/models/`, path + hash passed in `session.start`. Sizes: `small` 466 MiB, `large-v3-turbo-q5_0` 547 MiB, `large-v3-turbo-q8_0` 874 MiB, VAD 0.9 MiB. Default chosen by RAM: `small` on 8 GB, `large-v3-turbo-q5_0` from 16 GB.
- Audio retention and compression: the core stores 16 kHz PCM (~115 MB per hour per stream). Post-session compression options [decision M1]: (a) FLAC by a pure-Java encoder [library and license to verify], (b) agent-side Opus via an optional `audio.encode` capability. Default retention 7 days (SPEC).

## 10. LLM pass: preserving the user's structure

Unchanged in substance from draft 1, now in `core-ai` with Spring AI:
- **Principle**: the AI never writes instead of the user, it writes under the user.
- **Input**: ordered user blocks `{id, kind, text, anchor_ms}`; transcript as `[S123 12:04 Them] text`; metadata (title, participants, template, notes language).
- **Output**: strict JSON (`blocks[].additions[{text, sources}]`, `other_points`, `action_items[{text, owner, due, sources}]`, `title_suggestion`), requested with the provider's JSON-schema mode when supported (`supports_json_schema`), through Spring AI structured output.
- **Validation in `core-domain`** (pure Java, provider-agnostic): known `user_block_id`, existing sources, valid JSON; additions without a valid source are rejected; one retry with the error, then a visible failure with notes untouched.
- **Windowed mode** for small-context local models (< 16 k tokens); full transcript otherwise (1 h is about 12 to 15 k tokens).
- **Visible diff, accept/edit/delete per addition, undo all** through the `raw_user` snapshot; re-enhance never touches user blocks or accepted/edited additions.
- **Prompts** are files in `services/core/core-ai/src/main/resources/prompts/`, versioned (`prompt_version` in `ai_runs`), with 5 anonymised golden meetings replayed on every prompt change.
- Providers: OpenAI-compatible (covers OpenRouter, Groq, LM Studio, Mistral...), Anthropic, Ollama. Keys fetched per call from `SecretStore`, never cached on disk, never logged.
- Cost per 1 h meeting (draft 1 figures, prices noted 2026-11-13, to re-check): ~0.004 USD to ~0.08 USD depending on model; Ollama free.

## 11. Diarisation
- v1: Me / Them exact and free, from the two separate streams.
- Multi-speaker on the "Them" side: v2, experimental, post-processing. With the hybrid stack it belongs in the agent (native ONNX runtime, e.g. sherpa-onnx [to verify]) behind an optional `diarize.file` capability, so the core stays pure Java.
- No persistent voiceprint without explicit consent (biometric data under GDPR art. 9).

## 12. Calendar
- macOS v1: EventKit through the agent (`calendar.query`, `calendar.changed`); covers Google, Exchange and iCloud accounts added to macOS Internet Accounts. No OAuth in v1 (an OAuth client secret cannot stay secret in an open source app; app verification cost).
- Portable fallback in the core: **ICS subscription URL** (`IcsCalendarSource`, pure Java), usable on every OS, and the primary Linux path together with CalDAV later.
- Sync window -1 day / +14 days, refresh on start, every 5 min, and on `calendar.changed`. Minimal copy in `calendar_events`.
- Without a calendar: manual meetings, the brief matches on title and participants. The app is fully usable.

## 13. Data model (SQLite, Flyway `V1__init.sql`)

Same model as draft 1, owned by `core-persistence`. UUID v7 text ids, epoch-ms integers, WAL. Changes from draft 1 are marked `-- d2`.

**V1 is the M0 subset.** `V1__init.sql` creates only `meetings` (without `calendar_event_id` and `template_id`), `capture_runs`, `speakers` (without `person_id`), `transcript_segments`, `timeline_markers` and `settings`. Every other table below, the FTS indexes and the columns that reference them arrive in V2+ migrations.

```sql
CREATE TABLE meetings (
  id TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL CHECK (status IN ('draft','recording','degraded','processing','done','failed')), -- d2: degraded
  calendar_event_id TEXT REFERENCES calendar_events(id) ON DELETE SET NULL,
  template_id TEXT REFERENCES templates(id),
  language TEXT, started_at INTEGER, ended_at INTEGER,
  t0_wall_ms INTEGER,                         -- d2: session timeline origin
  audio_dir TEXT, audio_purge_at INTEGER,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, deleted_at INTEGER
);
CREATE TABLE capture_runs (                   -- d2: one row per agent run inside a meeting
  id TEXT PRIMARY KEY,
  meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  agent_name TEXT NOT NULL, agent_version TEXT NOT NULL, protocol INTEGER NOT NULL,
  started_at INTEGER NOT NULL, ended_at INTEGER,
  end_reason TEXT CHECK (end_reason IN ('stopped','agent_crash','core_crash','app_crash','paused')),
  last_seq_mic INTEGER, last_seq_system INTEGER,
  stt_done_mic INTEGER, stt_done_system INTEGER   -- watermarks in samples
);
CREATE TABLE people (id TEXT PRIMARY KEY, display_name TEXT NOT NULL, email TEXT UNIQUE, created_at INTEGER NOT NULL);
CREATE TABLE meeting_participants (
  meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  person_id TEXT NOT NULL REFERENCES people(id) ON DELETE CASCADE,
  PRIMARY KEY (meeting_id, person_id));
CREATE TABLE speakers (
  id TEXT PRIMARY KEY, meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  source TEXT NOT NULL CHECK (source IN ('mic','system','import')),
  label TEXT NOT NULL, person_id TEXT REFERENCES people(id) ON DELETE SET NULL);
CREATE TABLE transcript_segments (
  id INTEGER PRIMARY KEY,
  meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  speaker_id TEXT REFERENCES speakers(id) ON DELETE SET NULL,
  agent_seg TEXT NOT NULL,                    -- d2: protocol 'seg' id, for at-least-once dedup
  start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,
  text TEXT NOT NULL, text_edited TEXT, avg_logprob REAL, model TEXT NOT NULL,
  suppressed INTEGER NOT NULL DEFAULT 0, recovered INTEGER NOT NULL DEFAULT 0, -- d2
  UNIQUE (meeting_id, speaker_id, agent_seg));
CREATE TABLE timeline_markers (               -- d2: discontinuities, device changes, pauses
  id INTEGER PRIMARY KEY, meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  at_ms INTEGER NOT NULL, kind TEXT NOT NULL CHECK (kind IN ('gap','device_change','pause','resume')),
  detail TEXT);
CREATE TABLE note_blocks (
  rowid_fts INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE,
  meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  parent_id TEXT, position TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('heading','paragraph','bullet','todo','quote')),
  origin TEXT NOT NULL CHECK (origin IN ('user','ai')),
  content TEXT NOT NULL, plain_text TEXT NOT NULL, anchor_ms INTEGER,
  client_rev INTEGER NOT NULL DEFAULT 0,      -- d2: idempotent journal replay
  ai_run_id TEXT REFERENCES ai_runs(id) ON DELETE SET NULL,
  ai_state TEXT CHECK (ai_state IN ('proposed','accepted','edited')),
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE block_sources (
  block_id TEXT NOT NULL REFERENCES note_blocks(id) ON DELETE CASCADE,
  segment_id INTEGER NOT NULL REFERENCES transcript_segments(id) ON DELETE CASCADE,
  PRIMARY KEY (block_id, segment_id));
CREATE TABLE note_snapshots (id TEXT PRIMARY KEY, meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('raw_user','enhanced','manual')), doc TEXT NOT NULL, created_at INTEGER NOT NULL);
CREATE TABLE ai_runs (id TEXT PRIMARY KEY, meeting_id TEXT REFERENCES meetings(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('enhance','brief','followup_email','title')),
  provider_id TEXT REFERENCES providers(id) ON DELETE SET NULL, model TEXT NOT NULL, prompt_version TEXT NOT NULL,
  input_tokens INTEGER, output_tokens INTEGER,
  status TEXT NOT NULL CHECK (status IN ('running','ok','invalid_output','error','cancelled')),
  error TEXT, started_at INTEGER NOT NULL, finished_at INTEGER);
CREATE TABLE action_items (id TEXT PRIMARY KEY, meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  text TEXT NOT NULL, owner_label TEXT, owner_person_id TEXT REFERENCES people(id) ON DELETE SET NULL, due_date TEXT,
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','done','dismissed')),
  source_segment_id INTEGER REFERENCES transcript_segments(id) ON DELETE SET NULL,
  ai_run_id TEXT REFERENCES ai_runs(id) ON DELETE SET NULL, created_at INTEGER NOT NULL, completed_at INTEGER);
CREATE TABLE calendar_events (id TEXT PRIMARY KEY,
  source TEXT NOT NULL CHECK (source IN ('agent','ics','manual')),   -- d2: was 'eventkit'
  external_id TEXT, title TEXT NOT NULL, starts_at INTEGER NOT NULL, ends_at INTEGER NOT NULL,
  conference_url TEXT, attendees TEXT, synced_at INTEGER, UNIQUE (source, external_id));
CREATE TABLE briefs (id TEXT PRIMARY KEY, calendar_event_id TEXT REFERENCES calendar_events(id) ON DELETE CASCADE,
  meeting_id TEXT REFERENCES meetings(id) ON DELETE CASCADE, content_md TEXT NOT NULL,
  source_meeting_ids TEXT NOT NULL, ai_run_id TEXT REFERENCES ai_runs(id) ON DELETE SET NULL, created_at INTEGER NOT NULL);
CREATE TABLE providers (id TEXT PRIMARY KEY,           -- NO key here
  kind TEXT NOT NULL CHECK (kind IN ('openai_compatible','anthropic','ollama')),
  name TEXT NOT NULL, base_url TEXT NOT NULL, model TEXT NOT NULL,
  secret_account TEXT,                        -- d2: OS secret store account (was keychain_account)
  supports_json_schema INTEGER NOT NULL DEFAULT 0, is_default INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL);
CREATE TABLE templates (id TEXT PRIMARY KEY, name TEXT NOT NULL, instructions TEXT NOT NULL, builtin INTEGER NOT NULL DEFAULT 0);
CREATE TABLE settings (key TEXT PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL);
CREATE VIRTUAL TABLE blocks_fts USING fts5(plain_text, content='note_blocks', content_rowid='rowid_fts',
  tokenize='unicode61 remove_diacritics 2');
CREATE VIRTUAL TABLE segments_fts USING fts5(text, content='transcript_segments', content_rowid='id',
  tokenize='unicode61 remove_diacritics 2');
-- + AFTER INSERT/UPDATE/DELETE triggers keeping both FTS indexes in sync; indexes as in draft 1.
```
**V4 (M3) differences.** `V4__m3_calendar.sql` (Flyway location `db/migration-m3`) creates `calendar_events`, `people`, `meeting_participants` and adds `meetings.calendar_event_id`, plus an index on `calendar_events(starts_at)`. `people.email` is stored lower-cased. `calendar_events.attendees` is JSON text `[{"name":...,"email":...}]`; attendees without an email live only there. Sync keeps `source = 'ics'` rows only; the subscription URL is not in the database (ADR-007).

**V5 (M3 consent).** `V5__m3_consent.sql`, in the same Flyway location, adds nullable `meetings.consent_ack_at` (epoch milliseconds) and seeds `settings['consent.reminder_enabled']` as `true` without replacing an existing preference. Acknowledgement is idempotent and does not assert participant consent. The UI reads `GET /api/v1/settings/consent`, optionally saves `{reminder_enabled: false}` through `PUT`, and calls `POST /api/v1/meetings/{id}/consent-ack` before recording. Meeting responses expose `consentAckAt`, including null. Recording accepts an optional `meetingId`, opening a draft or appending a capture run using the existing resume protocol; omitting it still creates a new meeting. The core never gates capture on acknowledgement. A code rollback must retain the V5 migration resource and additive column and setting so Flyway validation can still succeed; no user data is removed. Deploy the migrated core before the new UI, which requires the consent endpoints.

FTS5 is verified as compiled into the SQLite shipped by `sqlite-jdbc` (`MigrationTest` asserts it at M0). Search: FTS5 in v1, embeddings in v2 (`sqlite-vec` is a native extension: loading it through `sqlite-jdbc` per OS must be validated before v2).

## 14. Packaging and distribution

### 14.1 Build artefacts
| Artefact | Built on | Per OS? |
|---|---|---|
| `interline-core.jar` (Spring Boot executable jar) | Linux runner, once per release | **No**: the same file (SHA-256 recorded) goes into every installer |
| Java runtime (jlink) | each OS runner | Yes |
| CDS archive `interline-core.jsa` | each OS runner (training run) | Yes |
| Capture agent | each OS runner | Yes |
| Tauri shell + UI bundle | each OS runner | Yes (shell code identical) |

### 14.2 jlink runtime
- Start set of modules, to be confirmed by `jdeps --print-module-deps --ignore-missing-deps` on the final jar [to verify]: `java.base, java.sql, java.naming, java.desktop (java.beans, required by Spring), java.management, java.logging, java.xml, java.net.http, jdk.unsupported, jdk.crypto.ec (TLS to LLM providers), jdk.zipfs`.
- Flags: `--strip-debug --no-header-files --no-man-pages --compress=zip-6` [to verify flag syntax on JDK 21]. Expected size 45 to 60 MB [estimate].
- **jpackage is not used to build installers**: Tauri's bundler already produces DMG, MSI and AppImage/deb, and a jpackage `.app` nested inside the Tauri `.app` doubles the signing surface. jpackage stays an option only for a native-named launcher on Windows (`interline-core.exe` instead of `java.exe` in Task Manager) [decision M4]. Default launcher everywhere: `runtime/bin/java`.
- JVM options: `-XX:SharedArchiveFile=interline-core.jsa -Xss512k -XX:+UseSerialGC -Xmx256m -Djava.awt.headless=true -Dapple.awt.UIElement=true -Dorg.sqlite.lib.path=<res>/core/native` (the last two: no Dock icon, and load the separately signed SQLite library on macOS).
- Startup: Spring Boot CDS training run (`-XX:ArchiveClassesAtExit` with `spring.context.exit=onRefresh`) [to verify for Boot 4.1], lazy initialisation for the LLM and MCP beans, no classpath scanning outside `app.interline`. Project Leyden AOT cache would help more but needs Java 24+ (ADR-001 exit/upgrade conditions).

### 14.3 Bundle layout (macOS)
```
Interline.app/Contents/
  MacOS/interline                  Tauri shell (Rust)
  MacOS/interline-capture          Swift agent (Tauri externalBin, per-target suffix resolved by Tauri)
  Resources/core/runtime/       jlink runtime (bin/java, lib/*.dylib, lib/jspawnhelper)
  Resources/core/interline-core.jar
  Resources/core/interline-core.jsa
  Resources/core/native/libsqlitejdbc.dylib   extracted from the jar, signed
  Resources/ui/                 built React app (Tauri frontendDist)
  Info.plist                    NSMicrophoneUsageDescription, NSAudioCaptureUsageDescription,
                                NSCalendarsFullAccessUsageDescription
```
Windows: `Program Files\Interline\interline.exe`, `interline-capture.exe`, `core\runtime\...`, same jar. Linux AppImage: same tree under `usr/lib/interline/`.

### 14.4 Start order and clean shutdown (no zombie JVM)
1. Shell starts, shows the window (UI skeleton), generates the token.
2. Shell spawns the core (stdin/stdout piped; on macOS and Linux in a new process group; on Windows inside a Job Object with `KILL_ON_JOB_CLOSE` [to verify crate, e.g. `command-group`]). Writes the token.
3. Core: Flyway migrate, bind 127.0.0.1:0, spawn agent, then print READY without waiting for the agent (capture state is reported later over SSE; a missing agent never blocks the notes).
4. Agent: `hello` -> `hello.ack`, `permission.status`, idle heartbeats.
5. UI gets endpoint + token, opens SSE.

Shutdown (quit from menu, tray, Cmd+Q, OS logout):
1. If recording, UI asks "Stop and save / Keep recording" (DESIGN-SYSTEM 7.8). Then shell writes `{"cmd":"shutdown"}`.
2. Core: `session.stop` (drain STT, max 3 s), `shutdown` to agent, wait 2 s, then force-kill the agent; WAL checkpoint; close DB; exit 0. Total budget 6 s.
3. Shell waits 8 s, then kills the core's process group / job. Then exits.

Defence in depth against orphans: (a) lifeline on stdin EOF in both core and agent, which works even if the shell is SIGKILLed; (b) process group / Job Object kill; (c) `run/pids.json` written by the core (pid + start time of core and agent); at startup the shell and the core kill a leftover process only if pid **and** start time **and** executable path match. A CI smoke test asserts that no `java` or `interline-capture` process survives quit and `kill -9` of the shell.

### 14.5 Signing and distribution per OS
**macOS (v1)**, no Apple Developer Program: rules, options and exact steps in ADR-004.
- Project **self-signed** code signing identity ("Interline Release Signing", private key in CI secrets + offline vault), explicit designated requirement pinned to that certificate, **every Mach-O signed inside-out**: jlink runtime binaries and dylibs (including `jspawnhelper`), `libsqlitejdbc.dylib`, the Swift agent, the shell. No notarization, no stapling.
- Entitlements: JVM `com.apple.security.cs.allow-jit`, `com.apple.security.cs.allow-unsigned-executable-memory` [to verify the minimal set for JDK 21]; agent `com.apple.security.device.audio-input`. Hardened runtime only if library validation works without a Team ID, else off or with `disable-library-validation` on the JVM [decision M4]. No App Sandbox (process taps outside the sandbox; no Mac App Store, same as draft 1).
- Native libraries inside jars: the jar stays byte-identical across OSes; the macOS dylib is extracted, signed with the project identity and loaded via `org.sqlite.lib.path`, so all code runs under one identity.
- TCC permissions and Keychain access are tied to the signature: an ad hoc signed build re-prompts after every update; the stable self-signed identity is expected to avoid it [to verify, M1 spike in ADR-004].
- Install: `install.sh` from the GitHub release (primary), DMG with "Open Anyway" (fallback), own Homebrew tap if retained, source build with a local identity.
- Updates: `tauri-plugin-updater` (minisign, independent of Apple), signed `latest.json` on GitHub Releases; updater key in CI secrets + offline vault.

**Windows (M5)**: MSI via Tauri's WiX target (or NSIS) [decision M5]; Authenticode signing (OV/EV certificate or Azure Trusted Signing [to verify availability and price]); WebView2 bootstrapper; no microphone prompt beyond Windows privacy settings; agent signed too (SmartScreen reputation).
**Linux (M5)**: AppImage + `.deb` (Tauri targets; `.deb` depends on `libwebkit2gtk-4.1` and PipeWire/PulseAudio client libs [to verify]); checksums + minisign or GPG signature; Flatpak later (audio through portals needs investigation).

Structure from M0, even if empty: `release.yml` has `macos`, `windows`, `linux` jobs; only `macos` is enabled until M5.

### 14.6 Budgets (checked by CI or by hand at each milestone exit)
| Metric | Budget | Notes |
|---|---|---|
| Installed app size (macOS) | <= 200 MB, target 150 MB | runtime 45-60 + jar 40-60 + shell/UI 10-15 + agent with whisper.cpp 5-15 [estimates] |
| DMG download | <= 100 MB | compressed |
| Whisper models | downloaded separately, never in the installer | `small` 466 MiB, `large-v3-turbo-q5_0` 547 MiB, VAD 0.9 MiB |
| Window visible after launch | <= 0.5 s | shell + static UI |
| Core READY, cold, M1 8 GB | <= 2.0 s, target 1.5 s | with CDS archive |
| Agent `hello` after spawn | <= 0.3 s | model not loaded yet |
| Idle RAM, all processes | <= 350 MB | core <= 200 MB RSS, shell + webview <= 120 MB, agent <= 30 MB |
| RAM while recording | idle + model | `large-v3-turbo-q5_0`: about +1 GB in the agent [to verify on M1] |
| Live transcript latency | median < 5 s | M1 8 GB with `small` |
| Clean quit | <= 8 s, zero leftover process | smoke test |

## 15. Target repository tree

```
interline/
  apps/desktop/
    src/                       React: routes/, features/{meetings,editor,transcript,brief,settings}, ui/, api/ (generated types)
    src-tauri/
      src/main.rs              window, tray, 3 commands
      src/supervisor.rs        spawn/stop core, lifeline, process group / job
      capabilities/            Tauri v2 permissions (deny-by-default)
      Info.plist, Entitlements.plist, tauri.conf.json
    package.json
  services/core/               Maven multi-module (+ Maven Wrapper)
    pom.xml                    parent, versions, enforcer rules
    core-domain/  core-persistence/  core-ai/  adapter-agent-protocol/  mock-agent/  app/
  agents/
    macos-capture/             Swift Package: InterlineCapture (executable), AgentProtocol, CaptureMac,
                               SpeechWhisper, CalendarMac, SecretsMac; Tests/
    windows-capture/README.md  contract + planned APIs, no code
    linux-capture/README.md    contract + planned APIs, no code
  protocol/agent/v1/           schema.json, golden/m0.ndjson, golden/m1/m1.ndjson + *.bin, CHANGELOG.md
  fixtures/
    audio/                     short WAVs (stereo mic|system) + scripted transcripts, CC0 or self-recorded
    meetings/                  5 anonymised golden meetings (enhancement evaluation)
    calendar/                  scripted calendars for the MockAgent
  tools/                       jlink.sh, cds-train.sh, sign-macos.sh, smoke-quit.sh
  docs/ (+ docs/adr/)
  .github/workflows/           ci.yml, release.yml
  CLAUDE.md, AGENTS.md         main branch, build/test/lint commands for the squad
  LICENSE                      MIT
```
Build tool: **Maven** (declarative, the Spring Boot default, easiest for contributors and coding agents). Gradle would be fine; switching later is cheap because modules are simple.

## 16. Test strategy

| Layer | Tooling | What it proves | Runner |
|---|---|---|---|
| `core-domain` | JUnit Jupiter (JUnit 5 API) + AssertJ | Alignment, echo dedup, enrichment validation, session state machine | Linux |
| `core-persistence` | JUnit + SQLite **temporary file** per test (`@TempDir`), real Flyway | Migrations from every previous version, FTS5 present and accent-insensitive, cascade delete leaves nothing | Linux |
| `core-ai` | Stubbed `ChatModel` / WireMock for OpenAI-compatible HTTP | Structured output parsing, retry, rejection of unsourced additions | Linux |
| `adapter-agent-protocol` | JUnit + jqwik property tests on the codec | Framing round-trips, size limits, unknown fields ignored | Linux |
| **Contract tests** | JUnit driving the **MockAgent process** | Handshake and version reject; 30 min of audio at 50x with no missing `seq`; backpressure with a slow consumer; crash at 45 s -> resume with no seq hole and no transcript gap beyond the marker; hang -> kill + restart; denied permission; device change; corrupt frame | Linux |
| End-to-end pipeline | `@SpringBootTest` + MockAgent + stub LLM | WAV in -> meeting `done` with transcript, enriched notes, citations, actions; user blocks byte-identical | Linux |
| API security | `@SpringBootTest` | No token -> 401; wrong token -> 401; foreign `Origin` -> 403; bad `Host` -> 403; bound to 127.0.0.1 only | Linux |
| Architecture | ArchUnit | No OS leak (ADR-003 rules), hexagonal boundaries | Linux |
| UI | Vitest + Testing Library; Playwright in Chromium against core + MockAgent (no Tauri) | Editor, journal replay after core kill, recording states | Linux |
| Swift agent | Swift Testing / XCTest | Resampler, spool writer, silent-tap detector | macOS |
| **Agent conformance kit** | The Java contract tests run against the **real agent** in `--test-input=<wav>` mode | The macOS agent obeys the same contract as the MockAgent | macOS |
| Packaging smoke | `tools/smoke-quit.sh` | Bundle launches, READY < budget, quit leaves no process | macOS |
| Manual | checklist per milestone | Real Zoom/Meet call, TCC prompts, ADR-004 install paths, permissions kept after an update | Mac |

CI matrix (GitHub Actions):
| Job | Runner | Trigger | Steps |
|---|---|---|---|
| `core` | `ubuntu-24.04` | every push/PR | `./mvnw -B verify` (all Linux rows above), publish `interline-core.jar` + SHA-256 |
| `ui` | `ubuntu-24.04` | every push/PR | `pnpm lint`, `typecheck`, `vitest`, Playwright with core + MockAgent, OpenAPI drift check |
| `agent-macos` | `macos-15` arm64 [to verify label] | push/PR touching `agents/macos-capture` or `protocol/` | `swift build`, `swift test`, conformance kit with the jar from `core` |
| `bundle-macos` | `macos-15` | push to main, tags | jlink, CDS training, `tauri build` unsigned, smoke quit test; on tags: sign with the project identity (ADR-004), DMG + updater tarball, `SHA256SUMS`, `install.sh`, release |
| `core-windows` | `windows-latest` | nightly from M2, required from M5 | `./mvnw -B verify` (proves path and process handling on Windows early, cheap) |
| `bundle-windows`, `bundle-linux` | Windows / Ubuntu | from M5 | same as macOS with MSI / AppImage + deb |

## 17. Versions

Verified in the build files at M0, unless otherwise noted. Checked on the web on 2026-11-13 for versions not yet exercised.

| Component | Version line | Status |
|---|---|---|
| Java | 21 LTS (Temurin) | Imposed. CI and `BuildTest` enforce 21 |
| Spring Boot | 4.1.2 | verified, `spring-boot-dependencies` BOM imported in the parent pom (latest GA on Maven Central) |
| Spring AI | 2.0.2 | verified, BOM imported in the parent pom; no provider in M0 |
| MCP Java SDK | not used in M0 | deferred to M3 |
| sqlite-jdbc (xerial) | 3.54.1.0 | verified; pinned in the parent `dependencyManagement` (Boot 4.1.2 manages 3.53.4.0); FTS5 enabled and asserted |
| Flyway | 12.4.0, managed by Spring Boot | verified with SQLite in `flyway-core` |
| JUnit | Jupiter 6.0.3, managed by Spring Boot | verified |
| jqwik | 1.10.2 | verified |
| ArchUnit | 1.5.1 | verified |
| WireMock | 3.13.1 (standalone) | verified, used in core-ai provider tests |
| springdoc-openapi | not used in M0 | out of scope |
| Maven | 3.9.x via Wrapper | verified |
| Tauri | 2.12.5 (Rust crate and `@tauri-apps/cli` 2.x) | verified |
| Tauri plugins (updater, notification, dialog) | not used in M0 | out of scope |
| whisper.cpp | v1.9.5, official `whisper-b5454-xcframework` (Metal embedded) | verified at M1 on Apple M4: links and runs with Command Line Tools only; SwiftPM URL download stalls on this network, see agents/macos-capture/README.md |
| Silero VAD (ggml) | `ggml-silero-v6.2.0.bin` (885 098 B) | verified at M1: used through whisper.cpp VAD, catalog sha256 checked against Hugging Face |
| Swift toolchain / Xcode | Swift 6.4.2, swift-tools 6.0, Command Line Tools only | verified at M1: `swift build` works, `swift test` does not (no XCTest, no swift-testing), so unit tests live in `CaptureSelfTest` |
| React / TypeScript / Vite | 19 / 5.7.3 / 6.4.6 | verified |
| TipTap / Tailwind CSS / shadcn/ui | TipTap 3.x, unique-id 3.34.0 | verified on npm; UI styles in `apps/desktop/src/styles.css` |

## 18. ADRs

| ADR | Subject | Status |
|---|---|---|
| ADR-001 | Hybrid stack B (Tauri shell, Spring Boot core, native agents) | Accepted |
| ADR-002 | Capture agent contract (Agent Protocol v1 over stdio) | Accepted |
| ADR-003 | Platform ports, no OS leak, adding an OS | Accepted |
| ADR-004 | macOS distribution without the Apple Developer Program (self-signed identity, install script) | Proposed (Accepted after the M1 spike) |
| ADR-005 | Note blocks with time anchors and the enhancement contract | [Accepted](docs/adr/ADR-005-blocs-notes-et-contrat-enrichissement.md) |
| ADR-006 | BYOK LLM via Spring AI, keys in the OS secret store via the agent | [Accepted](docs/adr/ADR-006-byok-llm-via-agent-secret-store.md) |
| ADR-007 | Calendar: agent (EventKit) + ICS in the core, no OAuth in v1 | To write (M3) |
| ADR-008 | Audio retention, consent, zero implicit network | To write (M4) |
| ADR-007 | Calendar through an ICS subscription URL, parsed in the core | [Accepted](docs/adr/ADR-007-calendar-ics.md) |
| ADR-009 | macOS capture: process tap primary, ScreenCaptureKit fallback, AEC, whisper packaging, test input, compression | [Draft](docs/adr/ADR-009-macos-capture.md) (spikes pending the M1 sitting) |
| ADR-010 | License MIT | [Accepted](docs/adr/ADR-010-mit-license.md) |
| ADR-011 | Core owns the data (SQLite via JDBC + Flyway, UI without SQL) | [Accepted](docs/adr/ADR-011-core-owns-the-data.md) |
