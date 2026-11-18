package app.interline.app;

import app.interline.domain.CalendarEvent;
import app.interline.domain.CalendarSnapshot;
import app.interline.domain.CalendarSource;
import app.interline.domain.SecretStore;
import app.interline.persistence.CalendarStore;
import app.interline.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static app.interline.app.M3Support.DAY;
import static app.interline.app.M3Support.HOUR;
import static app.interline.app.M3Support.NOW;
import static app.interline.app.M3Support.NOW_INSTANT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review round 2, item 8: a snapshot flagged incomplete (the parser stopped early) must never delete stored events;
 * the sync result reports {@code complete=false} and carries at least one warning mentioning "incomplete".
 */
@Timeout(60)
class CalendarServiceIncompleteTest {
    private static final String URL = "https://example.com/private/FAKE-TOKEN.ics?key=FAKE-KEY";
    private static final long FAR = Long.MAX_VALUE / 4;

    @TempDir Path directory;
    private SqliteDatabase database;
    private CalendarStore store;
    private CalendarService service;
    private volatile CalendarSnapshot next;

    static final class MemorySecrets implements SecretStore {
        private final Map<String, String> values = new ConcurrentHashMap<>();
        @Override public Optional<String> get(String account) { return Optional.ofNullable(values.get(account)); }
        @Override public void put(String account, String value) { values.put(account, value); }
        @Override public void delete(String account) { values.remove(account); }
    }

    @BeforeEach void start() {
        database = SqliteDatabase.open(directory.resolve("db.sqlite"));
        store = new CalendarStore(database);
        CalendarSource source = (from, to) -> next;
        service = new CalendarService(store, new MemorySecrets(), new HttpIcsFetcher(false), source, Clock.fixed(NOW_INSTANT, ZoneOffset.UTC));
        service.setSource(URL);
    }

    @AfterEach void stop() {
        database.close();
    }

    private static CalendarEvent event(String uid, String title, long start, long end) {
        return new CalendarEvent(uid, title, start, end, null, List.of());
    }

    private static CalendarEvent one() { return event("uid-1", "One", NOW + HOUR, NOW + 2 * HOUR); }
    private static CalendarEvent two() { return event("uid-2", "Two", NOW + 2 * HOUR, NOW + 3 * HOUR); }
    private static CalendarEvent three() { return event("uid-3", "Three", NOW + DAY, NOW + DAY + HOUR); }

    private void seedThree() {
        next = new CalendarSnapshot(List.of(one(), two(), three()), List.of(), 0, true);
        CalendarService.SyncResult seeded = service.sync();
        assertThat(seeded.upserted()).isEqualTo(3);
        assertThat(seeded.complete()).isTrue();
    }

    private List<String> titles() {
        return store.listEvents(0, FAR).stream().filter(e -> "ics".equals(e.source()))
                .map(CalendarStore.EventRow::title).sorted().toList();
    }

    @Test void aCompleteSnapshotReportsCompleteTrueAndStillDeletesAbsentEvents() {
        seedThree();
        next = new CalendarSnapshot(List.of(one()), List.of(), 0, true);

        CalendarService.SyncResult result = service.sync();

        assertThat(result.complete()).isTrue();
        assertThat(result.removed()).isEqualTo(2);
        assertThat(result.warnings()).noneMatch(w -> w.toLowerCase().contains("incomplete"));
        assertThat(titles()).containsExactly("One");
    }

    @Test void theThreeArgumentSnapshotConstructorMeansCompleteAndDeletes() {
        seedThree();
        next = new CalendarSnapshot(List.of(one()), List.of(), 0);

        CalendarService.SyncResult result = service.sync();

        assertThat(result.complete()).isTrue();
        assertThat(titles()).containsExactly("One");
    }

    @Test void anIncompleteSnapshotKeepsEventsAbsentFromIt_andReportsCompleteFalse() {
        seedThree();
        next = new CalendarSnapshot(List.of(one()), List.of(), 0, false);

        CalendarService.SyncResult result = service.sync();

        assertThat(result.complete()).isFalse();
        assertThat(result.removed()).isZero();
        assertThat(titles()).containsExactly("One", "Three", "Two");
    }

    @Test void anIncompleteSnapshotWithoutParserWarningsGetsAnIncompleteWarning() {
        seedThree();
        next = new CalendarSnapshot(List.of(one()), List.of(), 0, false);

        CalendarService.SyncResult result = service.sync();

        assertThat(result.warnings()).anyMatch(w -> w.toLowerCase().contains("incomplete"));
    }

    @Test void anIncompleteSnapshotKeepsTheParserWarnings() {
        seedThree();
        next = new CalendarSnapshot(List.of(one()), List.of("Stopped expanding recurrences after 100000 steps"), 2, false);

        CalendarService.SyncResult result = service.sync();

        assertThat(result.complete()).isFalse();
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.warnings()).contains("Stopped expanding recurrences after 100000 steps");
        assertThat(result.removed()).isZero();
    }

    @Test void anIncompleteSnapshotStillUpsertsNewAndChangedEvents() {
        seedThree();
        CalendarEvent changed = event("uid-1", "One renamed", NOW + HOUR, NOW + 2 * HOUR);
        CalendarEvent added = event("uid-4", "Four", NOW + 3 * DAY, NOW + 3 * DAY + HOUR);
        next = new CalendarSnapshot(List.of(changed, added), List.of(), 0, false);

        CalendarService.SyncResult result = service.sync();

        assertThat(result.upserted()).isEqualTo(2);
        assertThat(result.removed()).isZero();
        assertThat(titles()).containsExactly("Four", "One renamed", "Three", "Two");
    }

    @Test void anEmptyIncompleteSnapshotDeletesNothing() {
        seedThree();
        next = new CalendarSnapshot(List.of(), List.of(), 0, false);

        CalendarService.SyncResult result = service.sync();

        assertThat(result.complete()).isFalse();
        assertThat(result.removed()).isZero();
        assertThat(titles()).containsExactly("One", "Three", "Two");
    }

    @Test void aLaterCompleteSyncRemovesWhatTheIncompleteOneKept() {
        seedThree();
        next = new CalendarSnapshot(List.of(one()), List.of(), 0, false);
        service.sync();
        next = new CalendarSnapshot(List.of(one()), List.of(), 0, true);

        CalendarService.SyncResult result = service.sync();

        assertThat(result.complete()).isTrue();
        assertThat(result.removed()).isEqualTo(2);
        assertThat(titles()).containsExactly("One");
    }
}
