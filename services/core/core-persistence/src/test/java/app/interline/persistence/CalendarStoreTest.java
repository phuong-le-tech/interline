package app.interline.persistence;

import app.interline.domain.Attendee;
import app.interline.domain.CalendarEvent;
import app.interline.persistence.CalendarStore.EventRow;
import app.interline.persistence.CalendarStore.PersonRow;
import app.interline.persistence.CalendarStore.SyncCounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** M3 persistence: CalendarStore (AC4 and AC5 store part). Window used by most tests: [10_000, 20_000). */
class CalendarStoreTest {
    private static final long WS = 10_000;
    private static final long WE = 20_000;
    private static final long NOW = 5_000_000;

    @TempDir
    Path directory;

    private SqliteDatabase database;
    private CalendarStore store;

    @BeforeEach
    void open() {
        database = SqliteDatabase.open(directory.resolve("db.sqlite"));
        store = new CalendarStore(database);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private static CalendarEvent ev(String externalId, String title, long start, long end, Attendee... attendees) {
        return new CalendarEvent(externalId, title, start, end, null, List.of(attendees));
    }

    private static Attendee att(String name, String email) {
        return new Attendee(name, email);
    }

    private static List<CalendarEvent> list(CalendarEvent... events) {
        return Arrays.asList(events);
    }

    private List<String> externalIds(String source) {
        return database.read(j -> j.sql("SELECT external_id FROM calendar_events WHERE source = ? ORDER BY external_id")
                .param(source).query(String.class).list());
    }

    private String idOf(String externalId) {
        return database.read(j -> j.sql("SELECT id FROM calendar_events WHERE source = 'ics' AND external_id = ?")
                .param(externalId).query(String.class).single());
    }

    private long count(String table) {
        return database.read(j -> j.sql("SELECT count(*) FROM " + table).query(Long.class).single());
    }

    private void insertRaw(String id, String source, String externalId, long start, long end) {
        database.write(j -> j.sql("INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at,synced_at) "
                + "VALUES (?,?,?,'raw',?,?,1)").params(id, source, externalId, start, end).update());
    }

    private void insertMeeting(String id, String calendarEventId) {
        database.write(j -> j.sql("INSERT INTO meetings(id,status,created_at,updated_at,calendar_event_id) "
                + "VALUES (?, 'done', 1, 1, ?)").params(id, calendarEventId).update());
    }

    private String meetingEvent(String meetingId) {
        return database.read(j -> j.sql("SELECT calendar_event_id FROM meetings WHERE id = ?")
                .param(meetingId).query(String.class).optional().orElse(null));
    }

    // ---- AC5: replaceIcsWindow ----

    @Test
    void AC5_firstSyncInsertsIcsEventsWithSyncedAt() {
        SyncCounts counts = store.replaceIcsWindow(list(
                new CalendarEvent("u1", "Standup", 11_000, 12_000, "https://meet.example.com/x", List.of()),
                ev("u2", "Review", 13_000, 14_000)), WS, WE, NOW);

        assertThat(counts).isEqualTo(new SyncCounts(2, 0));
        List<EventRow> rows = store.listEvents(WS, WE);
        assertThat(rows).extracting(EventRow::externalId).containsExactly("u1", "u2");
        assertThat(rows).extracting(EventRow::source).containsOnly("ics");
        assertThat(rows).extracting(EventRow::syncedAt).containsOnly(NOW);
        assertThat(rows.get(0).title()).isEqualTo("Standup");
        assertThat(rows.get(0).startsAt()).isEqualTo(11_000);
        assertThat(rows.get(0).endsAt()).isEqualTo(12_000);
        assertThat(rows.get(0).conferenceUrl()).isEqualTo("https://meet.example.com/x");
        assertThat(rows.get(1).conferenceUrl()).isNull();
        assertThat(rows.get(0).id()).isNotBlank().isNotEqualTo(rows.get(1).id());
    }

    @Test
    void AC5_resyncOfUnchangedEventsIsIdempotent() {
        List<CalendarEvent> events = list(
                ev("u1", "A", 11_000, 12_000, att("Ann", "ann@example.com")),
                ev("u2", "B", 13_000, 14_000, att("Bob", "bob@example.com")));
        store.replaceIcsWindow(events, WS, WE, NOW);
        List<EventRow> before = store.listEvents(WS, WE);
        List<PersonRow> peopleBefore = store.listPeople();

        SyncCounts counts = store.replaceIcsWindow(events, WS, WE, NOW + 1_000);

        assertThat(counts).isEqualTo(new SyncCounts(0, 0));
        List<EventRow> after = store.listEvents(WS, WE);
        assertThat(after).hasSameSizeAs(before);
        for (int i = 0; i < before.size(); i++) {
            assertThat(after.get(i)).usingRecursiveComparison().ignoringFields("syncedAt").isEqualTo(before.get(i));
            assertThat(after.get(i).syncedAt()).isEqualTo(NOW + 1_000);
        }
        assertThat(store.listPeople()).isEqualTo(peopleBefore);
        assertThat(count("calendar_events")).isEqualTo(2);
    }

    @Test
    void AC5_absentIcsEventsInsideTheWindowAreRemoved() {
        store.replaceIcsWindow(list(ev("keep", "K", 11_000, 12_000), ev("gone", "G", 13_000, 14_000)), WS, WE, NOW);

        SyncCounts counts = store.replaceIcsWindow(list(ev("keep", "K", 11_000, 12_000)), WS, WE, NOW + 1);

        assertThat(counts).isEqualTo(new SyncCounts(0, 1));
        assertThat(externalIds("ics")).containsExactly("keep");
    }

    @Test
    void AC5_emptySnapshotRemovesAllIcsEventsOfTheWindow() {
        store.replaceIcsWindow(list(ev("a", "A", 11_000, 12_000), ev("b", "B", 13_000, 14_000)), WS, WE, NOW);

        SyncCounts counts = store.replaceIcsWindow(List.of(), WS, WE, NOW + 1);

        assertThat(counts).isEqualTo(new SyncCounts(0, 2));
        assertThat(count("calendar_events")).isZero();
    }

    @Test
    void AC5_icsEventsOutsideTheWindowAreUntouched() {
        insertRaw("before", "ics", "before", 1_000, 2_000);
        insertRaw("after", "ics", "after", 30_000, 31_000);
        insertRaw("inside", "ics", "inside", 12_000, 13_000);

        SyncCounts counts = store.replaceIcsWindow(List.of(), WS, WE, NOW);

        assertThat(counts).isEqualTo(new SyncCounts(0, 1));
        assertThat(externalIds("ics")).containsExactly("after", "before");
        java.util.List<Long> syncedAt = database.read(j -> j.sql("SELECT synced_at FROM calendar_events WHERE id IN ('before','after')")
                .query(Long.class).list());
        assertThat(syncedAt).containsOnly(1L);
    }

    @Test
    void AC5_otherSourcesAreNeverRemovedEvenInsideTheWindow() {
        insertRaw("a1", "agent", "x", 12_000, 13_000);
        insertRaw("m1", "manual", "x", 12_000, 13_000);
        insertRaw("i1", "ics", "x", 12_000, 13_000);

        SyncCounts counts = store.replaceIcsWindow(List.of(), WS, WE, NOW);

        assertThat(counts).isEqualTo(new SyncCounts(0, 1));
        assertThat(externalIds("agent")).containsExactly("x");
        assertThat(externalIds("manual")).containsExactly("x");
        assertThat(externalIds("ics")).isEmpty();
    }

    @Test
    void AC5_windowBoundaryRuleDecidesWhichAbsentEventsAreRemoved() {
        insertRaw("ends-at-start", "ics", "ends-at-start", 5_000, WS);        // end == windowStart: outside
        insertRaw("ends-after-start", "ics", "ends-after-start", 5_000, WS + 1); // inside
        insertRaw("starts-at-end", "ics", "starts-at-end", WE, WE + 1_000);   // start == windowEnd: outside
        insertRaw("starts-before-end", "ics", "starts-before-end", WE - 1, WE + 1_000); // inside
        insertRaw("zero-at-start", "ics", "zero-at-start", WS, WS);           // zero length at windowStart: inside
        insertRaw("zero-before-start", "ics", "zero-before-start", WS - 1, WS - 1); // outside
        insertRaw("spans", "ics", "spans", 1_000, 99_000);                    // spans the window: inside

        SyncCounts counts = store.replaceIcsWindow(List.of(), WS, WE, NOW);

        assertThat(counts.removed()).isEqualTo(4);
        assertThat(externalIds("ics")).containsExactlyInAnyOrder("ends-at-start", "starts-at-end", "zero-before-start");
    }

    @Test
    void AC5_upsertKeepsTheRowIdAndCountsChangedContent() {
        store.replaceIcsWindow(list(ev("u1", "Old", 11_000, 12_000)), WS, WE, NOW);
        String id = idOf("u1");

        SyncCounts counts = store.replaceIcsWindow(list(ev("u1", "New", 11_000, 12_000)), WS, WE, NOW + 1);

        assertThat(counts).isEqualTo(new SyncCounts(1, 0));
        assertThat(idOf("u1")).isEqualTo(id);
        assertThat(store.listEvents(WS, WE)).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(id);
            assertThat(row.title()).isEqualTo("New");
        });
        assertThat(count("calendar_events")).isEqualTo(1);
    }

    @Test
    void AC5_everyContentFieldChangeCountsAsOneUpsert() {
        CalendarEvent base = new CalendarEvent("u1", "T", 11_000, 12_000, "https://a.example.com",
                List.of(att("Ann", "ann@example.com")));
        store.replaceIcsWindow(list(base), WS, WE, NOW);
        List<CalendarEvent> variants = List.of(
                new CalendarEvent("u1", "T2", 11_000, 12_000, "https://a.example.com", base.attendees()),
                new CalendarEvent("u1", "T2", 11_500, 12_000, "https://a.example.com", base.attendees()),
                new CalendarEvent("u1", "T2", 11_500, 12_500, "https://a.example.com", base.attendees()),
                new CalendarEvent("u1", "T2", 11_500, 12_500, "https://b.example.com", base.attendees()),
                new CalendarEvent("u1", "T2", 11_500, 12_500, null, base.attendees()),
                new CalendarEvent("u1", "T2", 11_500, 12_500, null, List.of(att("Ann", "ann@example.com"), att("Bob", "bob@example.com"))),
                new CalendarEvent("u1", "T2", 11_500, 12_500, null, List.of(att("Annie", "ann@example.com"), att("Bob", "bob@example.com"))));
        long now = NOW;
        for (CalendarEvent variant : variants) {
            now += 1;
            assertThat(store.replaceIcsWindow(list(variant), WS, WE, now)).as(variant.toString()).isEqualTo(new SyncCounts(1, 0));
        }
        assertThat(store.listEvents(WS, WE)).singleElement().satisfies(row ->
                assertThat(row.attendees()).containsExactly(att("Annie", "ann@example.com"), att("Bob", "bob@example.com")));
    }

    @Test
    void AC5_onlyChangedEventsAreCountedInAMixedSync() {
        store.replaceIcsWindow(list(ev("a", "A", 11_000, 12_000), ev("b", "B", 13_000, 14_000), ev("c", "C", 15_000, 16_000)),
                WS, WE, NOW);

        SyncCounts counts = store.replaceIcsWindow(
                list(ev("a", "A", 11_000, 12_000), ev("b", "B changed", 13_000, 14_000), ev("d", "D", 17_000, 18_000)),
                WS, WE, NOW + 1);

        assertThat(counts).isEqualTo(new SyncCounts(2, 1));
        assertThat(externalIds("ics")).containsExactly("a", "b", "d");
    }

    @Test
    void AC5_upsertMatchesOnSourceAndExternalIdNotOnOtherSources() {
        insertRaw("agent-row", "agent", "u1", 11_000, 12_000);

        SyncCounts counts = store.replaceIcsWindow(list(ev("u1", "Ics", 11_000, 12_000)), WS, WE, NOW);

        assertThat(counts).isEqualTo(new SyncCounts(1, 0));
        assertThat(count("calendar_events")).isEqualTo(2);
        String agentTitle = database.read(j -> j.sql("SELECT title FROM calendar_events WHERE id = 'agent-row'")
                .query(String.class).single());
        assertThat(agentTitle).isEqualTo("raw");
    }

    @Test
    void AC5_aMovedEventKeepsItsRowEvenWhenItLeavesTheWindowContent() {
        store.replaceIcsWindow(list(ev("u1", "A", 11_000, 12_000)), WS, WE, NOW);
        String id = idOf("u1");

        store.replaceIcsWindow(list(ev("u1", "A", 13_000, 14_000)), WS, WE, NOW + 1);

        assertThat(idOf("u1")).isEqualTo(id);
        assertThat(store.listEvents(WS, WE)).singleElement().satisfies(r -> assertThat(r.startsAt()).isEqualTo(13_000));
    }

    @Test
    void AC5_removingASyncedEventUnlinksItsMeeting() {
        store.replaceIcsWindow(list(ev("u1", "A", 11_000, 12_000)), WS, WE, NOW);
        insertMeeting("m1", idOf("u1"));

        store.replaceIcsWindow(List.of(), WS, WE, NOW + 1);

        assertThat(meetingEvent("m1")).isNull();
        assertThat(count("meetings")).isEqualTo(1);
    }

    @Test
    void AC5_upsertKeepsTheMeetingLink() {
        store.replaceIcsWindow(list(ev("u1", "A", 11_000, 12_000)), WS, WE, NOW);
        String id = idOf("u1");
        insertMeeting("m1", id);

        store.replaceIcsWindow(list(ev("u1", "A renamed", 11_000, 12_000)), WS, WE, NOW + 1);

        assertThat(meetingEvent("m1")).isEqualTo(id);
    }

    @Test
    void AC5_aFailingSyncRollsBackEverything() {
        store.replaceIcsWindow(list(ev("a", "A", 11_000, 12_000), ev("gone", "G", 13_000, 14_000)), WS, WE, NOW);
        List<EventRow> before = store.listEvents(WS, WE);
        List<PersonRow> peopleBefore = store.listPeople();

        assertThatThrownBy(() -> store.replaceIcsWindow(list(
                ev("a", "A changed", 11_000, 12_000, att("New", "new@example.com")),
                ev("fresh", "F", 15_000, 16_000),
                ev("bad", null, 15_000, 16_000)), WS, WE, NOW + 1))
                .isInstanceOf(RuntimeException.class);

        assertThat(store.listEvents(WS, WE)).isEqualTo(before);
        assertThat(store.listPeople()).isEqualTo(peopleBefore);
        assertThat(externalIds("ics")).containsExactly("a", "gone");
    }

    @Test
    void AC5_aDatabaseErrorMidSyncRollsBackUpsertsDeletesAndPeople() {
        store.replaceIcsWindow(list(ev("a", "A", 11_000, 12_000), ev("gone", "G", 13_000, 14_000)), WS, WE, NOW);
        List<EventRow> before = store.listEvents(WS, WE);
        database.write(j -> j.sql("CREATE TRIGGER fail_on_boom BEFORE INSERT ON calendar_events WHEN NEW.title = 'boom' "
                + "BEGIN SELECT RAISE(ABORT, 'boom'); END").update());

        assertThatThrownBy(() -> store.replaceIcsWindow(list(
                ev("a", "A changed", 11_000, 12_000, att("Zed", "zed@example.com")),
                ev("x", "boom", 15_000, 16_000)), WS, WE, NOW + 1))
                .isInstanceOf(RuntimeException.class);

        assertThat(store.listEvents(WS, WE)).isEqualTo(before);
        assertThat(store.listPeople()).isEmpty();

        // the store stays usable after a rollback
        assertThat(store.replaceIcsWindow(list(ev("a", "A", 11_000, 12_000)), WS, WE, NOW + 2)).isEqualTo(new SyncCounts(0, 1));
    }

    // ---- listEvents ----

    @Test
    void AC5_listEventsOrdersByStartThenIdAndAppliesTheWindowRule() {
        insertRaw("b", "ics", "b", 15_000, 16_000);
        insertRaw("a", "ics", "a", 15_000, 16_000);
        insertRaw("early", "ics", "early", 11_000, 12_000);
        insertRaw("ends-at-from", "ics", "ends-at-from", 5_000, WS);
        insertRaw("starts-at-to", "ics", "starts-at-to", WE, WE + 5);
        insertRaw("zero-in", "ics", "zero-in", 12_000, 12_000);
        insertRaw("manual", "manual", "m", 17_000, 18_000);

        assertThat(store.listEvents(WS, WE)).extracting(EventRow::id)
                .containsExactly("early", "zero-in", "a", "b", "manual");
        assertThat(store.listEvents(WS, WE)).extracting(EventRow::source)
                .containsExactly("ics", "ics", "ics", "ics", "manual");
    }

    @Test
    void AC5_listEventsOnAnEmptyStoreIsEmpty() {
        assertThat(store.listEvents(WS, WE)).isEmpty();
    }

    @Test
    void AC4_listEventsDecodesAttendeesFromJson() {
        store.replaceIcsWindow(list(
                ev("u1", "A", 11_000, 12_000, att("Ann Martin", "ann@example.com"), att("Bob", "bob@example.com"), att("No Mail", null)),
                ev("u2", "B", 13_000, 14_000)), WS, WE, NOW);

        List<EventRow> rows = store.listEvents(WS, WE);

        assertThat(rows.get(0).attendees()).containsExactly(
                att("Ann Martin", "ann@example.com"), att("Bob", "bob@example.com"), att("No Mail", null));
        assertThat(rows.get(1).attendees()).isEmpty();
    }

    @Test
    void AC4_listEventsToleratesANullAttendeesColumn() {
        insertRaw("raw", "manual", "x", 12_000, 13_000);
        assertThat(store.listEvents(WS, WE)).singleElement().satisfies(r -> assertThat(r.attendees()).isEmpty());
    }

    // ---- AC4: people ----

    @Test
    void AC4_peopleAreUpsertedByCaseInsensitiveEmailDuringSync() {
        store.replaceIcsWindow(list(
                ev("u1", "A", 11_000, 12_000, att("Ann", "Ann@Example.com")),
                ev("u2", "B", 13_000, 14_000, att("Ann", "  ann@example.COM ")),
                ev("u3", "C", 15_000, 16_000, att("Bob", "bob@example.com"))), WS, WE, NOW);

        assertThat(store.listPeople()).extracting(PersonRow::email).containsExactlyInAnyOrder("ann@example.com", "bob@example.com");
        assertThat(store.listPeople()).extracting(PersonRow::displayName).containsExactlyInAnyOrder("Ann", "Bob");
        assertThat(store.listPeople()).extracting(PersonRow::createdAt).containsOnly(NOW);
        assertThat(count("people")).isEqualTo(2);
    }

    @Test
    void AC4_resyncDoesNotDuplicatePeople() {
        List<CalendarEvent> events = list(ev("u1", "A", 11_000, 12_000, att("Ann", "ann@example.com")));
        store.replaceIcsWindow(events, WS, WE, NOW);
        List<PersonRow> first = store.listPeople();

        store.replaceIcsWindow(events, WS, WE, NOW + 5_000);
        store.replaceIcsWindow(events, WS, WE, NOW + 9_000);

        assertThat(store.listPeople()).isEqualTo(first);
    }

    @Test
    void AC4_peopleOfRemovedEventsAreKept() {
        store.replaceIcsWindow(list(ev("u1", "A", 11_000, 12_000, att("Ann", "ann@example.com"))), WS, WE, NOW);
        store.replaceIcsWindow(List.of(), WS, WE, NOW + 1);
        assertThat(store.listPeople()).extracting(PersonRow::email).containsExactly("ann@example.com");
    }

    @Test
    void AC4_attendeesWithoutEmailStayOnlyInTheEventJson() {
        store.replaceIcsWindow(list(ev("u1", "A", 11_000, 12_000,
                att("Room 4", null), att("Blank Mail", ""), att("Spaces", "   "), att("Ann", "ann@example.com"))), WS, WE, NOW);

        assertThat(store.listPeople()).extracting(PersonRow::email).containsExactly("ann@example.com");
        assertThat(count("people")).isEqualTo(1);
        String json = database.read(j -> j.sql("SELECT attendees FROM calendar_events WHERE external_id = 'u1'")
                .query(String.class).single());
        assertThat(json).contains("Room 4").contains("ann@example.com");
        assertThat(store.listEvents(WS, WE)).singleElement().satisfies(r ->
                assertThat(r.attendees()).extracting(Attendee::name).contains("Room 4"));
    }

    @Test
    void AC4_attendeesJsonUsesNameAndEmailKeysWithNullsAsJsonNull() {
        store.replaceIcsWindow(list(ev("u1", "A", 11_000, 12_000, att(null, "ann@example.com"), att("Room", null))), WS, WE, NOW);

        String json = database.read(j -> j.sql("SELECT attendees FROM calendar_events WHERE external_id = 'u1'")
                .query(String.class).single());

        assertThat(json).startsWith("[").endsWith("]")
                .contains("\"name\":null").contains("\"email\":\"ann@example.com\"")
                .contains("\"name\":\"Room\"").contains("\"email\":null");
    }

    @Test
    void AC4_aNameLessAttendeeGetsAPersonRowWithoutCrashing() {
        store.replaceIcsWindow(list(ev("u1", "A", 11_000, 12_000, att(null, "ann@example.com"))), WS, WE, NOW);
        assertThat(store.listPeople()).singleElement().satisfies(p -> {
            assertThat(p.email()).isEqualTo("ann@example.com");
            assertThat(p.displayName()).isNotNull();
        });
    }

    @Test
    void AC4_upsertPersonLowerCasesAndTrimsTheEmailAndReturnsTheSameIdInAnyCase() {
        PersonRow first = store.upsertPerson("Ann", "Ann@Example.COM", NOW);
        PersonRow second = store.upsertPerson("Ann", "ann@example.com", NOW + 1);
        PersonRow third = store.upsertPerson("Ann", "  ANN@example.com  ", NOW + 2);

        assertThat(first.email()).isEqualTo("ann@example.com");
        assertThat(first.id()).isNotBlank();
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(third.id()).isEqualTo(first.id());
        assertThat(third.createdAt()).isEqualTo(NOW);
        assertThat(store.listPeople()).hasSize(1);
    }

    @Test
    void AC4_upsertPersonKeepsAnExistingNonBlankDisplayName() {
        PersonRow first = store.upsertPerson("Ann Martin", "ann@example.com", NOW);

        PersonRow other = store.upsertPerson("A. M.", "ANN@example.com", NOW + 1);
        PersonRow blank = store.upsertPerson("", "ann@example.com", NOW + 2);
        PersonRow nul = store.upsertPerson(null, "ann@example.com", NOW + 3);

        assertThat(other.displayName()).isEqualTo("Ann Martin");
        assertThat(blank.displayName()).isEqualTo("Ann Martin");
        assertThat(nul.displayName()).isEqualTo("Ann Martin");
        assertThat(other.id()).isEqualTo(first.id());
        assertThat(store.listPeople()).singleElement().satisfies(p -> assertThat(p.displayName()).isEqualTo("Ann Martin"));
    }

    @Test
    void AC4_upsertPersonFillsABlankDisplayName() {
        PersonRow first = store.upsertPerson("", "ann@example.com", NOW);

        PersonRow filled = store.upsertPerson("Ann Martin", "ann@example.com", NOW + 1);

        assertThat(filled.id()).isEqualTo(first.id());
        assertThat(filled.displayName()).isEqualTo("Ann Martin");
        assertThat(store.listPeople()).singleElement().satisfies(p -> assertThat(p.displayName()).isEqualTo("Ann Martin"));
    }

    @Test
    void AC4_syncKeepsAnExistingDisplayNameAndFillsABlankOne() {
        store.upsertPerson("Ann Martin", "ann@example.com", NOW);
        store.upsertPerson("", "bob@example.com", NOW);

        store.replaceIcsWindow(list(ev("u1", "A", 11_000, 12_000,
                att("Annie", "ANN@example.com"), att("Bob Stone", "bob@example.com"))), WS, WE, NOW + 1);

        assertThat(store.listPeople()).extracting(PersonRow::displayName).containsExactly("Ann Martin", "Bob Stone");
        assertThat(store.listPeople()).hasSize(2);
    }

    @Test
    void AC4_listPeopleOrdersByCreationTimeThenId() {
        store.upsertPerson("Late", "late@example.com", NOW + 10);
        store.upsertPerson("Early", "early@example.com", NOW);
        store.upsertPerson("Mid", "mid@example.com", NOW + 5);

        assertThat(store.listPeople()).extracting(PersonRow::displayName).containsExactly("Early", "Mid", "Late");
    }

    // ---- participants ----

    @Test
    void AC4_participantsAddAndListAreIdempotent() {
        insertMeeting("m1", null);
        insertMeeting("m2", null);
        PersonRow ann = store.upsertPerson("Ann", "ann@example.com", NOW);
        PersonRow bob = store.upsertPerson("Bob", "bob@example.com", NOW + 1);

        store.addParticipant("m1", ann.id());
        store.addParticipant("m1", ann.id());
        store.addParticipant("m1", bob.id());
        store.addParticipant("m2", ann.id());

        assertThat(store.listParticipants("m1")).extracting(PersonRow::email)
                .containsExactlyInAnyOrder("ann@example.com", "bob@example.com");
        assertThat(store.listParticipants("m1")).hasSize(2);
        assertThat(store.listParticipants("m2")).singleElement().satisfies(p -> assertThat(p.id()).isEqualTo(ann.id()));
        assertThat(count("meeting_participants")).isEqualTo(3);
    }

    @Test
    void AC4_listParticipantsOfAnUnknownOrEmptyMeetingIsEmpty() {
        insertMeeting("m1", null);
        assertThat(store.listParticipants("m1")).isEmpty();
        assertThat(store.listParticipants("missing")).isEmpty();
    }

    @Test
    void AC4_participantsReturnFullPersonRows() {
        insertMeeting("m1", null);
        PersonRow ann = store.upsertPerson("Ann", "ann@example.com", NOW);
        store.addParticipant("m1", ann.id());

        assertThat(store.listParticipants("m1")).containsExactly(ann);
    }

    @Test
    void AC4_addParticipantRejectsAnUnknownMeetingOrPerson() {
        insertMeeting("m1", null);
        PersonRow ann = store.upsertPerson("Ann", "ann@example.com", NOW);

        assertThatThrownBy(() -> store.addParticipant("missing", ann.id())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> store.addParticipant("m1", "missing")).isInstanceOf(RuntimeException.class);
        assertThat(count("meeting_participants")).isZero();
    }

    // ---- deleteIcsEvents ----

    @Test
    void AC5_deleteIcsEventsRemovesEveryIcsRowAndUnlinksMeetings() {
        store.replaceIcsWindow(list(ev("u1", "A", 11_000, 12_000)), WS, WE, NOW);
        insertRaw("outside", "ics", "outside", 90_000, 91_000);
        insertRaw("agent-row", "agent", "g", 12_000, 13_000);
        insertRaw("manual-row", "manual", "h", 12_000, 13_000);
        insertMeeting("m1", idOf("u1"));
        insertMeeting("m2", "agent-row");
        insertMeeting("m3", null);

        int deleted = store.deleteIcsEvents();

        assertThat(deleted).isEqualTo(2);
        assertThat(externalIds("ics")).isEmpty();
        assertThat(externalIds("agent")).containsExactly("g");
        assertThat(externalIds("manual")).containsExactly("h");
        assertThat(meetingEvent("m1")).isNull();
        assertThat(meetingEvent("m2")).isEqualTo("agent-row");
        assertThat(meetingEvent("m3")).isNull();
        assertThat(count("meetings")).isEqualTo(3);
    }

    @Test
    void AC5_deleteIcsEventsOnAnEmptyStoreReturnsZeroAndKeepsPeople() {
        assertThat(store.deleteIcsEvents()).isZero();
        store.replaceIcsWindow(list(ev("u1", "A", 11_000, 12_000, att("Ann", "ann@example.com"))), WS, WE, NOW);

        assertThat(store.deleteIcsEvents()).isEqualTo(1);
        assertThat(store.deleteIcsEvents()).isZero();
        assertThat(store.listPeople()).hasSize(1);
        assertThat(store.listEvents(WS, WE)).isEmpty();
    }

    @Test
    void AC5_aSyncAfterDeleteInsertsFreshRows() {
        store.replaceIcsWindow(list(ev("u1", "A", 11_000, 12_000)), WS, WE, NOW);
        store.deleteIcsEvents();

        assertThat(store.replaceIcsWindow(list(ev("u1", "A", 11_000, 12_000)), WS, WE, NOW + 1)).isEqualTo(new SyncCounts(1, 0));
        assertThat(store.listEvents(WS, WE)).hasSize(1);
    }
}
