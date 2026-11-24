ALTER TABLE meetings ADD COLUMN consent_ack_at INTEGER;

INSERT OR IGNORE INTO settings(key, value, updated_at)
VALUES ('consent.reminder_enabled', 'true', 0);
