package app.interline.persistence;

/** Local preferences stored in the existing settings table. */
public final class SettingsStore {
    private static final String CONSENT_REMINDER = "consent.reminder_enabled";
    private final SqliteDatabase database;

    public SettingsStore(SqliteDatabase database) { this.database = database; }

    public String uiPreference(String key, String fallback) {
        return database.read(db -> db.sql("SELECT value FROM settings WHERE key = ?").param(key)
                .query(String.class).optional().orElse(fallback));
    }

    public void saveUiPreferences(java.util.Map<String, String> values, long nowMs) {
        if (!java.util.Set.of("onboarding.done", "ui.language").containsAll(values.keySet()))
            throw new IllegalArgumentException("unknown UI preference");
        database.write(db -> {
            values.forEach((key, value) -> db.sql("INSERT INTO settings(key,value,updated_at) VALUES (?,?,?) "
                    + "ON CONFLICT(key) DO UPDATE SET value=excluded.value, updated_at=excluded.updated_at")
                    .params(key, value, nowMs).update());
            return null;
        });
    }

    public String audioRetention() {
        return database.read(db -> db.sql("SELECT value FROM settings WHERE key = 'audio.retention'")
                .query(String.class).optional().orElse("7d"));
    }

    public void setAudioRetention(String value, long nowMs) {
        if (!java.util.Set.of("after_transcription", "7d", "30d", "forever").contains(value))
            throw new IllegalArgumentException("unknown audio retention");
        database.write(db -> {
            db.sql("INSERT INTO settings(key,value,updated_at) VALUES ('audio.retention',?,?) "
                    + "ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at")
                    .params(value, nowMs).update();
            if ("forever".equals(value)) {
                db.sql("UPDATE meetings SET audio_purge_at = NULL WHERE audio_purge_at IS NOT NULL AND audio_dir IS NOT NULL").update();
            } else if ("7d".equals(value) || "30d".equals(value)) {
                long delay = ("7d".equals(value) ? 7 : 30) * 86_400_000L;
                db.sql("UPDATE meetings SET audio_purge_at = ended_at + ? WHERE audio_purge_at IS NOT NULL "
                        + "AND audio_dir IS NOT NULL AND ended_at IS NOT NULL AND ended_at + ? > audio_purge_at")
                        .params(delay,delay).update();
            }
            return null;
        });
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
