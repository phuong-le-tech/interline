# ADR-001 - Hybrid stack "B": dumb Tauri shell, Spring Boot core, native capture agents

Status: Accepted, 2026-11-13. Decided by Phuong. Supersedes the pure Tauri + Rust core of ARCHITECTURE draft 1.
Related: ADR-002 (agent contract), ADR-003 (platform ports), `docs/ARCHITECTURE.md`.

## Context

Interline is an open source (MIT), local-first meeting notepad: system audio capture without a bot, local transcription, AI-enhanced notes, pre-meeting brief. v1 ships on macOS only, but Windows and Linux must be reachable without a rewrite.

Forces:
1. **Portability is structural.** Audio capture, permissions, calendar and secret storage are different on every OS. Whatever we choose must confine that difference to one replaceable piece.
2. **The domain is the product.** The value is in the "after the meeting" logic: preserving user notes, citations, validation, brief. That code must be easy to test on any CI runner, including one without an audio device.
3. **Maintainer fit.** Phuong is most productive in Java/Spring; the core is where most hours go. Rust is limited to what Tauri requires.
4. **Native capture is unavoidable.** Core Audio process taps, TCC, EventKit are best reached from Swift; WASAPI from C++/Rust/C#; PipeWire from C/Rust. No single language does all three comfortably.
5. **UI already designed in React** (21st components, HTML mock-ups).

## Options

**A. Pure Tauri (draft 1).** Rust core in the Tauri process: capture via `cidre`/`objc2`, `rusqlite`, `reqwest`, whisper in a Rust sidecar. React UI.
**B. Hybrid.** Tauri 2 shell with no logic; Spring Boot core on Java 21 (domain, SQLite via JDBC + Flyway, pipeline, LLM providers, local API, MCP); one native capture agent per OS behind a stable protocol (Swift on macOS); React UI.
**C. All JVM.** Desktop UI in JavaFX (or Compose Multiplatform / a JCEF webview), core in Java, capture through JNI/FFM bindings to native libraries, whisper through JNI.

## Comparison

| Criterion | A. Pure Tauri | B. Hybrid | C. All JVM |
|---|---|---|---|
| Languages | Rust, TS | Rust (tiny), Java, Swift (+ later C++/Rust per OS), TS | Java (+ C/C++ for bindings) |
| OS isolation | Traits + `cfg` inside one process | Separate process per OS behind a protocol: strongest | JNI per OS inside the JVM: crashes kill the app |
| Testing the domain on Linux CI | Good (cargo test), capture behind traits | Very good: MockAgent replays a WAV through the real protocol | Good, native parts hard to fake |
| Native capture quality on macOS | Good (Rust bindings to Core Audio, less documented) | Best (Swift, first-class Apple APIs) | Weak (JNI glue, TCC attribution to a JVM) |
| Reuse of React design | Yes | Yes | No (JavaFX/Compose rewrite) or awkward (JCEF) |
| Maintainer productivity on the core | Lower (Rust) | Higher (Java/Spring) | Higher |
| Ecosystem for LLM, MCP, migrations | Fragmented crates | Spring AI, MCP Java SDK, Flyway, mature | Same as B |
| Installed size | ~25-35 MB | ~150-200 MB | ~120-180 MB |
| Cold start | < 0.5 s | ~1.5 s (CDS) | ~1.5-2.5 s |
| Idle RAM | ~100-150 MB | ~250-350 MB | ~250-400 MB |
| Packaging and signing | Simple (Tauri) | Heavier: JRE + jar + agent, all signed | Medium (jpackage) |
| Crash isolation | Sidecar only for ASR | Agent crash never touches data; core crash never touches notes in the UI journal | One process, one crash |
| Fit with competitors' code (Meetily, Anarlog: Tauri + Rust) | High (can borrow) | Medium (borrow ideas, not code) | Low |

## Decision

**Option B.**
- Shell: Tauri 2, window + webview + tray + supervision, 200 to 400 lines of Rust, zero business logic, zero audio, zero SQL.
- Core: Spring Boot 4.1 on Java 21, Maven multi-module, SQLite (`sqlite-jdbc` + Flyway), Spring AI 2.0, local HTTP API on loopback, MCP server slot.
- Capture agent: native per OS behind Agent Protocol v1 (ADR-002). macOS v1 in Swift. Windows and Linux after v1.
- UI: React + TypeScript in the Tauri webview.

## Consequences we accept

1. **Three languages in v1** (Java, Swift, TypeScript) plus a small amount of Rust. Contributors need at least one of them; the agent and the shell are small and change rarely. Each later OS adds one more native codebase.
2. **Size ~150-200 MB installed** (jlink runtime 45-60 MB, Spring Boot jar 40-60 MB) against ~30 MB for option A. Models stay a separate download in all options.
3. **Cold start ~1.5 s for the core.** Mitigated: the window appears in < 0.5 s, the UI renders a skeleton, CDS archive, lazy beans. A meeting notepad is opened once a day, not per action.
4. **Idle RAM ~250-350 MB** across processes. Acceptable on 8 GB Macs; measured at every milestone.
5. **Heavier packaging**: per-OS jlink runtime, CDS archive, signing every Mach-O inside the bundle (JVM included), JVM entitlements, a native library inside a jar that notarization may flag. One more CI job per OS.
6. **Three supervised processes**: lifeline, process groups, restart policy and a "no zombie JVM" smoke test become part of the product.
7. **A protocol to maintain** (ADR-002): versioning discipline, golden transcripts, conformance kit.
8. **Less code to borrow** from Tauri + Rust competitors; we borrow designs, not crates.

What we gain: the core is one jar that runs everywhere and is fully testable on Linux; each OS port is a bounded project (one agent, `docs/PLATFORM.md`); Spring AI and the MCP Java SDK cover providers and MCP without custom plumbing; the most productive language is used where most of the code is.

## Exit conditions (when we would regret this choice)

Revisit this ADR if any of these is observed and not fixable within one milestone:
1. **Cold start of the core > 3 s** on an M1 8 GB after CDS and lazy init, or user feedback that start time hurts.
2. **Idle RAM > 500 MB** across processes, or the JVM memory makes 8 GB Macs swap during transcription.
3. **Signing/notarization of the embedded JVM** blocks a release twice (rejections we cannot fix by configuration).
4. **TCC cannot attribute permissions correctly** to an agent launched by the JVM, and the Unix socket fallback (agent launched by the shell) does not fix it.
5. **Contributors stay away** because of the three-language setup: no external PR touching the core after 6 months of public beta, while issues mention the stack.
6. **The protocol becomes the bottleneck**: more than one breaking change (major version) per release, or features that cannot be expressed without leaking OS concepts into the core.

Fallback directions: (a) collapse the core into Rust (option A) by porting the domain module, keeping the protocol and the agents; (b) move to Java 25 LTS for Leyden AOT start-up gains if 1 is the only problem; (c) GraalVM native image for the core if 1 and 2 are both problems [to verify Spring AI and sqlite-jdbc support].
