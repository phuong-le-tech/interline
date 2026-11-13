# DESIGN-SYSTEM - Art direction, tokens and screens

Status: current, 2026-11-13. Sections 1 to 5 are derived from the working prototype
(`design/interline-desktop.html`), not from a mockup: every value below is read from that file and every
contrast figure was measured in the browser, not estimated. Sections 6 to 8 specify the product surfaces
the prototype does not cover yet (full states, errors, settings).

## 1. Identity

- **Name**: **Interline**. The English word for writing between existing lines, which is exactly what the
  product does.
- **Three intent words**: **paper, calm, faithful.**
  - Paper: a cool, near-white canvas, one column of text, no chrome competing with the note.
  - Calm: hairline borders at 7 %, one elevation, one accent per job, nothing moves while you type.
  - Faithful: your words are always the primary ink, the model's additions are a second ink, marked and
    reversible.
- **What we refuse**: a full-screen chatbot, AI gradients and sparkles, dashboards, badges everywhere,
  coloured paragraphs, glows on a light canvas.
- **Positioning against references**: the stillness and whitespace of Granola, the simplicity and feed of
  Voicenotes, the shape language of modern tool UI - but a proof sheet rather than a warm notepad, and a
  colour system where each hue has one job.

## 2. Typography

The prototype uses system stacks so it stays a single portable file. **The shipped app bundles its fonts**
(no Google Fonts call: privacy, offline, identical rendering on every OS):

| Role | Bundled font | License | Prototype fallback |
|---|---|---|---|
| Interface | Inter Variable (`cv11`, `ss01`, tabular figures) | OFL | `system-ui, -apple-system` |
| Note body | Source Serif 4 Variable | OFL | `"Iowan Old Style", Palatino, Georgia` |
| Data | JetBrains Mono | OFL | `ui-monospace, SFMono-Regular, Menlo` |

Scale as implemented (base 14 px UI, 17 px note):

| Token | Size / line | Weight | Use |
|---|---|---|---|
| `data` | 11 / 1.6 mono | 500 | Timestamps, durations, timecodes only |
| `label` | 12 / 1.6 UI | 500 | Section labels, sentence case, no tracking |
| `ui` | 14 / 1.5 | 450 | Default interface |
| `card-title` | 17 / 1.4 | 550, tracking -0.02em | Meeting cards |
| `note-body` | 17 / 1.6 serif | 400 | Human and machine text, same face |
| `page-title` | 28 / 1.25 | 500, tracking -0.045em | One per screen |

Rules: labels are sentence case in the UI sans; uppercase tracked monospace is reserved for data, because
uppercase tracked labels are what makes an interface read like a compiler output. Headings get
`text-wrap: balance`, body `text-wrap: pretty`, timers `font-variant-numeric: tabular-nums`. Note column
max width 680 px, about 70 characters.

## 3. Colour

Authored in OKLCH in the prototype, hex below for handoff.

### Neutrals

| Token | OKLCH | Hex | Use |
|---|---|---|---|
| `--bg` | 0.9569 0.0025 228.8 | `#EFF1F2` | Canvas, cool paper |
| `--surface` | 0.9873 0.0011 197.1 | `#FAFBFB` | Cards, note paper, recorder |
| `--fg` | 0.2067 0.0061 236.9 | `#15181A` | Primary text |
| `--muted` | 0.4762 0.0149 236.9 | `#555E64` | Secondary text, metadata |
| `--human-ink` | - | `#12161A` | Human note text |
| `--border` | 0.9133 0.0051 228.8 | `#DFE3E5` | Base tone, overridden below |
| `--border` (applied) | - | `rgba(21,24,26,.07)` | Every visible hairline |
| `--separator` | - | `rgba(21,24,26,.05)` | Section separators |
| `--outer-edge` | - | `rgba(21,24,26,.10)` | One per surface, outer card only |

### The four hues, one job each

| Role | Token | Hex | Means | Never means |
|---|---|---|---|---|
| Machine ink | `--petrol` | `#1F6F6B` | Text the model added, citations, ready state | People, pending work |
| Machine stroke | `--petrol-stroke` | `#2C857E` | Icons and rules at small sizes | Body text |
| Live | `--signal` brick | `#A6402F` | Recording now, destructive actions | Anything that is not happening now |
| Future / owed | `--ochre` | `#A87A2C` | Upcoming time, draft, unchecked, due dates | Completed work |
| People | `--indigo` | `#3F5A8C` | Avatars, owners, the Ask destination | Content of any kind |

Derived: `--tint` `oklch(0.9429 0.0128 191.0)` = `#E3EFEE` for petrol chips and citations, and
`--annotation-ink` `#265E5A` for machine text on light surfaces.

### Measured contrast (WCAG 2.1, computed in the browser)

| Pair | Ratio | Needs |
|---|---|---|
| `--muted` on `--bg` | 5.84 | 4.5 |
| `--muted` on `--surface` | 6.39 | 4.5 |
| `--petrol` on `--surface` | 5.72 | 4.5 |
| brick on `--surface` | 5.97 | 4.5 |

Chip labels and due dates use a darkened tone of their hue: raw `--ochre` on its own 10 % tint measures
3.31 and fails. Every chip label must clear 4.5:1. This is a release gate.

### Colour budget

Allowed: chips, 2 px state bars, 2 px machine rules, citation chips, avatars, one 7 % banner tint.
Forbidden: coloured paragraphs, filled large surfaces, colour on date group headers, two hues on one
element, a hue reused for a second meaning.

Dark mode follows the system (Light / Dark / System). In dark, surfaces lift instead of casting shadows and
every hue keeps its job at the same lightness relationship.

## 4. Space, shape, depth

- Spacing scale: 4 / 8 / 12 / 16 / 24 / 28 / 32 / 40 / 48. Inside a card 8 or 16; between sections 28 to 32.
- Radii: `8px` cards, controls and inputs (`--radius`). `999px` chips, avatars, recorder. `6px 6px 0 0`
  for note paper meeting the bottom edge.
- Elevation: exactly one, on the open note paper and the floating recorder:
  `0 1px 2px rgba(16,20,22,.06), 0 6px 16px -8px rgba(16,20,22,.12)`. **No glow**: on a light canvas a
  radial light reads as a smudge. The first attempt at one was measured, rejected and removed.
- Window chrome: macOS (v1) transparent title bar, native traffic lights, 40 px drag area. Windows and
  Linux (post-v1) native decorations; the UI reads `data-platform` and adapts the title area and shortcut
  glyphs, never assuming traffic lights on the left.

## 5. Motion

Two easings as tokens: `--motion-out: cubic-bezier(.2,.8,.2,1)` for entrances and state changes,
`--motion-spring: cubic-bezier(.18,.85,.28,1.08)` for the only spring (transcript width, citations).

| Choreography | Trigger | Timing | What moves |
|---|---|---|---|
| Two inks | Finish, or replay | starts 0 / 45 / 95 / 150 ms, 200-240 ms each, done by 318 ms | Machine blocks fade in with a 6 px rise while their petrol rule draws down. Human text never moves, never reflows |
| Status stamp | Same moment | 120 ms | `RECORDING` crossfades to `ENHANCED`, scale .97 to 1 |
| Recorder | Recording starts | 220 ms | Opacity, 10 px rise, scale .98 to 1, one soft shadow |
| Pause | Toggle | 120 ms | Icon crossfade, no movement |
| Seconds | Every second | 160 ms | Digit rolls vertically, tabular width reserved |
| Transcript peek | Expand or collapse | 260 ms spring | The only real size change in the app; content crossfades with 90 ms overlap |
| New segment | Live | 160 ms | 8 px rise and fade; partial text updates in place, never remounts |
| Speaking line | Live | 160 ms | Left border grows 0 to 2 px |
| Feed entrance | First load only | 40 ms stagger, done by 264 ms | 6 px rise and fade; never replays on return |
| Ask answer | Question sent | 18 ms per 2-3 word chunk | Chunks fade in; citations spring at 140 ms when their line lands |
| Rail indicator | Destination change | 220 ms | Transform only |

Hard rules: one entrance per container; nothing animates that is keyboard-triggered or opened many times a
day; no `transition: all`; only transform and opacity, except the transcript width; every sequence is
interruptible and resets cleanly; `prefers-reduced-motion: reduce` collapses everything to one 120 ms
opacity fade, no delays, no movement. During recording, nothing in the notes area animates.

## 6. Component states

Every interactive component: `default`, `hover` (background `--border` tone), `active` (scale .995),
`focus-visible` (2 px `--accent` ring, 4 px offset, never removed), `disabled` (45 % opacity, default
cursor, tooltip explaining why), `loading` (14 px spinner, label kept).

- **Record button**: `idle`, `starting`, `recording` (brick dot, tabular timer), `paused`, `stopping`,
  `error`, `unknown` (shown while the core is unreachable: never a fake "recording" or "stopped").
- **Note block**: `user` (`--human-ink`), `ai-proposed` (`--annotation-ink` plus the petrol rule, accept
  Mod+Enter / delete Backspace), `ai-accepted`, `ai-edited` (returns to human ink with an "edited" mark),
  `source-highlight` (tint while hovering the citation), `pending-sync` (dot in the margin while the edit
  only exists in the local journal).
- **Transcript segment**: `live`, `final`, `edited`, `suppressed` (echo duplicate, hidden by default),
  `cited`, `recovered` (after a crash), `gap` ("Capture interrupted for N s").
- **LLM provider**: `not configured`, `test running`, `OK`, `error` (exact provider message, never the key).
- **Whisper model**: `absent`, `downloading n %` (resumable), `verifying`, `ready`, `corrupted`.

Shortcuts are written `Mod+key`, rendered with the Cmd glyph on macOS and Ctrl elsewhere.

## 7. v1 screens

Common layout: left rail (184 px, collapsible) plus the document. Command palette on Cmd+K everywhere.

### 7.1 Onboarding (4 steps, once)
- **Goal**: usable in three minutes, nothing sent.
- **Content**: 1. Promise and privacy in five lines. 2. Permissions: only what the platform agent
  advertises (macOS: microphone, system audio tested by playing a sound, calendar optional or ICS URL).
  3. Transcription model: recommendation from detected RAM, size shown, background download. 4. AI
  (optional): Ollama detected or an OpenAI-compatible key, "Later" given the same weight as "Configure".
- **Errors**: permission denied -> explanation, "Open System Settings", "Continue without"; interrupted
  download -> auto resume; low disk -> required size and a smaller model offered.

### 7.2 Home feed
- **Goal**: know what is coming, find what happened.
- **Content**: date, greeting, one primary Record action; an "Up next" banner (ochre, because future);
  meetings grouped by day with a neutral group label; each card: mono time, title, two-line summary,
  duration chip, state chip plus a 2 px state bar.
- **Empty states**: no meeting -> one sentence and the Record button.
- **Errors**: database unreachable -> blocking screen with the path, "Export a backup", link to issues;
  calendar revoked -> banner.

### 7.3 Recording
- **Goal**: type without being distracted.
- **Content**: note column as the hero, serif, tabular timer shown once; collapsed transcript peek at the
  right (48 px) that expands on demand; floating recorder with level ticks, pause, Finish.
- **Errors**: silent system stream for 10 s -> inline banner "I can't hear the other participants";
  microphone unplugged -> switch and toast; agent restarting -> "Capture restarting", audio spooled, gap
  marked; core unreachable -> non-blocking banner, editor stays editable and journaled.

### 7.4 Enhanced note
- **Goal**: review, validate, move on.
- **Content**: summary, key points, action items with owners and due dates, next steps; human fragments
  untouched; each machine addition carries a citation chip; "see my original" toggle; transcript one
  click away with cited segments highlighted.
- **Errors**: provider 401 / quota / timeout -> exact message, notes intact, retry; invalid output after
  two attempts -> "The model did not respect the format" and a suggestion of another model; long run ->
  per-step progress, cancellable.

### 7.5 Ask
- **Goal**: answer a question across all meetings in one screen.
- **Content**: one question field, a synthesised answer with inline citations, sources with date and
  duration, each row in its state hue. Follow-up composer at the bottom.

### 7.6 Brief, search, settings, dialogs, menu bar
- **Brief**: participants, last related meetings, open actions, five lines from the model. Never an empty
  screen when no LLM is configured: the raw list is shown instead.
- **Command palette (Cmd+K)**: one field, results grouped Actions / Meetings / Passages.
- **Settings**: General, Audio (test both streams), Transcription, AI, Calendar, Privacy (what leaves the
  machine, audio retention, FileVault check), Storage, About.
- **Dialogs**: consent reminder, deletion, missing permission, quit during a recording.
- **Menu bar (macOS) / tray**: record, stop, next meeting, state dot.

## 8. Accessibility (non-negotiable)

- Contrast 4.5:1 for body text, 3:1 for large text and UI boundaries, measured for every pair introduced.
- Every interactive element has an accessible name, a visible `:focus-visible` ring, and a hit area of at
  least 44 px in the used dimension. Colour is never the only signal: state chips carry text, machine
  additions carry a rule, a citation and a screen-reader label ("AI addition").
- Everything reachable from the keyboard, logical tab order, shortcuts listed on `Cmd+/`.
- Live regions: the transcript uses `aria-live="polite"`; the timer does not.
- Zoom never disabled, `lang` set, `prefers-reduced-motion` honoured everywhere.

## 9. Verifying a change

- Static: `python3 scripts/audit_ui.py design/interline-desktop.html` - passes over the guidelines
  (transition: all, outline: none, unlabelled icon buttons, missing reduced-motion, ellipsis, heading
  order, tabular numerals, touch-action).
- Runtime: load the file and evaluate `scripts/runtime_audit.js` on each screen - computed contrast of
  every text node against its effective background, hit areas under 44 px, horizontal overflow, unnamed
  buttons, unlabelled controls, presence of a real `:focus-visible` rule, live-region absence.
