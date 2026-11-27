package app.interline.app;

import app.interline.persistence.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import static org.assertj.core.api.Assertions.assertThat;

class LifecyclePurgerTest {
    @TempDir Path data;
    @Test void tickUsesItsClockAndExpiresTrashAfterThirtyDays() {
        try (var db = SqliteDatabase.open(data.resolve("db"))) {
            var meetings = new MeetingStore(db);
            meetings.createDraft("old", "Keep my words", null, 0);
            var store = new LifecycleStore(db,data);
            store.trash("old",0);
            new LifecyclePurger(store,Clock.fixed(Instant.ofEpochMilli(30 * 86_400_000L),ZoneOffset.UTC)).tick();
            assertThat(store.trash()).hasSize(1);
            var purger = new LifecyclePurger(store,Clock.fixed(Instant.ofEpochMilli(30 * 86_400_000L + 1),ZoneOffset.UTC));
            purger.tick();
            purger.tick();
            assertThat(store.trash()).isEmpty();
        }
    }
}
