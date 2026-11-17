package app.interline.persistence;

import app.interline.domain.MeetingStatus;
import app.interline.domain.StreamKind;
import app.interline.domain.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

/** SQL for the capture lifecycle: meetings, capture runs, speakers, final segments and timeline markers. */
public final class CaptureStore {
    private static final long UNSET = -1;
    private static final String UNKNOWN_AGENT = "unknown";
    private static final int FIRST_PROTOCOL = 1;
    private static final int SAMPLES_PER_MS = 16;
    private final SqliteDatabase database;

    public CaptureStore(SqliteDatabase database) { this.database = database; }

    public record MeetingRow(String id, String title, String status, Long startedAt, Long endedAt) { }

    public record TranscriptRow(long id, String stream, String seg, String speaker, long startMs, long endMs, String text) { }

    /** A stored final, as the echo heuristic sees it. */
    public record SegmentRow(String stream, String seg, long startMs, long endMs, String text, boolean suppressed) { }

    public record RecoveryJob(String id, String meetingId, StreamKind stream, String path, long offsetSamples) { }

    /** Insert the meeting, its two speakers and its first capture run, unless the meeting already exists. */
    public void createMeeting(String meetingId, long nowMs) {
        database.write(db -> {
            boolean exists = db.sql("SELECT count(*) FROM meetings WHERE id = ?").param(meetingId)
                    .query(Long.class).single() > 0;
            if (exists) return null;
            db.sql("INSERT INTO meetings(id, status, started_at, created_at, updated_at) VALUES (?, 'recording', ?, ?, ?)")
                    .params(meetingId, nowMs, nowMs, nowMs).update();
            for (StreamKind stream : StreamKind.values()) {
                db.sql("INSERT INTO speakers(id, meeting_id, source, label) VALUES (?, ?, ?, ?)")
                        .params(UuidV7.next(nowMs), meetingId, stream.wireName(), stream.speakerLabel()).update();
            }
            insertRun(db, meetingId, UNKNOWN_AGENT, UNKNOWN_AGENT, FIRST_PROTOCOL, nowMs);
            return null;
        });
    }

    public void beginRun(String meetingId, String agentName, String agentVersion, int protocol, long nowMs) {
        database.write(db -> { insertRun(db, meetingId, agentName, agentVersion, protocol, nowMs); return null; });
    }

    /** Record which agent serves the latest run (the run is created before the agent is known). */
    public void describeLatestRun(String meetingId, String agentName, String agentVersion, int protocol) {
        database.write(db -> db.sql("UPDATE capture_runs SET agent_name = ?, agent_version = ?, protocol = ? WHERE id = ?")
                .params(agentName, agentVersion, protocol, latestRunId(db, meetingId)).update());
    }

    /** Close the latest run if it is still open. */
    public void endRun(String meetingId, String endReason, long nowMs) {
        database.write(db -> db.sql("UPDATE capture_runs SET ended_at = ?, end_reason = ? WHERE id = ? AND ended_at IS NULL")
                .params(nowMs, endReason, latestRunId(db, meetingId)).update());
    }

    public void setStatus(String meetingId, MeetingStatus status, long nowMs) {
        boolean terminal = status == MeetingStatus.DONE || status == MeetingStatus.FAILED;
        database.write(db -> db.sql("UPDATE meetings SET status = ?, updated_at = ?, ended_at = CASE WHEN ? THEN ? ELSE ended_at END WHERE id = ?")
                .params(status.dbValue(), nowMs, terminal, nowMs, meetingId).update());
    }

    public void setLanguage(String meetingId, String language) {
        database.write(db -> db.sql("UPDATE meetings SET language = ? WHERE id = ?")
                .params(language, meetingId).update());
    }

    public String language(String meetingId) {
        return database.read(db -> db.sql("SELECT language FROM meetings WHERE id = ?")
                .param(meetingId).query(String.class).optional().orElse(null));
    }

    /** Highest durable seq per stream across every run of the meeting, or -1 when none. */
    public long lastSeq(String meetingId, StreamKind stream) {
        String column = "last_seq_" + stream.wireName();
        return database.read(db -> db.sql("SELECT COALESCE(MAX(" + column + "), " + UNSET + ") FROM capture_runs WHERE meeting_id = ?")
                .param(meetingId).query(Long.class).single());
    }

    public void updateLastSeq(String meetingId, StreamKind stream, long seq) {
        String column = "last_seq_" + stream.wireName();
        database.write(db -> db.sql("UPDATE capture_runs SET " + column + " = ? WHERE id = ?")
                .params(seq, latestRunId(db, meetingId)).update());
    }

    /** Highest end sample of any final segment so far, or 0 when none. */
    public long sttDoneUntil(String meetingId, StreamKind stream) {
        String column = "stt_done_" + stream.wireName();
        return database.read(db -> db.sql("SELECT COALESCE(MAX(" + column + "), 0) FROM capture_runs WHERE meeting_id = ?")
                .param(meetingId).query(Long.class).single());
    }

    /** Store a final segment once per (meeting, stream, seg). Returns false for a duplicate delivery. */
    public boolean insertFinal(String meetingId, StreamKind stream, String seg, long startSample, long endSample,
                               String text, String model, Double avgLogprob) {
        return insertFinal(meetingId, stream, seg, startSample, endSample, text, model, avgLogprob, false);
    }

    public boolean insertFinal(String meetingId, StreamKind stream, String seg, long startSample, long endSample,
                               String text, String model, Double avgLogprob, boolean recovered) {
        return database.write(db -> {
            String speakerId = db.sql("SELECT id FROM speakers WHERE meeting_id = ? AND source = ?")
                    .params(meetingId, stream.wireName()).query(String.class).optional()
                    .orElseThrow(() -> new IllegalStateException("unknown meeting " + meetingId));
            int inserted = db.sql("INSERT OR IGNORE INTO transcript_segments(meeting_id, speaker_id, agent_seg, start_ms, end_ms, text, avg_logprob, model, recovered) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(meetingId, speakerId, seg, startSample / SAMPLES_PER_MS,
                            endSample / SAMPLES_PER_MS, text, avgLogprob, model, recovered ? 1 : 0).update();
            if (inserted == 0) return false;
            String column = "stt_done_" + stream.wireName();
            db.sql("UPDATE capture_runs SET " + column + " = MAX(COALESCE(" + column + ", 0), ?) WHERE id = ?")
                    .params(endSample, latestRunId(db, meetingId)).update();
            return true;
        });
    }

    public void addGapMarker(String meetingId, long atMs, String detail) {
        database.write(db -> db.sql("INSERT INTO timeline_markers(meeting_id, at_ms, kind, detail) VALUES (?, ?, 'gap', ?)")
                .params(meetingId, atMs, detail).update());
    }

    public void addDeviceChangeMarker(String meetingId, long atMs, String detail) {
        database.write(db -> db.sql("INSERT INTO timeline_markers(meeting_id, at_ms, kind, detail) VALUES (?, ?, 'device_change', ?)")
                .params(meetingId, atMs, detail).update());
    }

    /** Stored finals of {@code stream} whose start is within {@code toleranceMs} of {@code aroundMs}. */
    public List<SegmentRow> segmentsNear(String meetingId, StreamKind stream, long aroundMs, long toleranceMs) {
        return database.read(db -> db.sql("SELECT s.source, t.agent_seg, t.start_ms, t.end_ms, t.text, t.suppressed "
                        + "FROM transcript_segments t JOIN speakers s ON s.id = t.speaker_id "
                        + "WHERE t.meeting_id = ? AND s.source = ? AND ABS(t.start_ms - ?) <= ?")
                .params(meetingId, stream.wireName(), aroundMs, toleranceMs)
                .query((rs, row) -> new SegmentRow(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4),
                        rs.getString(5), rs.getInt(6) != 0)).list());
    }

    /** The decision is kept with the row; suppressed rows stay in the database and only leave the public transcript. */
    public void markSuppressed(String meetingId, StreamKind stream, String seg) {
        database.write(db -> db.sql("UPDATE transcript_segments SET suppressed = 1 WHERE meeting_id = ? AND agent_seg = ? "
                        + "AND speaker_id = (SELECT id FROM speakers WHERE meeting_id = ? AND source = ?)")
                .params(meetingId, seg, meetingId, stream.wireName()).update());
    }

    public List<String> meetingsWithStatus(MeetingStatus... statuses) {
        String marks = String.join(",", java.util.Collections.nCopies(statuses.length, "?"));
        return database.read(db -> db.sql("SELECT id FROM meetings WHERE status IN (" + marks + ") ORDER BY created_at, id")
                .params((Object[]) java.util.Arrays.stream(statuses).map(MeetingStatus::dbValue).toArray(String[]::new))
                .query(String.class).list());
    }

    public void addRecoveryJob(RecoveryJob job) {
        database.write(db -> db.sql("INSERT OR IGNORE INTO recovery_jobs(id, meeting_id, stream, path, offset_samples) VALUES (?, ?, ?, ?, ?)")
                .params(job.id(), job.meetingId(), job.stream().wireName(), job.path(), job.offsetSamples()).update());
    }

    public List<RecoveryJob> pendingRecoveryJobs() {
        return database.read(db -> db.sql("SELECT id, meeting_id, stream, path, offset_samples FROM recovery_jobs WHERE done = 0 ORDER BY meeting_id, id")
                .query((rs, row) -> new RecoveryJob(rs.getString(1), rs.getString(2),
                        StreamKind.fromWireName(rs.getString(3)).orElseThrow(), rs.getString(4), rs.getLong(5))).list());
    }

    public void completeRecoveryJob(String jobId) {
        database.write(db -> db.sql("UPDATE recovery_jobs SET done = 1 WHERE id = ?").params(jobId).update());
    }

    public int gapMarkerCount(String meetingId) {
        return database.read(db -> db.sql("SELECT count(*) FROM timeline_markers WHERE meeting_id = ? AND kind = 'gap'")
                .param(meetingId).query(Integer.class).single());
    }

    public List<MeetingRow> listMeetings() {
        return database.read(db -> db.sql("SELECT id, title, status, started_at, ended_at FROM meetings "
                        + "WHERE deleted_at IS NULL ORDER BY created_at DESC, id DESC")
                .query((rs, row) -> new MeetingRow(rs.getString("id"), rs.getString("title"), rs.getString("status"),
                        nullableLong(rs.getObject("started_at")), nullableLong(rs.getObject("ended_at")))).list());
    }

    /** SQLite hands back Integer for small values and Long for large ones. */
    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    public Optional<MeetingRow> findMeeting(String meetingId) {
        return listMeetings().stream().filter(row -> row.id().equals(meetingId)).findFirst();
    }

    public List<TranscriptRow> transcript(String meetingId) {
        return database.read(db -> db.sql("SELECT t.id, s.source, t.agent_seg, s.label, t.start_ms, t.end_ms, t.text "
                        + "FROM transcript_segments t JOIN speakers s ON s.id = t.speaker_id "
                        + "WHERE t.meeting_id = ? AND t.suppressed = 0 ORDER BY t.start_ms, t.id")
                .param(meetingId)
                .query((rs, row) -> new TranscriptRow(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getString(7))).list());
    }

    private static void insertRun(JdbcClient db, String meetingId, String agentName,
                                  String agentVersion, int protocol, long nowMs) {
        db.sql("INSERT INTO capture_runs(id, meeting_id, agent_name, agent_version, protocol, started_at) VALUES (?, ?, ?, ?, ?, ?)")
                .params(UuidV7.next(nowMs), meetingId, agentName, agentVersion, protocol, nowMs).update();
    }

    private static String latestRunId(JdbcClient db, String meetingId) {
        return db.sql("SELECT id FROM capture_runs WHERE meeting_id = ? ORDER BY started_at DESC, rowid DESC LIMIT 1")
                .param(meetingId).query(String.class).optional()
                .orElseThrow(() -> new IllegalStateException("no capture run for meeting " + meetingId));
    }
}
