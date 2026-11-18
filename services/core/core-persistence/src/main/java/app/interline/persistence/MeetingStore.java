package app.interline.persistence;

import app.interline.domain.BriefMatcher;
import app.interline.domain.BriefMatcher.Candidate;
import app.interline.domain.BriefMatcher.Subject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** SQL for draft meetings and for what the brief reads: related meetings, user notes and open actions. */
public final class MeetingStore {
    private static final String ICS = "ics";

    private final SqliteDatabase database;

    public MeetingStore(SqliteDatabase database) { this.database = database; }

    public record MeetingInfo(String id, String title, String status, String calendarEventId, Long startedAt, long createdAt) { }

    public record ActionRow(String id, String meetingId, String text, String ownerLabel, String dueDate) { }

    public void createDraft(String id, String title, String calendarEventId, long nowMs) {
        database.write(db -> db.sql("INSERT INTO meetings(id, title, status, calendar_event_id, created_at, updated_at) "
                + "VALUES (?, ?, 'draft', ?, ?, ?)").params(id, title, calendarEventId, nowMs, nowMs).update());
    }

    public Optional<MeetingInfo> find(String meetingId) {
        return database.read(db -> db.sql("SELECT id, title, status, calendar_event_id, started_at, created_at FROM meetings "
                        + "WHERE id = ? AND deleted_at IS NULL").param(meetingId)
                .query((rs, row) -> new MeetingInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        nullableLong(rs.getObject(5)), rs.getLong(6))).optional());
    }

    public Optional<String> findByCalendarEvent(String eventId) {
        return database.read(db -> db.sql("SELECT id FROM meetings WHERE calendar_event_id = ? AND deleted_at IS NULL "
                + "ORDER BY created_at, rowid LIMIT 1").param(eventId).query(String.class).optional());
    }

    public Optional<Subject> subject(String meetingId) {
        return database.read(db -> {
            Optional<MeetingInfo> meeting = find(meetingId);
            if (meeting.isEmpty()) return Optional.empty();
            Set<String> people = new HashSet<>(db.sql("SELECT person_id FROM meeting_participants WHERE meeting_id = ?")
                    .param(meetingId).query(String.class).list());
            String series = db.sql("SELECT external_id FROM calendar_events WHERE id = ? AND source = ?")
                    .params(meeting.get().calendarEventId(), ICS).query(String.class).optional()
                    .map(BriefMatcher::seriesUid).orElse(null);
            return Optional.of(new Subject(meetingId, meeting.get().title(), people, series));
        });
    }

    /** Other live meetings dated before {@code beforeMs}, most recent first. Date: started, else event start, else created. */
    public List<Candidate> candidates(String excludeMeetingId, long beforeMs, int limit) {
        return database.read(db -> {
            record Row(String id, String title, long date, String source, String externalId) { }
            List<Row> rows = db.sql("SELECT m.id, m.title, COALESCE(m.started_at, ce.starts_at, m.created_at) AS d, "
                            + "ce.source, ce.external_id FROM meetings m LEFT JOIN calendar_events ce ON ce.id = m.calendar_event_id "
                            + "WHERE m.deleted_at IS NULL AND m.id <> ? AND d < ? ORDER BY d DESC, m.id LIMIT ?")
                    .params(excludeMeetingId, beforeMs, limit)
                    .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5)))
                    .list();
            Map<String, Set<String>> people = participantsOf(rows.stream().map(Row::id).toList());
            List<Candidate> candidates = new ArrayList<>();
            for (Row row : rows) {
                String series = ICS.equals(row.source()) ? BriefMatcher.seriesUid(row.externalId()) : null;
                candidates.add(new Candidate(row.id(), row.title(), row.date(), people.getOrDefault(row.id(), Set.of()), series));
            }
            return candidates;
        });
    }

    private Map<String, Set<String>> participantsOf(List<String> meetingIds) {
        Map<String, Set<String>> byMeeting = new HashMap<>();
        if (meetingIds.isEmpty()) return byMeeting;
        database.read(db -> db.sql("SELECT meeting_id, person_id FROM meeting_participants WHERE meeting_id IN ("
                        + placeholders(meetingIds.size()) + ")").params(meetingIds.toArray())
                .query((rs, n) -> {
                    byMeeting.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
                    return null;
                }).list());
        return byMeeting;
    }

    /** What the user wrote, in order. AI additions are never part of a brief excerpt. */
    public List<String> userNotes(String meetingId, int limit) {
        return database.read(db -> db.sql("SELECT plain_text FROM note_blocks WHERE meeting_id = ? AND origin = 'user' "
                + "ORDER BY position, rowid_fts LIMIT ?").params(meetingId, limit).query(String.class).list());
    }

    public List<ActionRow> openActions(List<String> meetingIds) {
        if (meetingIds.isEmpty()) return List.of();
        return database.read(db -> db.sql("SELECT id, meeting_id, text, owner_label, due_date FROM action_items "
                        + "WHERE status = 'open' AND meeting_id IN (" + placeholders(meetingIds.size()) + ") "
                        + "ORDER BY meeting_id, created_at, id").params(meetingIds.toArray())
                .query((rs, n) -> new ActionRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)))
                .list());
    }

    private static String placeholders(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> "?").collect(Collectors.joining(","));
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
