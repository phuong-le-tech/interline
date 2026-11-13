# UI-COMPONENTS - 21st.dev shortlist for the v1 UI

Status: draft 2, 2026-11-13. Component ids are unchanged since draft 1: each id below was checked with `21st search` then `21st get <id> --json` (the component exists and its code can be fetched). The imports listed come from the fetched code. Nothing is installed or coded yet.
Context: the React UI runs in the Tauri webview and talks to the Spring Boot core over a loopback HTTP API and an SSE event stream (ARCHITECTURE section 8). It never talks to the capture agent or to the OS directly.

## Adoption rules

1. **One primitive base only: shadcn/ui on Radix + Tailwind 4.** Several components found import `@base-ui/react` or `react-aria-components`: we rewrite them on Radix (or take the alternative noted) to avoid shipping three primitive libraries.
2. **One animation library only: `motion`.** Components using `framer-motion` move to `motion/react` (same API).
3. **Icons: `lucide-react` only.** Replace `@tabler/icons-react`, `@untitledui/icons`, `@solar-icons/react`.
4. **Tokens**: replace every hard-coded colour, radius and shadow with the DESIGN-SYSTEM.md tokens.
5. **License**: the repo is MIT; each copied component must be under an MIT-compatible license (MIT, Apache-2.0, BSD, ISC); no GPL/AGPL code **[to verify component by component, not verified here]**.
6. Installation: `npx shadcn@latest add "https://21st.dev/r/<author>/<slug>?api_key=$API_KEY_21ST"` (copies the code into the repo; no runtime dependency on 21st). The key must never be committed.
7. **Portability**: no component may assume macOS. Shortcut glyphs come from a platform-aware `Kbd` component (Cmd on macOS, Ctrl elsewhere); no layout depends on traffic lights; no Node or Tauri API inside feature components (only the `api/` layer and a tiny `platform/` module may touch Tauri).

## Starting themes (`21st search --type theme`)

| Id | Name | Opinion |
|---|---|---|
| `d89e4b30-4890-40a3-80ae-89125feb7355` | Paper and Ink | Good structural base (shadcn light/dark variables, paper tone). Replace the red accent with sage, reduce `--radius` from 1.125 rem to 0.375 rem, lighten the background (`#f2ede3` too beige -> `#FAF9F6`) |
| `d0dcf130-1f28-4992-a99a-ae1381278c56` | Notion | Reference for neutrality and the sidebar; do not copy, compare only |

## By v1 UI element

### Note editor
| Id | Component | Imports | To adapt |
|---|---|---|---|
| **33511** | Rich Text Editor (uvain) | `@tiptap/react`, `@tiptap/starter-kit`, link / placeholder / character-count extensions, Radix tooltip | **Main choice.** Already TipTap + Radix. Remove the fixed toolbar (Granola has none), keep Markdown shortcuts, add block attributes `id` / `anchorMs` / `origin` and the AI decorations |
| 31438 | Rich Text Editor (mohammadshehadeh) | TipTap, shadcn popover, toggle, tooltip | Inline link editing bubble worth taking; the rest is too "word processor" |
| 26926 | Selection Bubble Menu | `framer-motion`, `react-dom` | Floating menu on selection (bold, heading, list, "Ask the AI" in v2). Wire it to TipTap's `BubbleMenu` rather than the DOM selection |
| 19351 | Editor (platejs) | `platejs/react` | **Rejected** (Plate is another engine than TipTap). Listed for the record |

### Rendering AI additions and the diff
| Id | Component | Imports | To adapt |
|---|---|---|---|
| **39717** | BJORK Inline AI edit | `lucide-react` | Inline accept / reject interaction model. Keep only the affordance (buttons on hover, shortcuts) applied to `ai-proposed` blocks, not the inline prompt |
| 18098 | AI Prompt Diff | `lucide-react` | Unified / side-by-side view: base for the "My notes / Enhanced" toggle as a compared view (option); replace the green / red colours with ink + rule |

### Meeting list and navigation
| Id | Component | Imports | To adapt |
|---|---|---|---|
| **1627** | Sidebar (shadcn) | `@radix-ui/react-slot`, `cva`, `lucide-react` | **Main choice**: standard collapsible sidebar (Cmd+\\), sections Today / Recent |
| 31454 | Sidebar (wensity) | `@base-ui/react`, `@tabler/icons-react`, `framer-motion` | Collapse to an icon rail and animated active item: ideas to port onto 1627, do not import as is |
| 1074 | Timeline | `cva`, `lucide-react` | Chronological grouping by day for past meetings; remove the decorative vertical line |
| 27575 | Empty State | `framer-motion` | Empty states (no meeting, no result): discreet icon, one sentence, one button |

### Live transcript panel
| Id | Component | Imports | To adapt |
|---|---|---|---|
| **45555** | Transcript | `react` only | **Main choice**: speakers + timestamps per turn, current line highlighted, click = jump. Replace audio playback with "scroll to segment"; add states `live`, `cited`, `suppressed`, `recovered`, `gap`, inline editing; virtualise (1 h is about 1 000 segments). Fed by `stt.partial` / `stt.final` relayed by the core over SSE |
| 4134 | Resizable | `react-resizable-panels` | Right panel resizable from 280 to 520 px |

### Recorder and meter
| Id | Component | Imports | To adapt |
|---|---|---|---|
| **39934** | Tweenly AI Recorder | `motion/react`, `lucide-react` | **Main choice** for the floating bar: mic pill -> timer + pause + stop. Remove the animated waveform during recording ("calm" rule) in favour of two Me / Them meters; wire it to the core SSE stream (recording state, `level` events) and add the `unknown` state |
| 45289 | Live Waveform Audio Recorder | `react` only | Capsule + scrubber: useful for audio playback (if audio is kept); neutralise the dark iOS theme |
| 44800 | Audio Visualizer | `react` (canvas) | "Bars" variant for the stream test in Settings > Audio and onboarding, fed by `level` events from the core |
| 25395 | Status (diceui) | `radix-ui`, `cva` | Pulsing state dot (recording, missing permission, unknown state); map onto `rec` and `warning` |

### Dialogs and overlays
| Id | Component | Imports | To adapt |
|---|---|---|---|
| **1248** | Dialog (shadcn) | `@radix-ui/react-dialog` | Base for every dialog (consent, missing permission); radius `xl`, shadow `e3` |
| **705** | Alert Dialog (shadcn) | `@radix-ui/react-alert-dialog` | Meeting deletion, quitting during a recording |
| 36529 | Permission Grant | `@base-ui/react`, `@solar-icons/react` | "What we ask / why / how long" structure for the onboarding permission step; take the layout only, on Radix + lucide; the list of permissions comes from the platform capabilities exposed by the core |
| 25002 | Sheet | local shadcn primitives | Brief panel (right side) if it is not in the Resizable |
| 3690 | Toast | `sonner`, `framer-motion` | Toasts (mic changed, export done); keep sonner, drop framer-motion |

### Command palette
| Id | Component | Imports | To adapt |
|---|---|---|---|
| **38984** | Command Menu (victor-teles) | `cva`, `clsx`, `lucide-react` | **Main choice**: groups, keyboard navigation, height animation, reduced motion already handled. Source: the core search API (FTS5) over HTTP (results Actions / Meetings / Passages); check whether to switch to `cmdk` |
| 2075 | Command Palette (rafa-porto) | `framer-motion` | Raycast-inspired: good model for the passage preview; second choice |
| 33492 | Command Menu (properui) | `react-aria-components`, `@untitledui/icons` | **Rejected**: another primitive stack |

### Brief and calendar
| Id | Component | Imports | To adapt |
|---|---|---|---|
| **25353** | Calendar With Event List | shadcn button / calendar / card, `lucide-react` | Today's event list on the home screen; remove the date picker, keep the hourly list |
| 45589 | Event Card | `@base-ui/react` (menu, popover, toggle) | Event card (time, duration, participants, video link): remove RSVP, replace "Join" with "Open the note"; rewrite on Radix |
| 23545 | Presence Avatars | `motion/react` | Stack of initial avatars for participants (no photos: nothing to download) |

### Action items
| Id | Component | Imports | To adapt |
|---|---|---|---|
| **29294** | Checkbox Todo List | `framer-motion`, `lucide-react` | Checkable list with animated strike-through; remove the progress bar, add owner + due date + link to the source |
| 24947 | Sprint Task Checklist | `react` only | Priority badges and "remaining" counter: keep the counter only |

### Settings and onboarding
| Id | Component | Imports | To adapt |
|---|---|---|---|
| **45613** | Settings Panel | `@base-ui/react` (switch, tabs) | Tabbed skeleton (general, system / light / dark theme); rewrite tabs and switch on Radix, add the tabs of DESIGN-SYSTEM 7.7 |
| 26523 | Notification Settings Field | local shadcn primitives | Title + description + switch rows (retention, automatic enhancement, consent reminder) |
| 34763 | Segmented Control | `react` only | "My notes / Enhanced" toggle and theme choice |
| 36489 | Model Picker | `@base-ui/react`, `@solar-icons/react` | Idea of a model menu with descriptions; rewrite on Radix dropdown |
| 34553 | Download Button | `lucide-react` | Download / in progress / ready states of the Whisper model; add percentage and resume (progress from core SSE events) |
| 3734 | Stepper | `react` only | 4-step onboarding progress |

### Small cross-cutting elements
| Id | Component | To adapt |
|---|---|---|
| 28308 | Combo Kbd | Shortcut display in menus and tooltips; base for the platform-aware `Kbd` (Cmd on macOS, Ctrl elsewhere) |
| 26919 | Keyboard Shortcuts | Shortcut help (Cmd+/) |
| 212 | Tooltip (originui, shadcn/Radix base) | Tooltips with shortcut. 31328 (wensity) has the best rendering but relies on `@base-ui/react`: inspiration only |
| 719 | Context Menu (shadcn, Radix) | Right click on a meeting (rename, export, delete). 23642 (cnippet-dev) is more complete but on `@base-ui/react` |
| 23625 | Announcement Banner | Inline banners (silent stream, disk encryption off, capture restarting, core unreachable) in `warning` tone |
| 4876 | Skeleton | Loading of the list and the brief; also the first frame while the core starts |

### v2 (for the record, not used in v1)
| Id | Component | v2 use |
|---|---|---|
| 43228 | Streaming Message | Answers of the chat over history (side panel) |
| 557 | Chat Input | Side chat input |
| 23830 | AI Suggestions | Suggested questions under a meeting |

## What 21st does not cover (we write it)

- The specific TipTap extensions: time anchor, block origin, citations, diff decorations.
- The double Me / Them meter, fed by `level` events relayed by the core over SSE.
- Transcript virtualisation.
- The "What leaves your machine" screen.
- The core connection layer: typed client generated from the core's OpenAPI, fetch-based SSE client sending the bearer token (the browser `EventSource` cannot), reconnection with backoff, TanStack Query as the cache over the HTTP API.
- The "core unreachable" banner and the IndexedDB edit journal with its pending-edits indicator (replay on reconnection, ARCHITECTURE 8.4).
- The record button `unknown` state.
- The platform-aware `Kbd` and the `platform/` module (reads `data-platform`, wraps the 3 Tauri commands).
