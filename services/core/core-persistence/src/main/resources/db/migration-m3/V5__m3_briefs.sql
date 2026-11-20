-- Derived cache only. Existing meetings and user notes are unchanged.
CREATE TABLE briefs (
  id TEXT PRIMARY KEY,
  meeting_id TEXT NOT NULL UNIQUE REFERENCES meetings(id) ON DELETE CASCADE,
  text TEXT NOT NULL,
  input_hash TEXT NOT NULL,
  ai_run_id TEXT REFERENCES ai_runs(id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL
);
