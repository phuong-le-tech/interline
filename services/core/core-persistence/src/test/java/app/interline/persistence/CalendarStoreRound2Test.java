package app.interline.persistence;

import app.interline.domain.Attendee;
import app.interline.domain.CalendarEvent;
import app.interline.persistence.CalendarStore.SyncCounts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** M3 review round 2, persistence: the deleteAbsent overload. Window: [10_000, 20_000). */
class CalendarStoreRound2Test {
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

    private static CalendarEvent event(String uid, String title, long start, long end) {
        return new CalendarEvent(uid, title, start, end, null, List.of());
    }

    private void seedThree() {
        store.replaceIcsWindow(List.of(
                event("a", "A", 11_000, 12_000),
                event("b", "B", 13_000, 14_000),
                event("c", "C", 15_000, 16_000)), WS, WE, NOW);
    }

    private List<String> externalIds() {
        return store.listEvents(WS, WE).stream().map(CalendarStore.EventRow::externalId).sorted().toList();
    }

    @Test
    void deleteAbsentFalseKeepsAbsentEventsAndUpdatesTheChangedOne() {
        seedThree();

        SyncCounts counts = store.replaceIcsWindow(List.of(event("b", "B changed", 13_000, 14_500)),
                WS, WE, NOW + 1, false);

        assertThat(counts.removed()).isZero();
        assertThat(counts.upserted()).isEqualTo(1);
        assertThat(externalIds()).containsExactly("a", "b", "c");
        CalendarStore.EventRow b = store.listEvents(WS, WE).stream()
                .filter(r -> "b".equals(r.externalId())).findFirst().orElseThrow();
        assertThat(b.title()).isEqualTo("B changed");
        assertThat(b.endsAt()).isEqualTo(14_500);
    }

    @Test
    void deleteAbsentFalseWithEmptyListDeletesNothing() {
        seedThree();

        SyncCounts counts = store.replaceIcsWindow(List.of(), WS, WE, NOW + 1, false);

        assertThat(counts.removed()).isZero();
        assertThat(counts.upserted()).isZero();
        assertThat(externalIds()).containsExactly("a", "b", "c");
    }

    @Test
    void deleteAbsentFalseStillUpsertsPeople() {
        CalendarEvent withAttendee = new CalendarEvent("p", "P", 11_000, 12_000, null,
                List.of(new Attendee("Ann", "ann@example.com")));

        store.replaceIcsWindow(List.of(withAttendee), WS, WE, NOW, false);

        assertThat(store.listPeople()).extracting(CalendarStore.PersonRow::email).containsExactly("ann@example.com");
        assertThat(externalIds()).containsExactly("p");
    }

    @Test
    void deleteAbsentTrueRemovesTheAbsentEvents() {
        seedThree();

        SyncCounts counts = store.replaceIcsWindow(List.of(event("b", "B", 13_000, 14_000)), WS, WE, NOW + 1, true);

        assertThat(counts.removed()).isEqualTo(2);
        assertThat(externalIds()).containsExactly("b");
    }

    @Test
    void fourArgumentOverloadBehavesAsDeleteAbsentTrue() {
        seedThree();

        SyncCounts counts = store.replaceIcsWindow(List.of(event("a", "A", 11_000, 12_000)), WS, WE, NOW + 1);

        assertThat(counts.removed()).isEqualTo(2);
        assertThat(externalIds()).containsExactly("a");
    }
}
