-- Existing text stays untouched. A durable tombstone makes partial file deletion retryable.
ALTER TABLE meetings ADD COLUMN deletion_started_at INTEGER;
CREATE INDEX meetings_audio_due ON meetings(audio_purge_at) WHERE audio_purge_at IS NOT NULL;
CREATE INDEX meetings_trash_due ON meetings(deleted_at) WHERE deleted_at IS NOT NULL;
INSERT OR IGNORE INTO settings(key,value,updated_at) VALUES ('audio.retention','7d',0);
-- Existing audio uses the same canonical meeting directory, even though older cores left audio_dir empty.
-- Never schedule existing audio for deletion on upgrade. Only future meeting ends create deadlines.
UPDATE meetings SET audio_dir = 'audio/' || id
WHERE started_at IS NOT NULL;
