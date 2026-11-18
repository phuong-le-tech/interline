package app.interline.persistence;

import app.interline.domain.Attendee;
import app.interline.domain.CalendarEvent;
import app.interline.domain.UuidV7;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * SQL for calendar events, people and meeting participants. A window is every event that overlaps
 * {@code [from, to)}: {@code starts_at < to} and an end strictly after {@code from} (a zero-length event ends one
 * millisecond after it starts).
 */
public final class CalendarStore {
    private static final String ICS = "ics";
    private static final String OVERLAPS = "starts_at < ? AND max(ends_at, starts_at + 1) > ? ";
    private static final String EVENT_COLUMNS =
            "SELECT id, source, external_id, title, starts_at, ends_at, conference_url, attendees, synced_at FROM calendar_events ";
    private static final String PERSON_COLUMNS = "SELECT id, display_name, email, created_at FROM people ";

    private final SqliteDatabase database;

    public CalendarStore(SqliteDatabase database) { this.database = database; }

    public record EventRow(String id, String source, String externalId, String title, long startsAt, long endsAt,
                           String conferenceUrl, List<Attendee> attendees, Long syncedAt) { }

    public record PersonRow(String id, String displayName, String email, long createdAt) { }

    public record SyncCounts(int upserted, int removed) { }

    /** One transaction: upsert the events, drop the absent ones of the window, keep the people in step. */
    public SyncCounts replaceIcsWindow(List<CalendarEvent> events, long windowStartMs, long windowEndMs, long nowMs) {
        return replaceIcsWindow(events, windowStartMs, windowEndMs, nowMs, true);
    }

    /**
     * Same, but with {@code deleteAbsent = false} nothing is ever removed: used when the snapshot is only a partial
     * view of the feed, which must not wipe what is already stored.
     */
    public SyncCounts replaceIcsWindow(List<CalendarEvent> events, long windowStartMs, long windowEndMs, long nowMs,
                                       boolean deleteAbsent) {
        return database.write(db -> {
            int upserted = 0;
            Set<String> present = new HashSet<>();
            for (CalendarEvent event : events) {
                present.add(event.externalId());
                if (upsertEvent(db, event, nowMs)) upserted++;
                for (Attendee attendee : event.attendees()) {
                    if (attendee.email() != null && !attendee.email().isBlank()) {
                        upsertPerson(db, attendee.name(), attendee.email(), nowMs);
                    }
                }
            }
            int removed = 0;
            if (!deleteAbsent) return new SyncCounts(upserted, 0);
            record WindowRow(String id, String externalId) { }
            List<WindowRow> inWindow = db.sql("SELECT id, external_id FROM calendar_events WHERE source = ? AND " + OVERLAPS)
                    .params(ICS, windowEndMs, windowStartMs)
                    .query((rs, rowNum) -> new WindowRow(rs.getString(1), rs.getString(2))).list();
            for (WindowRow row : inWindow) {
                if (row.externalId() != null && present.contains(row.externalId())) continue;
                removed += db.sql("DELETE FROM calendar_events WHERE id = ?").param(row.id()).update();
            }
            return new SyncCounts(upserted, removed);
        });
    }

    /** True when a row was inserted or its content changed. */
    private static boolean upsertEvent(JdbcClient db, CalendarEvent event, long nowMs) {
        String attendees = AttendeesJson.encode(event.attendees());
        EventRow current = db.sql(EVENT_COLUMNS + "WHERE source = ? AND external_id = ?")
                .params(ICS, event.externalId()).query(CalendarStore::event).optional().orElse(null);
        if (current == null) {
            db.sql("INSERT INTO calendar_events(id, source, external_id, title, starts_at, ends_at, conference_url, attendees, synced_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(UuidV7.next(nowMs), ICS, event.externalId(), event.title(), event.startsAt(), event.endsAt(),
                            event.conferenceUrl(), attendees, nowMs).update();
            return true;
        }
        boolean changed = !Objects.equals(current.title(), event.title()) || current.startsAt() != event.startsAt()
                || current.endsAt() != event.endsAt() || !Objects.equals(current.conferenceUrl(), event.conferenceUrl())
                || !current.attendees().equals(event.attendees());
        db.sql("UPDATE calendar_events SET title = ?, starts_at = ?, ends_at = ?, conference_url = ?, attendees = ?, synced_at = ? "
                        + "WHERE id = ?")
                .params(event.title(), event.startsAt(), event.endsAt(), event.conferenceUrl(), attendees, nowMs, current.id()).update();
        return changed;
    }

    public List<EventRow> listEvents(long fromMs, long toMs) {
        return database.read(db -> db.sql(EVENT_COLUMNS + "WHERE " + OVERLAPS + "ORDER BY starts_at, id")
                .params(toMs, fromMs).query(CalendarStore::event).list());
    }

    /** Removes every ICS event; meetings that pointed at one keep existing with no event. */
    public int deleteIcsEvents() {
        return database.write(db -> db.sql("DELETE FROM calendar_events WHERE source = ?").param(ICS).update());
    }

    public PersonRow upsertPerson(String displayName, String email, long nowMs) {
        return database.write(db -> upsertPerson(db, displayName, email, nowMs));
    }

    private static PersonRow upsertPerson(JdbcClient db, String displayName, String email, long nowMs) {
        if (email == null || email.isBlank()) throw new IllegalArgumentException("a person needs an email");
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        String name = displayName == null ? "" : displayName.strip();
        PersonRow existing = db.sql(PERSON_COLUMNS + "WHERE email = ?").param(normalized).query(CalendarStore::person)
                .optional().orElse(null);
        if (existing == null) {
            String id = UuidV7.next(nowMs);
            db.sql("INSERT INTO people(id, display_name, email, created_at) VALUES (?, ?, ?, ?)")
                    .params(id, name, normalized, nowMs).update();
            return new PersonRow(id, name, normalized, nowMs);
        }
        if (existing.displayName().isBlank() && !name.isEmpty()) {
            db.sql("UPDATE people SET display_name = ? WHERE id = ?").params(name, existing.id()).update();
            return new PersonRow(existing.id(), name, existing.email(), existing.createdAt());
        }
        return existing;
    }

    public Optional<EventRow> findEvent(String eventId) {
        return database.read(db -> eventById(db, eventId));
    }

    static Optional<EventRow> eventById(JdbcClient db, String eventId) {
        return db.sql(EVENT_COLUMNS + "WHERE id = ?").param(eventId).query(CalendarStore::event).optional();
    }

    /**
     * The person for a name and an optional email. With an email: by lower-cased email. Without: an existing
     * email-less person with the same name (trimmed, case-insensitive), else a new one. Nothing to go on: empty.
     */
    public Optional<PersonRow> resolvePerson(String name, String email, long nowMs) {
        return database.write(db -> resolvePerson(db, name, email, nowMs));
    }

    /** Same rules inside a transaction the caller already owns. */
    static Optional<PersonRow> resolvePerson(JdbcClient db, String name, String email, long nowMs) {
        String cleanName = name == null ? "" : name.strip();
        boolean hasEmail = email != null && !email.isBlank();
        if (!hasEmail && cleanName.isEmpty()) return Optional.empty();
        if (hasEmail) return Optional.of(upsertPerson(db, cleanName, email, nowMs));
        String wanted = cleanName.toLowerCase(Locale.ROOT);
        for (PersonRow person : db.sql(PERSON_COLUMNS + "WHERE email IS NULL ORDER BY created_at, rowid")
                .query(CalendarStore::person).list()) {
            if (person.displayName().strip().toLowerCase(Locale.ROOT).equals(wanted)) return Optional.of(person);
        }
        String id = UuidV7.next(nowMs);
        db.sql("INSERT INTO people(id, display_name, email, created_at) VALUES (?, ?, NULL, ?)").params(id, cleanName, nowMs).update();
        return Optional.of(new PersonRow(id, cleanName, null, nowMs));
    }

    /** One transaction: the meeting's participants become exactly these people. */
    public void replaceParticipants(String meetingId, List<String> personIds) {
        database.write(db -> {
            db.sql("DELETE FROM meeting_participants WHERE meeting_id = ?").param(meetingId).update();
            for (String personId : personIds) {
                db.sql("INSERT OR IGNORE INTO meeting_participants(meeting_id, person_id) VALUES (?, ?)")
                        .params(meetingId, personId).update();
            }
            return null;
        });
    }

    public List<PersonRow> listPeople() {
        return database.read(db -> db.sql(PERSON_COLUMNS + "ORDER BY created_at, rowid").query(CalendarStore::person).list());
    }

    public void addParticipant(String meetingId, String personId) {
        database.write(db -> db.sql("INSERT OR IGNORE INTO meeting_participants(meeting_id, person_id) VALUES (?, ?)")
                .params(meetingId, personId).update());
    }

    public List<PersonRow> listParticipants(String meetingId) {
        return database.read(db -> participantsOf(db, meetingId));
    }

    static List<PersonRow> participantsOf(JdbcClient db, String meetingId) {
        return db.sql("SELECT p.id, p.display_name, p.email, p.created_at FROM people p "
                        + "JOIN meeting_participants mp ON mp.person_id = p.id WHERE mp.meeting_id = ? ORDER BY p.created_at, p.rowid")
                .param(meetingId).query(CalendarStore::person).list();
        return database.read(db -> db.sql("SELECT p.id, p.display_name, p.email, p.created_at FROM people p "
                        + "JOIN meeting_participants mp ON mp.person_id = p.id WHERE mp.meeting_id = ? ORDER BY p.created_at, p.rowid")
                .param(meetingId).query(CalendarStore::person).list());
    }

    private static EventRow event(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long synced = rs.getLong(9);
        Long syncedAt = rs.wasNull() ? null : synced;
        return new EventRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getLong(6),
                rs.getString(7), AttendeesJson.decode(rs.getString(8)), syncedAt);
    }

    private static PersonRow person(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PersonRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4));
    }
}
