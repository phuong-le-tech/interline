# Interline

**A meeting notepad that writes between your lines.**

Interline is an open source, local-first meeting notepad for macOS. You scribble rough notes while you talk; it captures the system audio without a bot joining the call, transcribes locally, and then completes your notes from the transcript - **without ever rewriting a word you wrote**.

Your words stay your words. The machine writes in a second ink, and every addition points back to the moment it came from.

## Status

M0 scaffold is implemented and green: Java core, Agent Protocol v1, SQLite persistence, a fixture-replaying MockAgent, local API with token auth, React UI and a thin Tauri shell. The Swift capture agent is out of scope for M0.

- Product spec, architecture, data model, roadmap: [`docs/`](docs/)
- Decisions: [`docs/adr/`](docs/adr/)
- Design system and screens: [`docs/DESIGN-SYSTEM.md`](docs/DESIGN-SYSTEM.md), [`design/`](design/)

## Principles

1. **Two inks.** Your text is never modified, moved or reflowed. Everything the model adds is visibly separate, cited, and reversible.
2. **Local by default.** Notes, audio and transcripts stay on your Mac in a SQLite database. No account, no sync, no telemetry. AI is bring-your-own-key.
3. **No meeting bot.** System audio is captured on the device, so Zoom, Meet, Teams and any other app work without inviting a participant.
4. **Portable by construction.** The macOS capture agent is one implementation behind a versioned protocol. Windows and Linux agents are planned, not retrofitted.
5. **MIT, and nothing paywalled.** No open core, no pro tier.

## Stack

| Layer | Choice |
|---|---|
| Shell | Tauri 2, deliberately thin: window, webview, process supervision |
| Core | Spring Boot on Java 21, SQLite via JDBC and Flyway, Spring AI, local HTTP API |
| Capture agent | Native per OS behind Agent Protocol v1 - Swift on macOS |
| Interface | React and TypeScript in the Tauri webview |

Rationale and the conditions under which we would revisit it: [`docs/adr/ADR-001-stack-hybride.md`](docs/adr/ADR-001-stack-hybride.md).

## Requirements (v1)

macOS 14.2 or later. System audio capture relies on Core Audio process taps, which do not exist before 14.2.

## Documentation

| File | Contents |
|---|---|
| `docs/SPEC.md` | Vision, personas, user stories, out of scope |
| `docs/ARCHITECTURE.md` | Processes, agent protocol, data model, packaging, tests |
| `docs/DESIGN-SYSTEM.md` | Colour, type, motion, screens |
| `docs/ROADMAP.md` | M0 to M5, with exit criteria |
| `docs/PLATFORM.md` | Per-OS capture, permissions, porting effort |
| `docs/REPORT.md` | Market landscape, differentiator, risks |
| `docs/UI-COMPONENTS.md` | Component references |
| `docs/adr/` | Architecture decision records |

## License

MIT. See [LICENSE](LICENSE).

## Commands

Use JDK 21 and pnpm 12.16.1.

```bash
cd services/core
./mvnw -B verify                            # all Java tests and packaged-process contracts
./mvnw -B -DskipTests package               # build executable core and MockAgent jars

cd ../../apps/desktop
pnpm install --frozen-lockfile
pnpm lint
pnpm typecheck
pnpm test:e2e                               # Chromium, Vite, real core jar and MockAgent

# Native smoke test (macOS; requires the core jar built above)
pnpm tauri dev
```

The AC16 native-window and clean-quit checklist is in `services/core/app/src/test/resources/AC16-manual.md`. No WAV is committed; the build generates synthetic stereo audio in isolated temporary directories. Public fixtures are in `fixtures/audio/standup-fr-2min.transcript.json`.
