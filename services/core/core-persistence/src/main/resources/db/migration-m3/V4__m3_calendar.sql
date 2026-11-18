-- M3 calendar migration (docs/ARCHITECTURE.md section 13), additive only: no existing column changes.
-- Kept in its own Flyway location like V2 and V3.

CREATE TABLE calendar_events (
  id TEXT PRIMARY KEY,
  source TEXT NOT NULL CHECK (source IN ('agent','ics','manual')),
  external_id TEXT,
  title TEXT NOT NULL,
  starts_at INTEGER NOT NULL,
  ends_at INTEGER NOT NULL,
  conference_url TEXT,
  -- JSON text: [{"name":...,"email":...}]. Attendees without an email live only here.
  attendees TEXT,
  synced_at INTEGER,
  UNIQUE (source, external_id)
);
CREATE INDEX calendar_events_starts_at ON calendar_events (starts_at);

CREATE TABLE people (
  id TEXT PRIMARY KEY,
  display_name TEXT NOT NULL,
  -- Stored lower-cased by the application.
  email TEXT UNIQUE,
  created_at INTEGER NOT NULL
);

CREATE TABLE meeting_participants (
  meeting_id TEXT NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
  person_id TEXT NOT NULL REFERENCES people(id) ON DELETE CASCADE,
  PRIMARY KEY (meeting_id, person_id)
);

ALTER TABLE meetings ADD COLUMN calendar_event_id TEXT REFERENCES calendar_events(id) ON DELETE SET NULL;
