package app.interline.persistence;

/** Local preferences stored in the existing settings table. */
public final class SettingsStore {
    private static final String CONSENT_REMINDER = "consent.reminder_enabled";
    private final SqliteDatabase database;

    public SettingsStore(SqliteDatabase database) { this.database = database; }

    public String audioRetention() {
        return database.read(db -> db.sql("SELECT value FROM settings WHERE key = 'audio.retention'")
                .query(String.class).optional().orElse("7d"));
    }

    public void setAudioRetention(String value, long nowMs) {
        if (!java.util.Set.of("after_transcription", "7d", "30d", "forever").contains(value))
            throw new IllegalArgumentException("unknown audio retention");
        database.write(db -> db.sql("INSERT INTO settings(key,value,updated_at) VALUES ('audio.retention',?,?) "
                + "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at")
                .params(value, nowMs).update());
    }

    public boolean consentReminderEnabled() {
        return database.read(db -> db.sql("SELECT value FROM settings WHERE key = ?").param(CONSENT_REMINDER)
                .query(String.class).optional().map(value -> !"false".equals(value)).orElse(true));
    }

    public void setConsentReminderEnabled(boolean enabled, long nowMs) {
        database.write(db -> db.sql("INSERT INTO settings(key, value, updated_at) VALUES (?, ?, ?) "
                        + "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at")
                .params(CONSENT_REMINDER, Boolean.toString(enabled), nowMs).update());
    }
}
