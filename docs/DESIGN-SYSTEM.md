# DESIGN-SYSTEM - Art direction and tokens

Status: draft 2, 2026-11-13. The visual rendering (OpenDesign mock-ups) is produced separately; this document fixes the tokens, the rules and the screen specs. Contrasts computed (WCAG 2.x) on the values below. The design targets the React UI in the Tauri webview and stays valid with the hybrid stack (ADR-001).

## 1. Identity

- **Name**: **Interline** (validated by Phuong). Tagline idea: "the AI writes between your lines".
- **Three intent words**: **paper, calm, faithful.**
  - Paper: warm background, lots of white, one column of text in the centre, no chrome.
  - Calm: nothing moves during the meeting except what you type; one bright colour only, reserved for recording.
  - Faithful: your text is always the main ink; the AI is a secondary ink, marked, reversible.
- **What we refuse**: full-screen chatbot, "AI" gradients, purple sparkles, dashboards, badges everywhere, confetti.
- **Feel reference**: Granola (sober, document in the centre). We stand apart with a warmer paper tint, a sage accent and an explicitly marked AI ink.

## 2. Typography

Fonts **bundled in the app** (no call to Google Fonts: privacy and offline use; it also guarantees identical rendering on every OS).

| Role | Font | License |
|---|---|---|
| Interface + notes | Inter Variable (with `cv11`, `ss01`, tabular figures for durations) | OFL |
| Notes, "serif" option | Source Serif 4 Variable | OFL |
| Timestamps, code | JetBrains Mono | OFL |

Scale (desktop, base 14 px for the UI, 16 px for notes):

| Token | Size / line height | Weight | Use |
|---|---|---|---|
| `text-xs` | 11 / 16 | 500 | Timestamps, counters, kbd |
| `text-sm` | 12.5 / 18 | 450 | Metadata, secondary sidebar |
| `text-base` | 14 / 20 | 450 | Default UI, transcript |
| `note-body` | 16 / 26 | 400 | Note body (user and AI) |
| `note-h3` | 16 / 26 | 600 | Section heading inside a note |
| `note-h2` | 19 / 28 | 600 | |
| `note-h1` | 24 / 32 | 650 | Meeting title |
| `display` | 30 / 36 | 650 | Onboarding only |

Note column: max width 680 px (about 70 characters), centred. Letter spacing `-0.01em` above 19 px.

## 3. Palette

A single accent hue (sage) for the primary action and focus; red is reserved for recording and destructive actions.

| Token | Light | Dark | Use | Contrast (on `bg`) |
|---|---|---|---|---|
| `bg` | `#FAF9F6` | `#161514` | Paper background | |
| `surface` | `#FFFFFF` | `#1E1D1B` | Panels, popovers | |
| `surface-sunken` | `#F3F1EC` | `#262421` | Sidebar, transcript, fields | |
| `border` | `#E6E2DA` | `#2E2C29` | Separators | |
| `border-strong` | `#D4CFC4` | `#3D3A36` | Fields, inactive focus | |
| `ink` | `#1C1B19` | `#EDEBE6` | User text, titles | 16.4 / 15.3 |
| `ink-ai` | `#57534C` | `#ABA69D` | Text added by the AI | 7.3 / 7.5 |
| `ink-muted` | `#77726A` | `#8C877E` | Metadata (never on `surface-sunken` below 14 px: 4.2:1 in light) | 4.5 / 5.1 |
| `ai-rule` | `#C9D6CC` | `#34453A` | 2 px vertical rule left of AI additions | decorative |
| `accent` | `#3B6B4C` | `#8DBE9B` | Primary button, links, focus | 5.9 / 8.7 |
| `accent-fg` | `#FFFFFF` | `#161514` | Text on accent | 6.2 / 8.7 |
| `accent-subtle` | `#E8EFE9` | `#1F2A22` | Selection, active hover | |
| `rec` | `#C2362B` | `#F0675E` | Recording only | 5.2 / 5.9 |
| `rec-subtle` | `#FBEAE8` | `#3A1E1C` | Recording pill | |
| `danger` | `#B42318` | `#F07A6E` | Deletion, errors | 6.2 / 6.7 |
| `warning` | `#8A5A00` | `#E0B35C` | Missing permission, disk encryption off, unknown state | 5.6 / 9.4 |
| `success` | `#2E7346` | `#7CC795` | Model ready, connection test OK | 5.5 / 9.1 |
| `highlight` | `#FFF1BF` | `#4A3F14` | Highlighted source segment, search result | |

Dark mode follows the system by default (setting Light / Dark / System).

## 4. Spacing, radii, elevation

- **Spacing** (base 4): `0` 0, `1` 4, `2` 8, `3` 12, `4` 16, `5` 20, `6` 24, `8` 32, `10` 40, `12` 48, `16` 64 px.
  - Window padding: 24. Note top margin: 48. Space between blocks: 4 (paragraphs), 16 (before a heading).
  - Sidebar: 240 px, items 32 px high. Transcript panel: 360 px, resizable 280 to 520.
- **Radii**: `sm` 4 (badges, kbd), `md` 6 (buttons, fields), `lg` 10 (popovers, cards), `xl` 14 (dialogs), `full` (recording pill).
- **Elevation** (light; in dark, the shadow is replaced by `border` + a lighter surface):
  - `e0` none (the whole document)
  - `e1` `0 1px 2px rgba(28,27,25,.06)`: floating recording bar
  - `e2` `0 4px 16px rgba(28,27,25,.08), 0 0 0 1px rgba(28,27,25,.04)`: popovers, menus
  - `e3` `0 12px 40px rgba(28,27,25,.14), 0 0 0 1px rgba(28,27,25,.05)`: dialogs, command palette
- **Window chrome**:
  - macOS (v1): transparent title bar (`titleBarStyle: Overlay`), native traffic lights, 40 px drag area.
  - Windows and Linux (post-v1): native window decorations; the layout must not assume traffic lights on the left. The UI reads a `data-platform` attribute (`macos | windows | linux`) set at startup and adapts the title area and shortcut glyphs.

## 5. Motion

| Token | Duration | Easing | Use |
|---|---|---|---|
| `instant` | 80 ms | `linear` | Hover, press |
| `fast` | 140 ms | `cubic-bezier(.2,0,0,1)` | Menus, tooltips, toggles |
| `base` | 220 ms | `cubic-bezier(.2,0,0,1)` | Panels, dialogs |
| `gentle` | 400 ms | `cubic-bezier(.3,0,0,1)` | Appearance of AI additions (fade + 4 px) |
| `pulse` | 1600 ms | `ease-in-out`, infinite | Recording dot only |

Rules: during recording, no animation in the notes area. AI additions appear block by block (40 ms stagger), not streamed character by character (less distracting, and the JSON is only validated at the end). `prefers-reduced-motion`: everything goes to 0 ms, the pulse becomes a fixed dot.

## 6. Component states

For every interactive component: `default`, `hover` (background `accent-subtle` or `surface-sunken`), `active` (4 % darker), `focus-visible` (2 px `accent` ring, 2 px offset, never removed), `disabled` (45 % opacity, default cursor, tooltip explaining why), `loading` (14 px spinner, label kept).

Specific components:
- **Record button**: `idle` (outline, mic icon), `starting` (spinner), `recording` (background `rec-subtle`, pulsing `rec` dot, tabular timer), `paused` (fixed dot, label "Paused" ("En pause")), `stopping`, `error` (`warning` icon), `unknown` (`warning` tone, label "State unknown" ("Etat inconnu"), shown while the core is unreachable: never a fake "recording" or "stopped").
- **Note block**: `user` (ink `ink`), `ai-proposed` (ink `ink-ai`, `ai-rule` rule, actions on hover: Accept Mod+Enter / Delete Backspace), `ai-accepted` (ink `ink-ai`, subtler rule), `ai-edited` (becomes ink `ink`, small "edited" mark), `source-highlight` (`highlight` background while hovering the citation), `pending-sync` (tiny dot in the margin while the edit is only in the local journal).
- **Transcript segment**: `live` (last segment, light italic), `final`, `edited`, `suppressed` (echo duplicate, hidden by default), `cited` (highlighted when an AI addition points to it), `recovered` (after a crash recovery, small marker), `gap` (marker line "Capture interrupted for N s").
- **LLM provider**: `not configured`, `test running`, `OK` (`success`), `error` (exact provider message, without the key).
- **Whisper model**: `absent`, `downloading n %` (resumable), `verifying`, `ready`, `corrupted` (download again).

**Shortcuts**: defined as `Mod+key`; rendered with Cmd glyphs on macOS and as Ctrl on Windows and Linux. This document writes them with Cmd for readability.

## 7. v1 screens

Common layout: left sidebar (240 px, collapsible Cmd+\\) + document in the centre + optional right panel (transcript or brief). Command palette Cmd+K everywhere.

### 7.1 Onboarding (4 steps, once)
- **Goal**: make the app usable in 3 minutes without sending anything.
- **Content**: 1. Promise ("Your notes, enhanced, on your machine" ("Tes notes, enrichies, sur ta machine")) + policy in 5 lines. 2. Permissions: only the permissions the platform agent advertises are shown; on macOS: microphone, system audio (tested by playing a sound), calendar (optional, or paste an ICS URL). 3. Transcription model: recommendation by detected RAM, size shown, background download. 4. AI (optional): Ollama detected or an OpenAI-compatible key; "Later" given as much weight as "Configure".
- **Primary actions**: Continue, Allow, Download, Skip.
- **Empty states**: without network, step 3 says "The model will download as soon as you are online"; the app stays usable as a notepad.
- **Errors**: permission denied -> explanation + "Open System Settings" + "Continue without"; download interrupted -> automatic resume, Retry button; not enough disk space -> required size and a smaller model offered.

### 7.2 Home / meeting list
- **Goal**: know what is coming and find what happened.
- **Content**: "Today" section (upcoming calendar events with time, participants as initial avatars, "Open the note" button); past meetings grouped by day (title, duration, first-line excerpt, number of open actions); quick filter (All / With open actions); "Recovered meeting" label on meetings rebuilt after a crash.
- **Primary actions**: New note (Cmd+N), Record now (Cmd+Shift+R), open a meeting.
- **Empty states**: no meeting -> one sentence + "New note" button + shortcut; no calendar -> discreet "Connect a calendar" line (dismissable).
- **Errors**: database unreachable (disk, migration) -> blocking screen with the file path, "Export a backup" button and a link to the issue tracker; calendar access revoked -> `warning` banner.

### 7.3 Meeting note - during
- **Goal**: type without being distracted.
- **Content**: editable title; meta line (date, participants); brief collapsed to one line if available; editor in the centre; floating recording bar bottom centre (`e1`): red dot, timer, mini meters Me / Them (two bars, no animated waveform), Pause, Stop, Transcript button.
- **Primary actions**: type; Pause / Stop (Cmd+Shift+R); open the transcript (Cmd+Shift+T).
- **Empty states**: empty note -> placeholder "Note what matters, in any order. The rest comes later." ("Note ce qui compte, en vrac. Le reste viendra apres.")
- **Errors**:
  - System stream silent for 10 s -> inline `warning` banner "I can't hear the other participants" ("Je n'entends pas les autres participants") + likely cause.
  - Microphone unplugged -> automatic switch to the default mic + toast.
  - Capture agent restarting -> banner "Capture restarting" ("Capture en reprise"); audio is spooled to disk, notes untouched; the gap is marked in the transcript.
  - Core unreachable -> non-blocking banner "Interline is restarting its engine" ("Interline redemarre son moteur"); the editor stays editable and edits are journaled locally; after 3 failed restarts, full-screen error with Restart / Open logs folder / Copy diagnostics (notes still visible and copyable).
  - Disk almost full -> alert at 1 GB remaining.

### 7.4 Meeting note - after (enhanced)
- **Goal**: review, validate, move on.
- **Content**: enhanced notes (user ink + AI ink); segmented toggle "My notes / Enhanced"; Actions section (checkboxes, owner, due date); "Other points" section; transcript panel with cited segments highlighted; discreet result bar at the top "Enhanced with <model> - 0.04 USD estimated - Undo".
- **Primary actions**: Enhance / Re-enhance, Accept all, Accept / delete per block, Copy follow-up email, Export Markdown, choose the note template.
- **Empty states**: no LLM configured -> the "Enhance" button opens an explanation + link to Settings; empty transcript -> "No speech detected" + check permissions.
- **Errors**: provider error (401, quota, timeout) -> exact message, notes intact, Retry; invalid output after 2 attempts -> "The model did not respect the format" + suggestion of another model; long processing -> progress by step (Transcription, Enhancement), cancellable.

### 7.5 Pre-meeting brief
- **Goal**: know in 20 seconds where things stood.
- **Content**: participants; 1 to 3 last related meetings (link + date); open actions involving these people; with an LLM: 5 lines "Last time / Pending / To discuss". Shown in the right panel or collapsed at the top of the note.
- **Primary actions**: Open the note, Record, Regenerate.
- **Empty states**: first meeting with these people -> "First meeting. Nothing in the history yet."; no known participant -> match by title, otherwise nothing.
- **Errors**: LLM unavailable -> version without AI (raw list), never an empty screen.

### 7.6 Search and command palette (Cmd+K)
- **Goal**: reach everything from the keyboard.
- **Content**: a single field; results grouped: Actions (New note, Record, Settings...), Meetings (title, date), Passages (excerpt with the term highlighted, from notes or transcript).
- **Primary actions**: Enter opens and scrolls to the passage; Cmd+Enter opens in the panel.
- **Empty states**: no result -> "Nothing for "x"" + suggestion to remove a filter.
- **Errors**: corrupted FTS index -> "Rebuild the index" (single action).

### 7.7 Settings
- **Goal**: complete, readable control.
- **Content (tabs)**: General (language, theme, shortcuts), Audio (microphone, test of both streams with meters, dedup), Transcription (model, language, downloads), AI (providers, default model, automatic enhancement, note templates), Calendar (included calendars, ICS URLs, notifications), Privacy (active network destinations, including the MCP server: off by default, post-v1; audio retention; disk encryption check (FileVault on macOS); consent reminder; template sentence), Storage (space used, location, full export, deletion), About (version, license MIT, updates).
- **Primary actions**: Test connection, Download, Export all, Delete.
- **Empty states**: no provider -> card "Add Ollama or an OpenAI-compatible key".
- **Errors**: key refused -> provider code and message; secure store (Keychain on macOS) unreachable -> explanation + retry.

### 7.8 Dialogs
- **Consent reminder**: short text, copyable FR / EN template sentence, "Don't remind me for this series", Record / Cancel.
- **Deletion**: "Delete the meeting and its audio? This cannot be undone." `danger` button.
- **Missing permission**: which permission, why, button to the OS settings, "Continue without".
- **Quit during a recording**: "Stop and save" / "Keep recording".

### 7.9 Menu bar (macOS) / system tray (Windows, Linux)
- **Goal**: record without opening the window, see the state.
- **Content**: monochrome icon; red dot while recording; menu: next meeting, Record / Stop, Open, Quit.
- **Errors**: icon with a `warning` dot if a permission is missing or the state is unknown.

## 8. Accessibility (non-negotiable)

- AA contrast minimum (values above); the AI is distinguished by ink + rule + a screen-reader label ("AI addition"), never by colour alone.
- Everything from the keyboard; logical tab order; shortcuts listed (Cmd+/).
- Screen readers (VoiceOver on macOS; NVDA and Orca when ported): the recording state is announced (`aria-live="polite"`), the timer is not.
- Click targets 28 px minimum (desktop).
