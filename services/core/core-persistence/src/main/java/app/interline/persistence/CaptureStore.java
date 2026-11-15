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

    public record TranscriptRow(String stream, String seg, String speaker, long startMs, long endMs, String text) { }

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
        return database.write(db -> {
            String speakerId = db.sql("SELECT id FROM speakers WHERE meeting_id = ? AND source = ?")
                    .params(meetingId, stream.wireName()).query(String.class).optional()
                    .orElseThrow(() -> new IllegalStateException("unknown meeting " + meetingId));
            int inserted = db.sql("INSERT OR IGNORE INTO transcript_segments(meeting_id, speaker_id, agent_seg, start_ms, end_ms, text, avg_logprob, model) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(meetingId, speakerId, seg, startSample / SAMPLES_PER_MS,
                            endSample / SAMPLES_PER_MS, text, avgLogprob, model).update();
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

    public List<MeetingRow> listMeetings() {
        return database.read(db -> db.sql("SELECT id, title, status, started_at, ended_at FROM meetings "
                        + "WHERE deleted_at IS NULL ORDER BY created_at DESC, id DESC")
                .query((rs, row) -> new MeetingRow(rs.getString("id"), rs.getString("title"), rs.getString("status"),
                        (Long) rs.getObject("started_at"), (Long) rs.getObject("ended_at"))).list());
    }

    public Optional<MeetingRow> findMeeting(String meetingId) {
        return listMeetings().stream().filter(row -> row.id().equals(meetingId)).findFirst();
    }

    public List<TranscriptRow> transcript(String meetingId) {
        return database.read(db -> db.sql("SELECT s.source, t.agent_seg, s.label, t.start_ms, t.end_ms, t.text "
                        + "FROM transcript_segments t JOIN speakers s ON s.id = t.speaker_id "
                        + "WHERE t.meeting_id = ? ORDER BY t.start_ms, t.id")
                .param(meetingId)
                .query((rs, row) -> new TranscriptRow(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getString(6))).list());
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
