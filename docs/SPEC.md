# SPEC - Product specification (v1)

Product name: **Interline** (validated by Phuong, 2026-11-13). License: MIT.
Status: draft 2, 2026-11-13. Decisions marked **[to confirm]** are tech lead assumptions.

## 1. Vision

1. Interline is a meeting notepad shipped first on macOS (v1): you type short notes during the call, the app listens to the computer's audio and completes your notes afterwards, with no bot in the meeting. Its architecture is portable to Windows and Linux from day one (`docs/PLATFORM.md`).
2. Everything stays on your machine: local transcription, local database, no account; cloud AI is used only if you plug in your own key.
3. Your notes stay your notes: the AI adds between your lines, every addition is visible, sourced in the transcript and reversible.

## 2. Personas

**P1 - Camille, product manager, 6 to 10 meetings a day.** Switches between Zoom, Meet and Teams. Types three words per topic, has no time to reread a transcript. Wants: clean notes within 30 seconds after the call, her commitments listed, and a reminder of "where we left off" before the next meeting with the same people. Refuses a visible bot in client calls.

**P2 - Malik, privacy-minded consultant / freelance developer.** Works under NDA with European clients. Does not want audio to leave his machine. Already has Ollama or an OpenRouter key. Wants: everything local, Markdown export to Obsidian, and to know exactly what goes over the network.

## 3. User stories (numbered by epic)

### E1 - Capture
- **US-1.1** As a user, I start recording a meeting with one click or a global shortcut, from the app or the menu bar.
- **US-1.2** The app captures my voice (microphone) and the others' voices (system audio) separately, without joining the meeting.
- **US-1.3** A persistent red indicator (menu bar + window) shows that recording is active, with the duration.
- **US-1.4** I can pause and resume; pause really stops the capture.
- **US-1.5** If a permission is missing or the system stream is silent, the app tells me within 10 s and shows me what to do.
- **US-1.6** If the app crashes, the audio already captured is recovered at restart. No notes are ever lost, even if a component of the app crashes.
- **US-1.7** I can import an existing audio file (m4a, mp3, wav) instead of a recording.

### E2 - Transcription
- **US-2.1** The transcript appears live (target latency < 5 s) in a collapsible panel, with "Me" and "Them" distinguished.
- **US-2.2** Transcription runs locally by default; no audio leaves the machine.
- **US-2.3** On first launch, I choose and download a model (size shown, resumes after an interruption).
- **US-2.4** I choose the language (auto, French, English...) by default and per meeting.
- **US-2.5** I can correct a transcript segment and rename a speaker.

### E3 - Notes and enhancement
- **US-3.1** I type my notes in a simple editor (headings, bullets, checkboxes) while the meeting runs.
- **US-3.2** Each block I write is time-stamped relative to the recording, with no action on my part.
- **US-3.3** At the end, I click "Enhance" (or it is automatic, configurable): my blocks are kept word for word and the AI adds sub-points under each one, plus an "Other points" section for what I did not note.
- **US-3.4** AI additions are visually distinct from my text; I can switch between "My notes" and "Enhanced notes".
- **US-3.5** Each AI addition links to the transcript segments it comes from; one click takes me there.
- **US-3.6** I accept, edit or delete each addition; I can undo the whole enhancement.
- **US-3.7** I choose a note template (1:1, client, interview, team meeting) that steers the enhancement.
- **US-3.8** Without a configured LLM, the app stays useful: notes + transcript + search.

### E4 - After the meeting
- **US-4.1** Action items (who, what, due date if said) are extracted and listed, checkable.
- **US-4.2** I generate a follow-up email draft that I copy (the app does not send it).
- **US-4.3** I export the note as Markdown (file or clipboard), with or without the transcript.
- **US-4.4** I find a meeting through full-text search (notes and transcripts).

### E5 - Before the meeting
- **US-5.1** I connect my calendar (macOS Calendar via EventKit, or an ICS subscription URL that works on any OS); today's meetings appear on the home screen.
- **US-5.2** Before a meeting, I open a brief: participants, notes and open actions from previous meetings with these people or with this title.
- **US-5.3** A notification 1 min before the event offers to open the note; it never starts recording by itself.
- **US-5.4** Without a calendar, I create a meeting by hand and the brief relies on the title and the participants I entered.

### E6 - Settings, privacy, consent
- **US-6.1** I configure one or more LLM providers (OpenAI-compatible URL, key, model) or Ollama; I test the connection.
- **US-6.2** My keys are stored in the OS secure store (Keychain on macOS), never in clear text.
- **US-6.3** I set audio retention (delete after transcription, 7 days, 30 days, keep forever).
- **US-6.4** Before the first recording of a meeting, the app reminds me to inform the participants and offers a consent sentence to copy.
- **US-6.5** A "What leaves your machine" screen lists every possible network call and its state.
- **US-6.6** I delete a meeting and everything linked to it (audio, transcript, notes) in one action.

## 4. The three moments

Shortcuts are shown for macOS; Ctrl replaces Cmd on Windows and Linux.

### Before
1. The home screen shows "Today": calendar events + recent past meetings.
2. 1 min before an event: notification "Meeting with X in 1 min - Open the note".
3. The note opens with the brief at the top, collapsed to one line: last meeting with these people, 3 open actions, what had been decided. Without an LLM: raw list (links to notes, open actions). With an LLM: 5 written lines.
4. Consent reminder (once per meeting, can be turned off per recurring participant).

### During
1. Click "Record" or Cmd+Shift+R. Red indicator + timer.
2. The centre of the screen is the editor; nothing else moves. The live transcript is in a side panel, closed by default.
3. The user types fragments ("budget Q1 ??", "Paul -> follow up legal"). Each block gets its time anchor.
4. If the system stream has been silent for 10 s while the user speaks, a discreet banner: "I can't hear the other participants". Link to the likely cause.

### After
1. Stop. The note goes to the "Processing" state (end of transcription, a few seconds).
2. Automatic enhancement if an LLM is configured and the option is on, otherwise an "Enhance" button.
3. Result: my lines in full ink, AI additions in secondary ink with a side mark, an "Actions" section, an "Other points" section.
4. Review: accept all, or block by block; click on an addition -> source segment.
5. Actions: copy follow-up email, export Markdown, check off actions.

## 5. Out of scope for v1 (explicit)

- Shipping Windows and Linux builds: v1 is macOS only; the architecture is portable and the ports are planned post-v1 as M5 (`docs/PLATFORM.md`). iOS, Android, web.
- Chat over history ("what did I promise?"): v2.
- Multi-speaker diarisation on the "Them" side (v1 only separates Me / Them): v2, experimental.
- Multi-device sync, accounts, link sharing, real-time collaboration.
- Direct Google / Microsoft OAuth (the calendar goes through the OS agent, EventKit on macOS, or an ICS URL).
- Sending emails, Notion / CRM / Slack integrations.
- MCP server: after v1, read-only, off by default (the core reserves the module, ARCHITECTURE 8.2).
- Automatic recording start, automatic call detection.
- Application-level database encryption (v1: OS disk encryption recommended and checked).
- Translation, video summary, screen capture.

## 6. Feature table

| Feature | v1 | v2 / post-v1 | Never |
|---|---|---|---|
| Microphone + system audio capture (macOS 14.2+) | x | | |
| Audio file import | x | | |
| Local transcription with whisper.cpp | x | | |
| Cloud transcription (BYOK) | | x | |
| Me / Them (separation by stream) | x | | |
| Offline multi-speaker diarisation | | x (experimental) | |
| Note editor + time-stamped blocks | x | | |
| AI enhancement that preserves notes + citations | x | | |
| Note templates (4 built in) | x | custom templates | |
| Action item extraction | x | | |
| Follow-up email draft (copy) | x | | |
| Sending email from the app | | | x |
| Calendar via the OS agent (EventKit on macOS) + ICS URL + brief | x | | |
| Google / Outlook OAuth | | x if requested | |
| Full-text search (FTS5) | x | | |
| Semantic search + chat over history | | x | |
| Markdown export | x | automatic folder mirror | |
| Local MCP server | | x (read-only, off by default) | |
| Portable core (same core on macOS, Windows, Linux) | x | | |
| Windows | | x (post-v1, M5a) | |
| Linux | | x (post-v1, M5b) | |
| Mobile | | | x (out of reach for a solo project) |
| Bot that joins the meeting | | | x |
| Telemetry by default | | | x |
| Mandatory account / project backend | | | x |
| Paid features / open core | | | x [to confirm] |

## 7. Non-functional requirements

- **Privacy**: zero network request without an explicit action (model download, configured LLM call, update check that can be turned off). See the annex.
- **Performance**: live transcription on an M1 Mac with 8 GB and the `small` model; target latency < 5 s. Enhancement of a 1 h meeting < 60 s with a cloud model. Budgets (ARCHITECTURE 14.6): window visible <= 0.5 s, core ready <= 2 s cold on an M1 8 GB, idle RAM <= 350 MB for all processes, installed size <= 200 MB, models downloaded separately.
- **Robustness**: no audio loss on a crash. Audio is spooled to disk by the capture agent before being sent; an agent crash resumes in < 2 s with the gap marked in the transcript; notes never transit through the agent and are journaled in the UI if the core is down.
- **Portability**: no OS API in the core; the full pipeline runs in CI on Linux with a MockAgent that replays a WAV (ADR-003).
- **Accessibility**: full keyboard navigation, AA contrast, AI / user distinction never carried by colour alone, "Reduce motion" respected.
- **Languages**: FR + EN interface in v1; transcription in any language Whisper supports.
- **Data compatibility**: versioned SQLite migrations, never destructive; Markdown export always possible.

## Annex - Privacy policy (one page)

**What stays on your machine, always**: audio, transcript, your notes, enhanced notes, action items, the calendar data read, the database, logs. Location: the OS app data folder (macOS: `~/Library/Application Support/Interline/`).

**What can leave, and when**:
1. **Model downloads** (Hugging Face): at first launch and when you change model. No personal data sent, only the file request.
2. **LLM calls**: only if you configured a provider AND started an enhancement, a brief or an email (manually or through the "enhance automatically" option). Content sent: your notes for this meeting, its transcript, the title and first names of participants, and for the brief the notes of related meetings. Never the audio. Destination: the URL you entered. If it is Ollama on `localhost`, nothing leaves.
3. **Update check**: one request to GitHub Releases at startup, can be turned off. It only sends the version and the platform.

**What never leaves**: audio, your API keys (except to their provider), any telemetry, automatic crash reports. The project has no server.

**Inside the app**: the app's components talk over private pipes and a loopback-only API (127.0.0.1, random port, per-launch token); nothing listens on the network. The optional MCP server (post-v1) is off by default and loopback-only.

**Keys**: stored in the OS secure store (macOS Keychain; Windows Credential Manager and Linux Secret Service when ported).

**Retention**: by default, audio is deleted 7 days after the end of the meeting [to confirm]; notes and transcripts are kept until deleted. Deleting a meeting erases everything linked to it, audio files included.

**Encryption at rest**: v1 relies on OS disk encryption (FileVault on macOS) and warns if it is off. Application-level encryption (SQLCipher-compatible) in v2.

**Consent**: recording a conversation may require the agreement of all participants (GDPR in Europe, several US states with "all-party consent"). The app reminds you and provides a template sentence; obtaining consent is your responsibility. No biometric voice profile is created in v1.

**Verifiable**: the Settings > Privacy screen lists every active network destination. The code is public (MIT).
