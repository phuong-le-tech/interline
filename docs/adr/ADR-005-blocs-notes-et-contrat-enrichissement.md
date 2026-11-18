# ADR-005 - Note blocks with time anchors and the enhancement contract

Status: **Accepted**, 2026-11-17.
Related: ADR-001 (hybrid stack), ADR-002 (agent protocol), ADR-006 (BYOK), `docs/ARCHITECTURE.md` sections 6-10 and 13, `docs/ROADMAP.md` M2, `docs/SPEC.md` E3/E4/US-6.1, `docs/DESIGN-SYSTEM.md` 6-7.4.

## Constraint

The user writes notes during or after a meeting. M2 must:

- keep every user word byte-identical and never reorder, rewrite or repeat it;
- let the AI add sourced completions to user bullets, surface uncited points and extract action items;
- make the result reversible (undo all AI changes in one click) and robust to core crashes while typing.

## Decision

1. **Block model.** Notes are a list of blocks. Each block carries:
   - `id`: UUID v7, generated client-side once and never changed;
   - `anchorMs`: milliseconds since the meeting `t0_wall_ms`, set once at creation and never changed;
   - `origin`: `user` or `ai`;
   - `parent_id`: for AI additions, the user block they complete;
   - `position`: lexicographic ordering key;
   - `kind`: `heading`, `paragraph`, `bullet`, `todo`, `quote`;
   - `content`: the structured JSON content;
   - `plain_text`: the rendered text for search/export.

2. **Editor.** TipTap 3.x (`@tiptap/react`, StarterKit, task list, `@tiptap/extension-unique-id`). The unique-id extension guarantees stable `id` attributes on block nodes; the document schema maps TipTap nodes to our block model.

3. **Persistence.** Migration `V3__m2_notes.sql` adds `note_blocks`, `block_sources`, `note_snapshots`, `ai_runs`, `action_items`, `providers`, `templates` and `meetings.template_id`. It also adds FTS5 indexes `blocks_fts` and `segments_fts` with `tokenize='unicode61 remove_diacritics 2'` and sync triggers. The migration is additive only: no existing M1 column changes.

4. **Sync protocol.** `PUT /api/v1/meetings/{id}/blocks/{blockId}` carries `client_rev`. The core ignores any rev `<=` the stored rev (idempotent) and applies last-writer-wins per block. The UI writes every edit to an IndexedDB journal first, drops the entry on acknowledgement and replays the remaining entries in order after reconnect.

5. **Enhancement contract (core-domain, pure Java).** `EnhancementParser`, `EnhancementValidator`, `Alignment`, `ModeSelector` and `EnhancementOutput` form a strict contract:
   - every addition and action item must cite at least one non-suppressed transcript segment of this meeting;
   - an addition is at most 3 sentences and at most 80 characters longer than the user fragment it completes;
   - single-pass prompt rendering so substituted user notes and transcript text are never re-scanned for placeholders;
   - full mode when estimated tokens (`chars / 4`) `<= 75 %` of `context_tokens`, windowed otherwise (one call per block window plus one for other points);
   - invalid output: one retry with the violation list, then drop violating items; still-invalid JSON leaves notes untouched with message "The model did not respect the format".

6. **AI block lifecycle.** AI additions are `origin=ai` blocks with `ai_state=proposed`. Accept sets `ai_state=accepted`; the user editing an AI addition sets `ai_state=edited`. Delete removes the AI block. Undo all deletes every AI block and AI action item of the meeting.

## Consequences

- User text never moves or reflows; AI additions enter with a two-inks animation (transform/opacity only, `prefers-reduced-motion` honoured).
- Search covers both note blocks and transcript segments.
- A raw-user snapshot is written before each enhance run so undo is a single database operation.
- The journal makes the UI crash/core-crash resilient without a complex sync protocol.
- Because block ids are generated client-side, the core must accept UUID v7 strings and never reassign them.
