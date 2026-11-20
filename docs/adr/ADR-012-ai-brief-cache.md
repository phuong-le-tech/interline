# ADR-012 - Derived AI brief cache

Status: Accepted, 2026-11-20.

## Decision

V6 adds `briefs`, one cache row per meeting. `text` is JSON containing validated written lines and their related meeting citations. `input_hash` is SHA-256 of the raw non-AI brief plus the current meeting title. `ai_run_id` links to a `brief` run; `created_at` records cache creation. User notes are read only.

GET recomputes the hash and returns lines only on a match; a mismatch returns `stale` without old text. Only an explicit POST with a default provider generates a brief. The prompt receives first names, related titles and dates, user-note excerpts and open actions, never participant email addresses or transcripts. Each line must cite a currently related meeting; invalid output gets one retry and is never cached.

The additive migration preserves all existing data. Older binaries ignore the new table and may keep writing notes; the new reader recomputes inputs on every read. Rollback retains V6 and the cache table, with no destructive down migration. Concurrent generation for one meeting is refused; a result whose inputs changed during generation remains stale.
