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
    @Test void upgradeDoesNotScheduleOrPurgeExistingAudio() throws Exception {
        long now = Instant.parse("2026-11-27T12:00:00Z").toEpochMilli();
        String url = "jdbc:sqlite:" + data.resolve("db");
        org.flywaydb.core.Flyway.configure().dataSource(url,null,null).locations("classpath:db/migration", "classpath:db/migration-m1",
                "classpath:db/migration-m2", "classpath:db/migration-m3").target("6").load().migrate();
        try (var c = java.sql.DriverManager.getConnection(url); var insert = c.prepareStatement(
                "INSERT INTO meetings(id,status,started_at,ended_at,audio_dir,created_at,updated_at) VALUES ('old','done',?,?, 'audio/old',0,0)")) {
            insert.setLong(1,now - 31 * 86_400_000L); insert.setLong(2,now - 30 * 86_400_000L); insert.executeUpdate();
        }
        Path file = data.resolve("audio/old/mic/0.pcm");
        java.nio.file.Files.createDirectories(file.getParent());
        java.nio.file.Files.writeString(file,"existing audio");
        try (var db = SqliteDatabase.open(data.resolve("db"))) {
            new LifecyclePurger(new LifecycleStore(db,data),Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC)).tick();
            assertThat(java.nio.file.Files.readString(file)).isEqualTo("existing audio");
            assertThat(db.<Integer>read(c -> c.sql("SELECT count(*) FROM meetings WHERE id='old' AND audio_purge_at IS NULL").query(Integer.class).single())).isEqualTo(1);
        }
    }

}
