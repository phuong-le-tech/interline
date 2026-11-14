-- WAL is a property of the database file; setting it here makes every connection, including tools, see it.
PRAGMA journal_mode = WAL;

-- V1 is the M0 subset of docs/ARCHITECTURE.md section 13: capture and transcript storage only.
-- Notes, calendar, AI and provider tables arrive in V2+; meetings.calendar_event_id and
-- meetings.template_id are added by the migration that creates their tables.

CREATE TABLE meetings (
  id TEXT PRIMARY KEY,
  title TEXT NOT NULL DEFAULT '',
  status TEXT NOT NULL CHECK (status IN ('draft','recording','degraded','processing','done','failed')),
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

CREATE TABLE capture_runs (
  id TEXT PRIMARY KEY,
  meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  agent_name TEXT NOT NULL,
  agent_version TEXT NOT NULL,
  protocol INTEGER NOT NULL,
  started_at INTEGER NOT NULL,
  ended_at INTEGER,
  end_reason TEXT CHECK (end_reason IN ('stopped','agent_crash','core_crash','app_crash','paused')),
  last_seq_mic INTEGER,
  last_seq_system INTEGER,
  stt_done_mic INTEGER,
  stt_done_system INTEGER
);
CREATE INDEX capture_runs_meeting ON capture_runs (meeting_id, started_at);

CREATE TABLE speakers (
  id TEXT PRIMARY KEY,
  meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  source TEXT NOT NULL CHECK (source IN ('mic','system','import')),
  label TEXT NOT NULL
);
CREATE INDEX speakers_meeting ON speakers (meeting_id, source);

CREATE TABLE transcript_segments (
  id INTEGER PRIMARY KEY,
  meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  speaker_id TEXT REFERENCES speakers(id) ON DELETE SET NULL,
  agent_seg TEXT NOT NULL,
  start_ms INTEGER NOT NULL,
  end_ms INTEGER NOT NULL,
  text TEXT NOT NULL,
  text_edited TEXT,
  avg_logprob REAL,
  model TEXT NOT NULL,
  suppressed INTEGER NOT NULL DEFAULT 0,
  recovered INTEGER NOT NULL DEFAULT 0,
  UNIQUE (meeting_id, speaker_id, agent_seg)
);
CREATE INDEX transcript_segments_timeline ON transcript_segments (meeting_id, start_ms);

CREATE TABLE timeline_markers (
  id INTEGER PRIMARY KEY,
  meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  at_ms INTEGER NOT NULL,
  kind TEXT NOT NULL CHECK (kind IN ('gap','device_change','pause','resume')),
  detail TEXT
);
CREATE INDEX timeline_markers_meeting ON timeline_markers (meeting_id, at_ms);

CREATE TABLE settings (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);
