-- M2 notes and AI migration (docs/ARCHITECTURE.md section 13 plus the M2 additions), additive only:
-- no existing M1 column changes. Kept in its own Flyway location like V2.

CREATE TABLE providers (
  id TEXT PRIMARY KEY,
  kind TEXT NOT NULL CHECK (kind IN ('openai_compatible','anthropic','ollama')),
  name TEXT NOT NULL,
  base_url TEXT NOT NULL,
  model TEXT NOT NULL,
  -- Account name in the OS secret store. The API key itself is never stored here.
  secret_account TEXT,
  supports_json_schema INTEGER NOT NULL DEFAULT 0,
  is_default INTEGER NOT NULL DEFAULT 0,
  created_at INTEGER NOT NULL,
  context_tokens INTEGER,
  price_in_per_mtok REAL,
  price_out_per_mtok REAL
);

CREATE TABLE templates (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  instructions TEXT NOT NULL,
  builtin INTEGER NOT NULL DEFAULT 0
);

ALTER TABLE meetings ADD COLUMN template_id TEXT REFERENCES templates(id);

CREATE TABLE ai_runs (
  id TEXT PRIMARY KEY,
  meeting_id TEXT REFERENCES meetings(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('enhance','brief','followup_email','title')),
  provider_id TEXT REFERENCES providers(id) ON DELETE SET NULL,
  model TEXT NOT NULL,
  prompt_version TEXT NOT NULL,
  input_tokens INTEGER,
  output_tokens INTEGER,
  status TEXT NOT NULL CHECK (status IN ('running','ok','invalid_output','error','cancelled')),
  error TEXT,
  started_at INTEGER NOT NULL,
  finished_at INTEGER,
  cost_estimate_usd REAL
);
CREATE INDEX ai_runs_meeting ON ai_runs (meeting_id, started_at);

CREATE TABLE note_blocks (
  rowid_fts INTEGER PRIMARY KEY,
  id TEXT NOT NULL UNIQUE,
  meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  parent_id TEXT,
  position TEXT NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('heading','paragraph','bullet','todo','quote')),
  origin TEXT NOT NULL CHECK (origin IN ('user','ai')),
  content TEXT NOT NULL,
  plain_text TEXT NOT NULL,
  anchor_ms INTEGER,
  client_rev INTEGER NOT NULL DEFAULT 0,
  ai_run_id TEXT REFERENCES ai_runs(id) ON DELETE SET NULL,
  ai_state TEXT CHECK (ai_state IN ('proposed','accepted','edited')),
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);
CREATE INDEX note_blocks_meeting ON note_blocks (meeting_id, position);
CREATE INDEX note_blocks_parent ON note_blocks (parent_id);

CREATE TABLE block_sources (
  block_id TEXT NOT NULL REFERENCES note_blocks(id) ON DELETE CASCADE,
  segment_id INTEGER NOT NULL REFERENCES transcript_segments(id) ON DELETE CASCADE,
  PRIMARY KEY (block_id, segment_id)
);

CREATE TABLE note_snapshots (
  id TEXT PRIMARY KEY,
  meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  kind TEXT NOT NULL CHECK (kind IN ('raw_user','enhanced','manual')),
  doc TEXT NOT NULL,
  created_at INTEGER NOT NULL
);
CREATE INDEX note_snapshots_meeting ON note_snapshots (meeting_id, created_at);

CREATE TABLE action_items (
  id TEXT PRIMARY KEY,
  meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  text TEXT NOT NULL,
  owner_label TEXT,
  owner_person_id TEXT,
  due_date TEXT,
  status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','done','dismissed')),
  source_segment_id INTEGER REFERENCES transcript_segments(id) ON DELETE SET NULL,
  ai_run_id TEXT REFERENCES ai_runs(id) ON DELETE SET NULL,
  created_at INTEGER NOT NULL,
  completed_at INTEGER
);
CREATE INDEX action_items_meeting ON action_items (meeting_id);

CREATE VIRTUAL TABLE blocks_fts USING fts5(plain_text, content='note_blocks', content_rowid='rowid_fts',
  tokenize='unicode61 remove_diacritics 2');
CREATE VIRTUAL TABLE segments_fts USING fts5(text, content='transcript_segments', content_rowid='id',
  tokenize='unicode61 remove_diacritics 2');

CREATE TRIGGER note_blocks_ai AFTER INSERT ON note_blocks BEGIN
  INSERT INTO blocks_fts(rowid, plain_text) VALUES (new.rowid_fts, new.plain_text);
END;
CREATE TRIGGER note_blocks_ad AFTER DELETE ON note_blocks BEGIN
  INSERT INTO blocks_fts(blocks_fts, rowid, plain_text) VALUES ('delete', old.rowid_fts, old.plain_text);
END;
CREATE TRIGGER note_blocks_au AFTER UPDATE ON note_blocks BEGIN
  INSERT INTO blocks_fts(blocks_fts, rowid, plain_text) VALUES ('delete', old.rowid_fts, old.plain_text);
  INSERT INTO blocks_fts(rowid, plain_text) VALUES (new.rowid_fts, new.plain_text);
END;

CREATE TRIGGER segments_ai AFTER INSERT ON transcript_segments BEGIN
  INSERT INTO segments_fts(rowid, text) VALUES (new.id, new.text);
END;
CREATE TRIGGER segments_ad AFTER DELETE ON transcript_segments BEGIN
  INSERT INTO segments_fts(segments_fts, rowid, text) VALUES ('delete', old.id, old.text);
END;
CREATE TRIGGER segments_au AFTER UPDATE ON transcript_segments BEGIN
  INSERT INTO segments_fts(segments_fts, rowid, text) VALUES ('delete', old.id, old.text);
  INSERT INTO segments_fts(rowid, text) VALUES (new.id, new.text);
END;

-- Segments stored by M1 before this migration.
INSERT INTO segments_fts(segments_fts) VALUES ('rebuild');

INSERT INTO templates(id, name, instructions, builtin) VALUES
  ('builtin-1on1', '1:1',
   'This is a one-to-one conversation. Organise additions around the topics discussed, and make feedback, blockers, commitments and personal follow-ups explicit.', 1),
  ('builtin-client', 'Client meeting',
   'This is a meeting with a client. Capture the client''s needs and objections, the decisions taken, the commitments made by each side, prices or deadlines mentioned, and next steps.', 1),
  ('builtin-interview', 'Interview',
   'This is an interview. Capture the candidate''s answers to each question, concrete examples they gave, strengths and concerns that were voiced, and any follow-up question left open.', 1),
  ('builtin-team', 'Team meeting',
   'This is a team meeting. Capture the status of each topic, decisions taken, who owns which action, and risks or blockers raised.', 1);
