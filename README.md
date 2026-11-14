# Interline

**A meeting notepad that writes between your lines.**

Interline is an open source, local-first meeting notepad for macOS. You scribble rough notes while you talk; it captures the system audio without a bot joining the call, transcribes locally, and then completes your notes from the transcript - **without ever rewriting a word you wrote**.

Your words stay your words. The machine writes in a second ink, and every addition points back to the moment it came from.

## Status

Design and specification phase. No application code yet.

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

Use JDK 21. M0 is currently at the red-test scaffold stage: application behavior is not implemented, so verification is expected to report failing assertions.

```bash
cd services/core
./mvnw -B verify                           # unit tests and packaged-process contracts
./mvnw -B -DskipTests package               # compile tests and build executable skeleton jars
./mvnw -B -Dmaven.test.failure.ignore=true verify  # collect all red reports; exit status alone is not a test verdict

cd ../../apps/desktop
pnpm install --frozen-lockfile
pnpm lint
pnpm typecheck
pnpm test:e2e                              # Chromium, Vite, real core jar and MockAgent
```

The AC16 native-window and clean-quit checklist is in `services/core/app/src/test/resources/AC16-manual.md`. The Tauri shell is not implemented in the red scaffold. No WAV is committed; Java pipeline tests generate synthetic stereo audio in isolated temporary directories.
