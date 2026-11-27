package app.interline.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import static app.interline.persistence.SqlSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

class MigrationV7Test {
    @TempDir Path data;
    private Flyway migration(String url, String target) {
        return Flyway.configure().dataSource(url,null,null).locations("classpath:db/migration","classpath:db/migration-m1",
                "classpath:db/migration-m2","classpath:db/migration-m3","classpath:db/migration-m4").target(target).load();
    }
    @Test void freshAndUpgradedDatabasesPreserveTextAndRetryWithoutChanges() throws Exception {
        for (boolean upgrade : new boolean[]{false,true}) {
            String url = "jdbc:sqlite:" + data.resolve(upgrade ? "upgrade" : "fresh");
            if (upgrade) {
                migration(url,"6").migrate();
                try (var c = DriverManager.getConnection(url)) {
                    meeting(c,"old");
                    segment(c,1,"old","Original transcript");
                    block(c,"b1","old","Original notes");
                    execute(c,"UPDATE meetings SET started_at=1, ended_at=2 WHERE id='old'");
                }
            }
            migration(url,"7").migrate();
            assertThat(migration(url,"7").migrate().migrationsExecuted).isZero();
            try (var c = DriverManager.getConnection(url)) {
                assertThat(strings(c,"SELECT value FROM settings WHERE key='audio.retention'")).containsExactly("7d");
                assertThat(strings(c,"SELECT name FROM pragma_table_info('meetings')")).contains("deletion_started_at");
                if (upgrade) {
                    assertThat(strings(c,"SELECT plain_text FROM note_blocks")).containsExactly("Original notes");
                    assertThat(strings(c,"SELECT text FROM transcript_segments")).containsExactly("Original transcript");
                    assertThat(strings(c,"SELECT audio_purge_at FROM meetings WHERE id='old'")).containsExactly((String) null);
                }
                assertThat(strings(c,"PRAGMA foreign_key_check")).isEmpty();
            }
        }
    }
}
