package app.interline.app;

import app.interline.domain.CalendarEvent;
import app.interline.persistence.CalendarStore;
import app.interline.persistence.SqliteDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class UpcomingNotifierTest {
    private static final long NOW = 1_800_000_000_000L;
    private static final long MINUTE = 60_000;

    @TempDir Path directory;
    private SqliteDatabase database;
    private CalendarStore store;
    private long clockMs = NOW;
    private final List<Map<String, Object>> announced = new ArrayList<>();
    private UpcomingNotifier notifier;

    @BeforeEach
    void open() {
        database = SqliteDatabase.open(directory.resolve("db.sqlite"));
        store = new CalendarStore(database);
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(clockMs); }
        };
        notifier = new UpcomingNotifier(store, clock, announced::add);
    }

    @AfterEach
    void close() { database.close(); }

    private void feed(CalendarEvent... events) {
        store.replaceIcsWindow(List.of(events), NOW - 10 * MINUTE, NOW + 600 * MINUTE, NOW);
    }

    private static CalendarEvent event(String uid, long start) {
        return new CalendarEvent(uid, "Standup " + uid, start, start + 30 * MINUTE, null, List.of());
    }

    @Test
    void announcesOnceInTheMinuteBeforeAndNeverEarlyOrAfterStart() {
        feed(event("soon", NOW + 45_000), event("later", NOW + 5 * MINUTE), event("started", NOW - 20_000));

        notifier.tick();
        notifier.tick();

        assertThat(announced).hasSize(1);
        assertThat(announced.get(0)).containsEntry("title", "Standup soon").containsEntry("startsAt", NOW + 45_000);

        clockMs = NOW + 4 * MINUTE + 30_000;
        notifier.tick();
        assertThat(announced).hasSize(2);
        assertThat(announced.get(1)).containsEntry("title", "Standup later");
    }
}
