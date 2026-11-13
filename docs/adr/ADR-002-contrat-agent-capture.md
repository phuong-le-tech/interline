# ADR-002 - Capture agent contract: Agent Protocol v1 over stdio

Status: Accepted, 2026-11-13.
Related: ADR-001 (hybrid stack), ADR-003 (platform ports). Normative specification: `docs/ARCHITECTURE.md` section 5. Machine-readable schema and golden transcripts: `protocol/agent/v1/`.

## Context

The core (Java, portable) needs audio, transcription, permissions, calendar and secrets from a native agent that differs per OS. The contract between them is the single seam that makes portability real: if it is stable and OS-neutral, adding Windows or Linux means writing one agent and passing one conformance kit.

Requirements:
1. Same behaviour on macOS, Windows, Linux, and in the JVM, with no extra dependency.
2. Carries ~64 KiB/s of PCM (two 16 kHz mono streams) plus low-rate control and STT events, with backpressure that never drops audio and never blocks the real-time callback.
3. Detects death of either side immediately; no orphan agent.
4. No authentication problem to solve; no network surface.
5. Easy to record, replay and fake (MockAgent, CI on Linux without audio).
6. Evolvable without lockstep releases of core and agents.

## Decision

- **Transport**: the agent's stdin/stdout, the core is the parent. stderr carries logs only.
- **Framing**: `u32 BE length | u8 kind | payload`. Kind `0x01` = JSON control message (envelope `v`, `t`, `id`/`re`, `ts`, `body`), max 1 MiB. Kind `0x02` = audio frame: 24-byte header (`stream`, `flags`, `seq`, `first_sample`, `host_time_ns`) + PCM s16le 16 kHz mono, 100 ms per frame.
- **Messages**: `hello`/`hello.ack`/`hello.reject`, `heartbeat`, `session.start`/`started`/`pause`/`resume`/`stop`/`stopped`, `audio.chunk`, `audio.ack`, `level`, `vad.segment`, `stt.partial`, `stt.final`, `device.changed`, `permission.status`/`request`/`denied`, `calendar.query`/`events`/`changed`, `secret.get`/`put`/`delete`, `transcribe.file`, `error`, `shutdown`/`shutdown.ok`.
- **Backpressure**: sliding window per stream; `audio.ack {seq, window}` means "durable up to seq". The agent spools every frame to disk before sending, so a closed window delays frames but never drops them.
- **Recovery**: per-stream `seq` and a shared sample timeline survive agent restarts; the core resumes a session with `acked`, `next_seq` and `stt_done_until_sample`; the new agent replays its predecessor's spool.
- **Lifeline**: an agent exits within 2 s of stdin EOF; 3 missed heartbeats (6 s) = the core kills and restarts it.
- **Fallback binding**: the same frames over a Unix domain socket (agent launched by the shell) if macOS TCC attribution requires it. Only the transport class changes.

## Alternatives rejected

| Alternative | Why rejected |
|---|---|
| Loopback TCP or WebSocket | Needs a port and authentication; Windows firewall prompt risk; death detection needs heartbeats only; no gain over stdio for a single client |
| gRPC (HTTP/2 + protobuf) | Heavy dependency in Swift and in every future agent, code generation in three languages, harder to inspect by hand; streaming and deadlines are nice but our needs are covered by a 24-byte header and JSON |
| Named pipes / Unix sockets as the primary transport | Different path semantics per OS, permissions to manage, a token to design; kept only as a fallback binding |
| XPC / Mach services | macOS only: violates ADR-003 |
| In-process JNI / FFM bindings | A native crash kills the core and its data; per-OS native libraries in the jar break "one jar everywhere"; FFM is preview only in Java 21 |
| Shared-memory ring buffer for audio | Fast but OS-specific, complex to make crash-safe; 64 KiB/s does not need it |
| JSON + base64 for audio | 33 % overhead and GC churn on 20 messages/s; binary frames cost one small parser |
| Protobuf or CBOR for control messages | Smaller, but control traffic is tiny and JSON is readable in logs and golden files; can be adopted in a future major version |
| Agent pushes files only (no streaming) | No live transcript, no VU meters, no core-side durability; the brief requires streaming |

## Evolution procedure

Versioning model: `protocol` major integer in every envelope (`"v":1`); optional features discovered through `hello.capabilities`, never through version numbers.

**Compatible (minor) change**, no version bump, allowed at any time:
- new optional field in a body; new event type (receivers ignore unknown events); new error code; new capability flag; new optional request that the core sends only if the agent advertised the matching capability.
- Procedure: update `protocol/agent/v1/schema.json`, add a golden transcript exercising the change, add a line to `protocol/agent/v1/CHANGELOG.md`, update ARCHITECTURE section 5, update the MockAgent. The conformance kit must stay green for every existing agent.

**Breaking (major) change**: anything that changes the meaning of an existing field, removes a field or a message, changes framing, audio format or the ack semantics.
- Procedure: (1) write an ADR amending this one; (2) create `protocol/agent/v2/`; (3) the core supports **both** majors (`hello` negotiation picks the highest common one) for at least one release; (4) ship the agents speaking v2; (5) drop v1 in the core one release later, noted in the release notes.
- Rule: never more than one major in flight; never a breaking change inside a minor release of the app.

**Ownership and gates**:
- Any PR touching `protocol/` or `adapter-agent-protocol` requires the conformance kit job on macOS (`agent-macos`) and the Linux contract tests to pass.
- Golden transcripts are append-only; a golden file is never edited to make a test pass.
- The MockAgent is updated in the same PR as any protocol change (it is the reference client for CI).

## Consequences

- Positive: OS-neutral seam, trivial to fake, crash-isolated, debuggable (`tools/` can dump a session as NDJSON + raw PCM), no network surface.
- Negative: a hand-written codec in Java and in Swift (and later in each agent's language), kept honest by the shared golden files; a binary audio header to keep consistent across languages; agent and core must agree on spool semantics.
