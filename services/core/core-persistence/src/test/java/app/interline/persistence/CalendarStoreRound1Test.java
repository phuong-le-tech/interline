package app.interline.persistence;

import app.interline.domain.Attendee;
import app.interline.domain.CalendarEvent;
import app.interline.persistence.CalendarStore.EventRow;
import app.interline.persistence.CalendarStore.SyncCounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** M3 review round 1, persistence: null external_id rows and corrupt attendees columns. Window: [10_000, 20_000). */
class CalendarStoreRound1Test {
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

    private void insertRaw(String id, String externalId, long start, long end) {
        database.write(j -> j.sql("INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at,synced_at) "
                + "VALUES (?,'ics',?,'raw',?,?,1)").params(id, externalId, start, end).update());
    }

    private void insertRawWithAttendees(String id, String externalId, long start, long end, String attendees) {
        database.write(j -> j.sql("INSERT INTO calendar_events(id,source,external_id,title,starts_at,ends_at,attendees,synced_at) "
                + "VALUES (?,'ics',?,'raw',?,?,?,1)").params(id, externalId, start, end, attendees).update());
    }

    private List<String> ids() {
        return database.read(j -> j.sql("SELECT id FROM calendar_events WHERE source = 'ics' ORDER BY id")
                .query(String.class).list());
    }

    // ---- 7: NULL external_id rows ----

    @Test
    void nullExternalIdRowInWindowIsRemovedByEmptySyncAndOutsideRowStays() {
        insertRaw("in", null, 11_000, 12_000);
        insertRaw("out", null, 30_000, 31_000);

        SyncCounts counts = store.replaceIcsWindow(List.of(), WS, WE, NOW);

        assertThat(counts.removed()).isEqualTo(1);
        assertThat(counts.upserted()).isZero();
        assertThat(ids()).containsExactly("out");
    }

    @Test
    void twoOverlappingNullExternalIdRowsAreBothRemovedAndCountedAsTwo() {
        insertRaw("a", null, 11_000, 12_000);
        insertRaw("b", null, 13_000, 14_000);
        insertRaw("out", null, 30_000, 31_000);

        SyncCounts counts = store.replaceIcsWindow(List.of(), WS, WE, NOW);

        assertThat(counts.removed()).isEqualTo(2);
        assertThat(ids()).containsExactly("out");
    }

    @Test
    void nullExternalIdRowsAreRemovedEvenWhenOtherEventsAreSynced() {
        insertRaw("a", null, 11_000, 12_000);
        insertRaw("b", null, 13_000, 14_000);

        SyncCounts counts = store.replaceIcsWindow(
                List.of(new CalendarEvent("u1", "Keep", 15_000, 16_000, null, List.of())), WS, WE, NOW);

        assertThat(counts.upserted()).isEqualTo(1);
        assertThat(counts.removed()).isEqualTo(2);
        assertThat(store.listEvents(WS, WE)).extracting(EventRow::externalId).containsExactly("u1");
    }

    // ---- 8: corrupt attendees column ----

    private static final List<String> CORRUPT = List.of(
            "not json",
            "[{\"name\":\"\\u12\"}]",
            "[{\"name\":5}]",
            "{}",
            "[{\"name\":\"Ann\",\"email\":\"ann@example.com\"",
            "[{\"name\":\"Ann\"},",
            "[",
            "");

    @Test
    void corruptAttendeesColumnYieldsEventWithEmptyAttendeesInsteadOfThrowing() {
        for (int i = 0; i < CORRUPT.size(); i++) {
            insertRawWithAttendees("row" + i, "ext" + i, 11_000 + i, 12_000 + i, CORRUPT.get(i));
        }

        assertThatCode(() -> store.listEvents(WS, WE)).doesNotThrowAnyException();
        List<EventRow> rows = store.listEvents(WS, WE);
        assertThat(rows).hasSize(CORRUPT.size());
        assertThat(rows).allSatisfy(row -> assertThat(row.attendees()).isEmpty());
    }

    @Test
    void resyncOfEventWithCorruptAttendeesRewritesItWithoutException() {
        insertRawWithAttendees("row", "u1", 11_000, 12_000, "[{\"name\":\"\\u12\"}]");
        CalendarEvent same = new CalendarEvent("u1", "raw", 11_000, 12_000, null, List.of());

        assertThatCode(() -> store.replaceIcsWindow(List.of(same), WS, WE, NOW)).doesNotThrowAnyException();

        EventRow row = store.listEvents(WS, WE).get(0);
        assertThat(row.id()).isEqualTo("row");
        assertThat(row.syncedAt()).isEqualTo(NOW);
        assertThat(row.attendees()).isEmpty();
        String stored = database.read(j -> j.sql("SELECT attendees FROM calendar_events WHERE id = 'row'")
                .query(String.class).single());
        assertThat(stored).isEqualTo("[]");
    }

    @Test
    void resyncWithAttendeesOverCorruptColumnStoresThemAndReportsChange() {
        insertRawWithAttendees("row", "u1", 11_000, 12_000, "not json");
        CalendarEvent withAttendee = new CalendarEvent("u1", "raw", 11_000, 12_000, null,
                List.of(new Attendee("Ann", "ann@example.com")));

        SyncCounts counts = store.replaceIcsWindow(List.of(withAttendee), WS, WE, NOW);

        assertThat(counts.upserted()).isEqualTo(1);
        assertThat(store.listEvents(WS, WE).get(0).attendees()).containsExactly(new Attendee("Ann", "ann@example.com"));
    }
}
