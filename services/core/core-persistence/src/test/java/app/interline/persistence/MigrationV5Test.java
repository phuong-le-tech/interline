package app.interline.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static app.interline.persistence.SqlSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

class MigrationV5Test {
    private static final String[] LOCATIONS = {"classpath:db/migration", "classpath:db/migration-m1",
            "classpath:db/migration-m2", "classpath:db/migration-m3"};
    @TempDir Path directory;

    private Flyway flyway(String url) {
        return Flyway.configure().dataSource(url, null, null).locations(LOCATIONS).target("5").load();
    }

    @Test void freshDatabaseHasNullableAcknowledgementAndDefaultsReminderToTrue() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("fresh.db");
        flyway(url).migrate();
        try (var c = DriverManager.getConnection(url)) {
            assertThat(strings(c, "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank"))
                    .containsExactly("1", "2", "3", "4", "5");
            meeting(c, "m1");
            assertThat(strings(c, "SELECT consent_ack_at FROM meetings WHERE id = 'm1'")).containsExactly((String) null);
            assertThat(strings(c, "SELECT value FROM settings WHERE key = 'consent.reminder_enabled'"))
                    .containsExactly("true");
        }
    }

    @Test void populatedV4UpgradeAndRetriesPreserveDataAcknowledgementAndOptOut() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("upgrade.db");
        Flyway.configure().dataSource(url, null, null).locations(LOCATIONS).target("4").load().migrate();
        try (var c = DriverManager.getConnection(url)) {
            meeting(c, "old");
            segment(c, 1, "old", "Bonjour");
            block(c, "b1", "old", "Existing notes");
            execute(c, "INSERT INTO settings(key,value,updated_at) VALUES ('language','fr',100)");
            execute(c, "INSERT INTO settings(key,value,updated_at) VALUES ('consent.reminder_enabled','false',100)");
        }
        flyway(url).migrate();
        try (var c = DriverManager.getConnection(url)) {
            assertThat(strings(c, "SELECT name FROM pragma_table_info('meetings')")).contains("consent_ack_at");
            assertThat(strings(c, "SELECT consent_ack_at FROM meetings WHERE id = 'old'")).containsExactly((String) null);
            execute(c, "UPDATE meetings SET consent_ack_at = 123456 WHERE id = 'old'");
        }
        assertThat(flyway(url).migrate().migrationsExecuted).isZero();
        try (var c = DriverManager.getConnection(url)) {
            assertThat(strings(c, "SELECT consent_ack_at FROM meetings WHERE id = 'old'")).containsExactly("123456");
            assertThat(strings(c, "SELECT text FROM transcript_segments")).containsExactly("Bonjour");
            assertThat(strings(c, "SELECT plain_text FROM note_blocks")).containsExactly("Existing notes");
            assertThat(strings(c, "SELECT value FROM settings WHERE key = 'language'")).containsExactly("fr");
            assertThat(strings(c, "SELECT value FROM settings WHERE key = 'consent.reminder_enabled'"))
                    .containsExactly("false");
            meeting(c, "new");
            assertThat(strings(c, "SELECT consent_ack_at FROM meetings WHERE id = 'new'")).containsExactly((String) null);
            assertThat(strings(c, "PRAGMA foreign_key_check")).isEmpty();
        }
        try (var reopened = SqliteDatabase.open(directory.resolve("upgrade.db"))) {
            Long acknowledgement = reopened.read(jdbc -> jdbc.sql("SELECT consent_ack_at FROM meetings WHERE id = 'old'")
                    .query(Long.class).single());
            assertThat(acknowledgement).isEqualTo(123456L);
        }
    }
}
