package app.interline.persistence;

import java.util.Optional;

/** A derived cache; this store never writes notes. */
public final class BriefStore {
    private final SqliteDatabase database;
    public BriefStore(SqliteDatabase database) { this.database = database; }
    public record Cached(String text, String inputHash, String aiRunId, long createdAt) { }

    public Optional<Cached> find(String meetingId) {
        return database.read(db -> db.sql("SELECT text, input_hash, ai_run_id, created_at FROM briefs WHERE meeting_id = ?")
                .param(meetingId).query((rs, n) -> new Cached(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4))).optional());
    }

    public void save(String meetingId, Cached cache) {
        database.write(db -> db.sql("INSERT INTO briefs(id, meeting_id, text, input_hash, ai_run_id, created_at) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT(meeting_id) DO UPDATE SET text = excluded.text, input_hash = excluded.input_hash, "
                        + "ai_run_id = excluded.ai_run_id, created_at = excluded.created_at")
                .params(meetingId, meetingId, cache.text(), cache.inputHash(), cache.aiRunId(), cache.createdAt()).update());
    }
}
