package app.interline.persistence;

import app.interline.domain.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

/**
 * SQL for one AI pass: run records, snapshots of the user's notes, the AI blocks and action items an enhancement
 * adds, and the transcript lines a prompt is built from. The user's blocks are only ever read here.
 */
public final class AiStore {
    private static final String ORIGIN_AI = "ai";
    private static final String KIND_PARAGRAPH = "paragraph";
    private static final String CHILD_POSITION_SEPARATOR = "!";
    private static final String OTHER_POINT_POSITION_PREFIX = "~";
    private static final String STATUS_OPEN = "open";
    private static final int POSITION_DIGITS = 3;

    private final SqliteDatabase database;

    public AiStore(SqliteDatabase database) { this.database = database; }

    public record SegmentRow(long id, long startMs, String speaker, String text, boolean suppressed) { }

    public record RunStart(String meetingId, String kind, String providerId, String model, String promptVersion) { }

    public record RunEnd(String status, Integer inputTokens, Integer outputTokens, String error, Double costEstimateUsd) { }

    public record RunRow(String id, String kind, String providerId, String model, String promptVersion,
                         Integer inputTokens, Integer outputTokens, String status, Double costEstimateUsd) { }

    /** An addition under a user block ({@code parentId}) or, with a null parent, an "other point". */
    public record AdditionWrite(String parentId, String text, List<Long> sources) { }

    public record ActionWrite(String text, String owner, String dueDate, Long sourceSegmentId) { }

    public record ActionRow(String id, String text, String owner, String dueDate, String status, Long sourceSegmentId) { }

    public List<SegmentRow> segments(String meetingId) {
        return database.read(db -> db.sql("SELECT t.id, t.start_ms, COALESCE(s.label, '?') AS speaker, t.text, t.suppressed "
                        + "FROM transcript_segments t LEFT JOIN speakers s ON s.id = t.speaker_id "
                        + "WHERE t.meeting_id = ? ORDER BY t.start_ms, t.id").param(meetingId)
                .query((rs, row) -> new SegmentRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                        rs.getInt(5) != 0)).list());
    }

    public Optional<String> meetingLanguage(String meetingId) {
        return database.read(db -> db.sql("SELECT language FROM meetings WHERE id = ?").param(meetingId)
                .query(String.class).optional());
    }

    public Optional<String> meetingTitle(String meetingId) {
        return database.read(db -> db.sql("SELECT title FROM meetings WHERE id = ?").param(meetingId)
                .query(String.class).optional());
    }

    public Optional<String> meetingTemplate(String meetingId) {
        return database.read(db -> db.sql("SELECT template_id FROM meetings WHERE id = ? AND template_id IS NOT NULL")
                .param(meetingId).query(String.class).optional());
    }

    public void setMeetingTemplate(String meetingId, String templateId) {
        database.write(db -> db.sql("UPDATE meetings SET template_id = ? WHERE id = ?").params(templateId, meetingId).update());
    }

    public String writeSnapshot(String meetingId, String kind, String doc, long nowMs) {
        String id = UuidV7.next(nowMs);
        database.write(db -> db.sql("INSERT INTO note_snapshots(id, meeting_id, kind, doc, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(id, meetingId, kind, doc, nowMs).update());
        return id;
    }

    public String startRun(RunStart run, long nowMs) {
        String id = UuidV7.next(nowMs);
        database.write(db -> db.sql("INSERT INTO ai_runs(id, meeting_id, kind, provider_id, model, prompt_version, status, started_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'running', ?)")
                .params(id, run.meetingId(), run.kind(), run.providerId(), run.model(), run.promptVersion(), nowMs).update());
        return id;
    }

    public void finishRun(String runId, RunEnd end, long nowMs) {
        database.write(db -> db.sql("UPDATE ai_runs SET status = ?, input_tokens = ?, output_tokens = ?, error = ?, "
                        + "cost_estimate_usd = ?, finished_at = ? WHERE id = ?")
                .params(end.status(), end.inputTokens(), end.outputTokens(), end.error(), end.costEstimateUsd(), nowMs, runId)
                .update());
    }

    public List<RunRow> runs(String meetingId) {
        return database.read(db -> db.sql("SELECT id, kind, provider_id, model, prompt_version, input_tokens, output_tokens, "
                        + "status, cost_estimate_usd FROM ai_runs WHERE meeting_id = ? ORDER BY started_at, id").param(meetingId)
                .query((rs, row) -> new RunRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), nullableInt(rs, 6), nullableInt(rs, 7), rs.getString(8), nullableDouble(rs, 9))).list());
    }

    public List<ActionRow> actions(String meetingId) {
        return database.read(db -> db.sql("SELECT id, text, owner_label, due_date, status, source_segment_id FROM action_items "
                        + "WHERE meeting_id = ? ORDER BY created_at, rowid").param(meetingId)
                .query((rs, row) -> {
                    long source = rs.getLong(6);
                    Long sourceId = rs.wasNull() ? null : source;
                    return new ActionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), sourceId);
                }).list());
    }

    /**
     * Replaces what an earlier enhancement proposed (proposed additions, open action items) with this run's output
     * in one transaction. Accepted and edited additions stay. Additions whose user block vanished meanwhile are skipped.
     */
    public void applyEnhancement(String meetingId, String runId, List<AdditionWrite> additions, List<ActionWrite> actions,
                                 long nowMs) {
        database.write(db -> {
            db.sql("DELETE FROM note_blocks WHERE meeting_id = ? AND origin = ? AND ai_state = ?")
                    .params(meetingId, ORIGIN_AI, NoteStore.STATE_PROPOSED).update();
            db.sql("DELETE FROM action_items WHERE meeting_id = ? AND ai_run_id IS NOT NULL AND status = ?")
                    .params(meetingId, STATUS_OPEN).update();
            for (AdditionWrite addition : additions) insertAddition(db, meetingId, runId, addition, nowMs);
            for (ActionWrite action : actions) {
                db.sql("INSERT INTO action_items(id, meeting_id, text, owner_label, due_date, source_segment_id, ai_run_id, created_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                        .params(UuidV7.next(nowMs), meetingId, action.text(), action.owner(), action.dueDate(),
                                action.sourceSegmentId(), runId, nowMs).update();
            }
            return null;
        });
    }

    /** Removes every AI block and every AI action item of the meeting. The user's blocks are untouched. */
    public void undoEnhancement(String meetingId) {
        database.write(db -> {
            db.sql("DELETE FROM note_blocks WHERE meeting_id = ? AND origin = ?").params(meetingId, ORIGIN_AI).update();
            db.sql("DELETE FROM action_items WHERE meeting_id = ? AND ai_run_id IS NOT NULL").param(meetingId).update();
            return null;
        });
    }

    private static void insertAddition(JdbcClient db, String meetingId, String runId, AdditionWrite addition, long nowMs) {
        String position;
        if (addition.parentId() == null) {
            position = OTHER_POINT_POSITION_PREFIX + padded(count(db, "SELECT count(*) FROM note_blocks WHERE meeting_id = ? "
                    + "AND origin = 'ai' AND parent_id IS NULL", meetingId));
        } else {
            Optional<String> parentPosition = db.sql("SELECT position FROM note_blocks WHERE meeting_id = ? AND id = ? AND origin = 'user'")
                    .params(meetingId, addition.parentId()).query(String.class).optional();
            if (parentPosition.isEmpty()) return;
            position = parentPosition.get() + CHILD_POSITION_SEPARATOR
                    + padded(count(db, "SELECT count(*) FROM note_blocks WHERE meeting_id = ? AND parent_id = ?",
                    meetingId, addition.parentId()));
        }
        String id = UuidV7.next(nowMs);
        db.sql("INSERT INTO note_blocks(id, meeting_id, parent_id, position, kind, origin, content, plain_text, ai_run_id, "
                        + "ai_state, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(id, meetingId, addition.parentId(), position, KIND_PARAGRAPH, ORIGIN_AI, addition.text(),
                        addition.text(), runId, NoteStore.STATE_PROPOSED, nowMs, nowMs).update();
        for (long source : addition.sources()) {
            db.sql("INSERT OR IGNORE INTO block_sources(block_id, segment_id) VALUES (?, ?)").params(id, source).update();
        }
    }

    private static long count(JdbcClient db, String sql, Object... params) {
        return db.sql(sql).params(params).query(Long.class).single();
    }

    private static String padded(long index) {
        return String.format("%0" + POSITION_DIGITS + "d", index);
    }

    private static Integer nullableInt(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Double nullableDouble(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
