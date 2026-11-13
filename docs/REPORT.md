# REPORT - Design report, Interline

For: Phuong. By: tech lead. Date: 2026-11-13, draft 2 (replaces draft 1: AGPL, code name "Interline", pure Tauri + Rust).
Linked documents (all in `docs/`): SPEC.md, ARCHITECTURE.md, PLATFORM.md, ROADMAP.md, DESIGN-SYSTEM.md, UI-COMPONENTS.md, adr/.

## 1. Executive summary

**The project.** Interline is an open source Granola: you type short notes during a call, the app captures the computer's audio without a bot, transcribes locally with whisper.cpp, then completes your notes with an LLM you provide (API key or Ollama). Pre-meeting brief from your calendar. No server, no account, no telemetry. License **MIT**. v1 ships on **macOS**; the architecture is portable to Windows and Linux from day one.

**The stack (hybrid "B", ADR-001).** Four processes: a React + TypeScript UI in a Tauri 2 webview; a deliberately dumb Tauri shell (window, tray, process supervision, 200 to 400 lines of Rust); a Spring Boot 4 core on Java 21 (domain, SQLite via JDBC + Flyway, pipeline, LLM providers via Spring AI, local API, MCP slot), the same jar on every OS; and a native capture agent per OS (Swift on macOS: Core Audio process tap, whisper.cpp on Metal, EventKit, Keychain) speaking Agent Protocol v1 over stdio (ADR-002).

**What is feasible, and what is not.**
- Feasible in v1: system capture through a Core Audio process tap (macOS 14.2+), near-real-time local transcription on Apple Silicon (`large-v3-turbo` q5, 547 MiB, ~12x real time on M2), exact Me / Them separation thanks to the two streams, enhancement that keeps your notes word for word with citations into the transcript, calendar brief through EventKit or an ICS URL, FTS5 search.
- Not in v1: multi-speaker diarisation (feasible offline but fragile, v2 experimental), chat over history (v2), Windows and Linux builds (post-v1, M5; the architecture already allows them), Google / Outlook OAuth (not needed on Mac thanks to EventKit).
- Cost for the user: 0.004 USD to 0.08 USD per 1 h meeting depending on the cloud model; 0 USD with Ollama.

**Effort.** ~45.5 person-days for v1 (M0 to M4) + 28 to 39 pd for the Windows and Linux ports (M5). M0 (<= 5 pd) proves the architecture and its portability: the full pipeline runs on a Linux CI runner with a MockAgent replaying a WAV, without any audio device. M1 then attacks the riskiest platform piece: real macOS capture and permissions.

**The market is far from empty.** Meetily (MIT, ~33k stars) and Anarlog (ex-Hyprnote, MIT, ~10k stars) already do local-first without a bot. A fourth project only makes sense if it is clearly better on one precise point. My proposal: **fidelity to the user's notes** (strict preservation, sourced additions, reversible diff), **with no paid feature at all**, and **designed first for French-speaking Europeans** (built-in GDPR consent, tested French quality).

**My recommendation.** Do M0 (one week) before committing further. In parallel, spend 2 hours using Anarlog and Meetily on real meetings. If one of them already does 80 % of what we want, contributing there is an honest option to consider (question 1).

**Decisions taken by Phuong, and their accepted trade-offs.**
- **MIT**: anyone, including a commercial competitor, can fork Interline into a closed product or a SaaS. In exchange: no friction for contributors and integrators, the same license as the segment leaders. If a paid offer ever exists, decide on a CLA before the first external contribution.
- **Hybrid stack B**: three languages (Java, Swift, TypeScript, plus a little Rust), ~150 to 200 MB installed, ~1.5 s core cold start, heavier packaging (embedded JVM: every Mach-O signed). Gains: one portable core jar, fully testable on Linux, Java productivity where most of the code is, bounded OS ports. Exit conditions are written in ADR-001.
- **Name Interline**: collision risk accepted (section 6).
- **Pre-meeting brief in v1**: worth little while the history is empty. Kept in v1, but at M3, after the core (capture, transcription, enhancement).
- **No Apple Developer Program** (2026-11-13, firm): no notarization, so a downloaded app is blocked by Gatekeeper until the user installs through the one-command script or clicks "Open Anyway"; permission persistence across updates rests on a self-signed identity, to be proven in M1. Details and plan B: ADR-004.
- **STT in the capture agent** (2026-11-13): closed, see ARCHITECTURE section 9.
- **macOS 14.2 minimum** (2026-11-13): 13.0 to 14.1 and older are unsupported in v1; ScreenCaptureKit only as an opt-in fallback if users ask (PLATFORM.md, "macOS version support").

## 2. Open source state of the art (verified on 2026-11-13)

Stars and activity read on GitHub; "not verified" = not found in the README.

| Project | License | Stack / platforms | Capture | Local transcription | Diarisation | LLM | User notes enhanced | Weak point |
|---|---|---|---|---|---|---|---|---|
| **Anarlog** (ex-Hyprnote), github.com/fastrepl/anarlog | MIT (commercial enterprise parts) | Tauri + Rust; Mac, Win, Linux | System audio + mic, no bot | Yes | Yes (local pyannote) | BYOK, Ollama, LM Studio, paid cloud | Yes ("transcript + memo -> summary") | The team refocused on another product (char); open core |
| **Meetily**, github.com/Zackriya-Solutions/meetily | MIT | Tauri + Rust; Win, Mac, Linux | System audio + mic | Yes (Whisper, Parakeet) | Basic; named speakers in PRO | Ollama, Claude, Groq, OpenRouter, OpenAI, custom | Not verified (transcript summaries) | Key features reserved to PRO; ~33k stars |
| **Muesli**, github.com/Muesli-HQ/muesli | MIT | Native Swift; Apple Silicon Mac | Core Audio process tap | Yes (Parakeet, Whisper...) | Yes (pyannote) | OpenAI, Anthropic, OpenRouter, Ollama | Not verified | Mac only; dictation first |
| **Minutes**, github.com/silverstein/minutes | MIT | Tauri + Rust + whisper.cpp + MCP | Local recording | Yes | Yes | Built for agents (Claude Code, Cursor via MCP) | Not verified | Notes UI secondary |
| **OpenWhispr**, github.com/OpenWhispr/openwhispr | MIT | Electron; Mac, Win, Linux | Call detection, system audio | Yes | Yes | Local or BYOK | Not verified | Dictation first, meetings added later |
| **Note67**, github.com/ZapYap-com/note67 | AGPL-3.0 | Tauri + Rust; Mac | Local audio | Yes (Whisper) | Not verified | Local summaries | Not verified | One maintainer, ~230 stars, very young |
| **interline** (luqmansungkar), github.com/luqmansungkar/interline | not verified | Native Mac | Process tap + mic, separate streams | Yes or cloud | Me / Them by stream | not verified | **Yes**, explicitly "Granola-like" | Targets Bahasa Indonesia. **Same name as our product** |
| **screenpipe**, github.com/screenpipe/screenpipe | Source-available (not OSI) | Rust; Mac, Win, Linux | Continuous screen + audio | Yes | Yes | All, MCP | No | Total recording, not a notepad; non-free license |
| **Vexa**, github.com/Vexa-ai/vexa | Apache-2.0 | Python, self-hosted | **Bot** in Meet / Teams / Zoom | Yes with a GPU | Yes | Agents / MCP | No | Heavy infrastructure, an API more than an app |
| **Vibe**, github.com/thewh1teagle/vibe | MIT | Tauri + whisper.cpp | Files, mic | Yes | Not verified | Optional summaries | No | A transcriber, not a meeting app |
| **Amurex** | AGPL-3.0 | Chrome extension + Python | Browser (Meet) | Not verified | Not verified | Not verified | No | Abandoned (last push May 2025) |
| **Obsidian** plugins (Scuttlebutt, Meeting Live, meeting-scribe...) | Various | Obsidian | Mic, sometimes system | Through an external whisper server | Some (cloud) | BYOK / local | No | Fragmented, depends on an external STT server |

Proprietary reference: **Granola** (free with 30 days of history; Business 14 USD/user/month with unlimited history, integrations and MCP; summaries in its cloud, no BYOK). **MacWhisper**: meeting recording in Pro (~64 EUR one-off), local. Otter, Fireflies: cloud, mostly through a bot.

**Reading**: the "local, no bot, BYOK" niche is occupied by two solid projects and half a dozen small ones, mostly on Tauri + Rust. What remains under-served: (1) faithful enhancement of the user's own notes (only Anarlog documents it, and without verifiable citations); (2) an entirely free product with no paid tier (Meetily PRO, Anarlog cloud, screenpipe source-available); (3) French-speaking / European users, with consent built into the product.

## 3. Defensible differentiator

1. **"Your notes stay your notes."** A technical contract, not a slogan: user blocks never modified (checked by code), every addition cites transcript segments, visible diff, total undo. Measurable and demonstrable in a 10-second video. It is the feature that makes Granola valuable, and the least well copied in open source.
2. **Fully free, no open core**: no PRO tier, no project cloud. MIT does not force forks to stay open; the promise binds this project, not forks.
3. **Network transparency.** "What leaves your machine" screen + zero implicit request. A concrete argument for P2 (NDA, GDPR).
4. **Measured French quality** (WER published per model, prompts tested in French) and GDPR consent built into the flow.
5. **Portable by construction**: the same core on every OS; a port is a bounded agent project (PLATFORM.md), not a rewrite.

What is **not** a differentiator: "local-first", "no bot", "BYOK", "Tauri". Everybody already does it. "Spring Boot" is not one either: it is an internal choice that users never see.

Honestly: against Meetily and Anarlog, this project will not win on breadth. It can only win on the finish of the core (the "after the meeting" moment). If that is not the ambition, contributing to Anarlog (MIT) brings more value to users.

## 4. Risks

| # | Risk | Impact | Mitigation |
|---|---|---|---|
| R1 | Core Audio taps poorly documented; permission denial = silence, not an error; now implemented in Swift inside the agent | High: central feature | M1 opens with a day-1 spike; silent-stream detection in the agent; learn from Anarlog / Meetily / Muesli code (MIT) |
| R2 | Echo without headphones (the others appear twice) | Medium | `AVAudioEngine` voice-processing spike in M1; otherwise text dedup in the core |
| R3 | Enhancement quality varies with the model, especially locally | High: it is the promise | Validated JSON output, visible rejections, 5 golden meetings, windowed mode, recommended models |
| R4 | Performance on an M1 8 GB | Medium | `small` by default on 8 GB, benchmark in M1, CoreML later |
| R5 | Crowded market, project without traction | High for motivation | Narrow, demonstrable differentiator; decide after M0 + trying the competitors |
| R6 | Legal: a user records without consent | Medium (reputation) | Consent reminder, permanent indicator, no auto-recording, no voiceprint |
| R7 | No Apple Developer Program (ADR-004): Gatekeeper blocks the downloaded app; TCC grants (microphone, system audio) and Keychain access are tied to the signature, so an ad-hoc build re-prompts after every update; no Apple trust chain; no Team ID for library validation | High at beta | Project self-signed identity with a pinned designated requirement (keeps grants across updates [to verify, M1 spike]); one-command `install.sh` (no quarantine) as the primary path, "Open Anyway" documented for the DMG; integrity via SHA-256 + published certificate fingerprint + minisign; plan B in ADR-004 if the spike fails |
| R8 | Loss or leak of a release key: the Tauri updater key (minisign) or the self-signed code signing key | High: lost updater key = no more updates; lost signing key = one new permission prompt for every user; leaked signing key = a malicious binary could inherit users' microphone grants | Both keys in CI secrets + offline vault, written procedure; signing certificate valid 10 years; rotation announced in release notes with the new fingerprint |
| R9 | Name collision ("Interline" already used several times) | Medium | Accepted by Phuong. Mitigation: distinctive tagline, trademark check (EUIPO / USPTO), domain and GitHub org before the public beta [to verify] |
| R10 | Licenses: 21st components, models (Parakeet CC-BY), copied code | Low to medium | Dependencies and copied components must be MIT-compatible (MIT, Apache-2.0, BSD, ISC); no GPL/AGPL code copied in (BlockNote `xl-*` packages excluded); license report in CI |
| R11 | Solo project: bus factor 1 | Medium | ADRs, CLAUDE.md, pipeline tests on audio files, public roadmap |
| R12 | Cost of the hybrid stack: start time, RAM, size | Medium | CDS archive, lazy init, budgets checked at each milestone (ARCHITECTURE 14.6), ADR-001 exit conditions |
| R13 | TCC attributes permissions badly to an agent launched by the JVM (grandchild of the app) | High: capture blocked | M1 day-1 spike; fallback: agent launched by the shell over a Unix socket, same protocol |
| R14 | Signing the embedded JVM and native code inside jars (sqlite-jdbc) with a self-signed identity: library validation may reject libraries without a Team ID | Medium at M4 | Notarization no longer applies (ADR-004); extracted and signed dylib; hardened runtime off or `disable-library-validation` on the JVM [decision M4]; dev builds signed with the stable identity from M1 |
| R15 | Protocol drift between the Java and Swift codecs | Medium | Shared golden transcripts, conformance kit on macOS CI, ADR-002 evolution procedure |

## 5. Open questions

1. **Build or contribute?** After trying Anarlog and Meetily, does the "note fidelity" difference justify a new project?
2. **License**: Resolved 2026-11-13: MIT.
3. **Public name**: Resolved 2026-11-13: Interline.
4. **macOS 14.2 minimum**: Resolved 2026-11-13: 14.2 minimum, ScreenCaptureKit opt-in fallback only if users ask, earlier versions unsupported (PLATFORM.md).
5. **Apple Developer account**: Resolved 2026-11-13: none, no 99 USD/year. Distribution without notarization, ADR-004.
6. **Languages**: are FR + EN enough for v1?
7. **Default audio retention**: delete after 7 days (my proposal), keep, or delete as soon as transcribed?
8. **Automatic enhancement at the end of a meeting** on by default when an LLM is configured? (I propose yes, consistent with Granola.)
9. **Available time**: full time or evenings? It changes the roadmap from 9 weeks to 5 to 6 months.
10. **Development by the Hermes squad** (kanban cards per milestone) or by hand? If squad: `CLAUDE.md` with main branch and commands is part of M0.
11. **STT location**: Resolved 2026-11-13: whisper.cpp stays in the capture agent (ARCHITECTURE section 9).

## 6. Name: Interline (validated 2026-11-13)

Known collisions, accepted as a risk by Phuong: Notu.la (iOS, meeting notes), interline.ai (Indonesian SaaS), interlineapp.com (macOS transcription app) and above all **github.com/luqmansungkar/interline**, a macOS meeting notepad "in the spirit of Granola".

To do before the public beta: trademark search (EUIPO, USPTO, INPI), domain, GitHub organisation, and a distinctive tagline that makes the project findable (for example "Interline - meeting notes that keep your words" [to confirm]).
