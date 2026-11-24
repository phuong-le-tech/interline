package app.interline.persistence;

import app.interline.domain.BriefMatcher;
import app.interline.domain.BriefMatcher.Candidate;
import app.interline.domain.BriefMatcher.Subject;

import app.interline.persistence.CalendarStore.EventRow;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
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

    /** A participant as typed or as listed on an event; either part may be blank but not both. */
    public record PersonInput(String name, String email) { }

    public record DraftResult(String id, boolean created) { }

    private static final int MAX_TITLE_CHARS = 500;

    public record ActionRow(String id, String meetingId, String text, String ownerLabel, String dueDate) { }

    public void createDraft(String id, String title, String calendarEventId, long nowMs) {
        database.write(db -> db.sql("INSERT INTO meetings(id, title, status, calendar_event_id, created_at, updated_at) "
                + "VALUES (?, ?, 'draft', ?, ?, ?)").params(id, title, calendarEventId, nowMs, nowMs).update());
    }

    /** One transaction: the draft, its people and its links, or nothing. */
    public void createManual(String meetingId, String title, List<PersonInput> people, long nowMs) {
        database.write(db -> {
            insertDraft(db, meetingId, title, null, nowMs);
            link(db, meetingId, people, nowMs);
            return null;
        });
    }

    /**
     * One transaction: the event, an existing meeting for it (returned as is), or a new draft titled like the event
     * with the event's attendees as participants. A failure leaves nothing behind, so a retry builds the whole meeting.
     */
    public Optional<DraftResult> createFromEvent(String eventId, String newMeetingId, long nowMs) {
        return database.write(db -> {
            Optional<EventRow> event = CalendarStore.eventById(db, eventId);
            if (event.isEmpty()) return Optional.empty();
            Optional<String> existing = db.sql("SELECT id FROM meetings WHERE calendar_event_id = ? AND deleted_at IS NULL "
                    + "ORDER BY created_at, rowid LIMIT 1").param(eventId).query(String.class).optional();
            if (existing.isPresent()) return Optional.of(new DraftResult(existing.get(), false));
            String title = event.get().title().strip();
            insertDraft(db, newMeetingId, title.length() > MAX_TITLE_CHARS ? title.substring(0, MAX_TITLE_CHARS) : title, eventId, nowMs);
            link(db, newMeetingId, event.get().attendees().stream().map(a -> new PersonInput(a.name(), a.email())).toList(), nowMs);
            return Optional.of(new DraftResult(newMeetingId, true));
        });
    }

    /** One transaction: false (and nothing changed) for an unknown or deleted meeting; otherwise the exact new list. */
    public boolean replaceParticipants(String meetingId, List<PersonInput> people, long nowMs) {
        return replaceParticipantsAndList(meetingId, people, nowMs).isPresent();
    }

    /**
     * Same, returning the resulting participants read inside the same transaction, so a concurrent change can never
     * make a caller see somebody else's list.
     */
    public Optional<List<CalendarStore.PersonRow>> replaceParticipantsAndList(String meetingId, List<PersonInput> people, long nowMs) {
        return database.write(db -> {
            boolean exists = db.sql("SELECT count(*) FROM meetings WHERE id = ? AND deleted_at IS NULL").param(meetingId)
                    .query(Long.class).single() > 0;
            if (!exists) return Optional.empty();
            db.sql("DELETE FROM meeting_participants WHERE meeting_id = ?").param(meetingId).update();
            link(db, meetingId, people, nowMs);
            return Optional.of(CalendarStore.participantsOf(db, meetingId));
        });
    }

    private static void insertDraft(JdbcClient db, String id, String title, String calendarEventId, long nowMs) {
        db.sql("INSERT INTO meetings(id, title, status, calendar_event_id, created_at, updated_at) VALUES (?, ?, 'draft', ?, ?, ?)")
                .params(id, title, calendarEventId, nowMs, nowMs).update();
    }

    /** People rules: by email when there is one (an address without @ counts as none), else by name; neither: skipped. */
    private static void link(JdbcClient db, String meetingId, List<PersonInput> people, long nowMs) {
        Set<String> ids = new LinkedHashSet<>();
        for (PersonInput input : people) {
            String email = input.email() == null || !input.email().contains("@") ? null : input.email();
            CalendarStore.resolvePerson(db, input.name(), email, nowMs).ifPresent(person -> ids.add(person.id()));
        }
        for (String personId : ids) {
            db.sql("INSERT OR IGNORE INTO meeting_participants(meeting_id, person_id) VALUES (?, ?)").params(meetingId, personId).update();
        }
    }

    public Optional<MeetingInfo> find(String meetingId) {
        return database.read(db -> db.sql("SELECT id, title, status, calendar_event_id, started_at, created_at FROM meetings "
                        + "WHERE id = ? AND deleted_at IS NULL").param(meetingId)
                .query((rs, row) -> new MeetingInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        nullableLong(rs.getObject(5)), rs.getLong(6))).optional());
    }

    public boolean acknowledgeConsent(String meetingId, long nowMs) {
        return database.write(db -> db.sql("UPDATE meetings SET consent_ack_at = COALESCE(consent_ack_at, ?), "
                        + "updated_at = CASE WHEN consent_ack_at IS NULL THEN ? ELSE updated_at END "
                        + "WHERE id = ? AND deleted_at IS NULL")
                .params(nowMs, nowMs, meetingId).update() > 0);
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
            String series = db.sql("SELECT source, external_id FROM calendar_events WHERE id = ?")
                    .param(meeting.get().calendarEventId()).query((rs, n) -> seriesKey(rs.getString(1), rs.getString(2)))
                    .optional().orElse(null);
            return Optional.of(new Subject(meetingId, meeting.get().title(), people, series));
        });
    }

    /** Other live meetings dated before {@code beforeMs}, most recent first. Date: started, else event start, else created. */
    public List<Candidate> candidates(String excludeMeetingId, long beforeMs, int limit) {
        return candidates(excludeMeetingId, beforeMs, limit, 0);
    }

    /** One page of {@link #candidates(String, long, int)}: skips the first {@code offset} rows of the same ordering. */
    public List<Candidate> candidates(String excludeMeetingId, long beforeMs, int limit, int offset) {
        return database.read(db -> {
            record Row(String id, String title, long date, String source, String externalId) { }
            List<Row> rows = db.sql("SELECT m.id, m.title, COALESCE(m.started_at, ce.starts_at, m.created_at) AS d, "
                            + "ce.source, ce.external_id FROM meetings m LEFT JOIN calendar_events ce ON ce.id = m.calendar_event_id "
                            + "WHERE m.deleted_at IS NULL AND m.id <> ? AND d < ? ORDER BY d DESC, m.id LIMIT ? OFFSET ?")
                    .params(excludeMeetingId, beforeMs, limit, offset)
                    .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getString(5)))
                    .list();
            Map<String, Set<String>> people = participantsOf(rows.stream().map(Row::id).toList());
            List<Candidate> candidates = new ArrayList<>();
            for (Row row : rows) {
                candidates.add(new Candidate(row.id(), row.title(), row.date(), people.getOrDefault(row.id(), Set.of()),
                        seriesKey(row.source(), row.externalId())));
            }
            return candidates;
        });
    }

    /**
     * The series an event belongs to: its UID within its source. ICS keeps the bare UID; any other source is prefixed so
     * that the same UID in two sources never matches. Null when the event has no external id.
     */
    private static String seriesKey(String source, String externalId) {
        String uid = BriefMatcher.seriesUid(externalId);
        if (uid == null || source == null) return null;
        return ICS.equals(source) ? uid : source + "\u0000" + uid;
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
