# ADR-011: Core owns the data

## Status

Accepted

## Context

Interline stores meetings, transcripts, notes and audio on the user's device. The Tauri shell and the web UI are deliberately thin: they do not read or write the SQLite database directly and they do not know the file layout under the data directory. This separation keeps the data model under one owner and prevents a future UI port from duplicating persistence logic.

## Decision

- The core is the single owner of the SQLite database, chunk files, spool files and settings.
- Persistence access goes through `core-persistence` and is exercised only by core modules and tests.
- The UI receives data through the local HTTP API and events. It never opens the database or the audio directory.
- The shell launches the core, hands it the token on stdin, and passes the endpoint to the UI. It does not read the database or the data directory.

## Consequences

- Schema migrations, backups, retention and consent checks live in one place.
- `core-domain` has no dependency on Spring, persistence or adapter classes.
- Changing the storage layout or adding FTS and vector indexes does not require UI changes.
- The shell stays small and can be ported to another OS without touching data logic.
