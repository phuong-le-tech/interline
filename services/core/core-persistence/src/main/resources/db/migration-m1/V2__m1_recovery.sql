-- M1 additive migration, kept in its own Flyway location so the M0 V1 schema suite still sees V1 alone.
-- 'recovered' marks a meeting whose capture died with the app and was rebuilt from the agent's rescue spool.
-- SQLite cannot change a CHECK constraint in place: the table is rebuilt (foreign keys are off on this connection).
-- Flyway does not wrap this file (see the .conf): the PRAGMA is a no-op inside a transaction, so the
-- rebuild runs in an outermost SAVEPOINT (Flyway's parser reads BEGIN as a block opener), so the
-- RELEASE commits it and a failure leaves 'meetings' untouched.
PRAGMA foreign_keys = OFF;
SAVEPOINT m1_recovery;

CREATE TABLE meetings_m1 (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL CHECK (status IN ('draft','recording','degraded','processing','done','failed','recovered')),
  language TEXT,
  started_at INTEGER,
  ended_at INTEGER,
  t0_wall_ms INTEGER,
  audio_dir TEXT,
  audio_purge_at INTEGER,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL,
  deleted_at INTEGER
);
INSERT INTO meetings_m1 SELECT id, title, status, language, started_at, ended_at, t0_wall_ms, audio_dir,
  audio_purge_at, created_at, updated_at, deleted_at FROM meetings;
DROP TABLE meetings;
ALTER TABLE meetings_m1 RENAME TO meetings;

-- Audio recovered from a crashed session, waiting for an agent to transcribe it (transcribe.file).
CREATE TABLE recovery_jobs (
  id TEXT PRIMARY KEY,
  meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  stream TEXT NOT NULL CHECK (stream IN ('mic','system')),
  path TEXT NOT NULL,
  offset_samples INTEGER NOT NULL,
  done INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX recovery_jobs_pending ON recovery_jobs (done, meeting_id);

RELEASE m1_recovery;
