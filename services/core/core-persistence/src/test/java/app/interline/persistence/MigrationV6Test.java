package app.interline.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;

import static app.interline.persistence.SqlSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

class MigrationV6Test {
    @TempDir Path directory;

    @Test void upgradesPopulatedV4PreservingNotesAndSupportsIdempotentCacheReplacementAndCascade() throws Exception {
        Path file = directory.resolve("upgrade.db");
        String url = "jdbc:sqlite:" + file;
        Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration", "classpath:db/migration-m1",
                "classpath:db/migration-m2", "classpath:db/migration-m3").target("4").load().migrate();
        try (var connection = DriverManager.getConnection(url)) {
            meeting(connection, "m1");
            block(connection, "b1", "m1", "Original words");
        }
        try (var db = SqliteDatabase.open(file)) {
            assertThat(db.<String>read(c -> c.sql("SELECT plain_text FROM note_blocks WHERE id = 'b1'").query(String.class).single()))
                    .isEqualTo("Original words");
            var cache = new BriefStore(db);
            cache.save("m1", new BriefStore.Cached("[]", "old", null, 1));
            cache.save("m1", new BriefStore.Cached("[]", "new", null, 2));
            assertThat(cache.find("m1")).contains(new BriefStore.Cached("[]", "new", null, 2));
        }
        try (var db = SqliteDatabase.open(file)) {
            assertThat(db.<Integer>read(c -> c.sql("SELECT count(*) FROM briefs").query(Integer.class).single())).isEqualTo(1);
            assertThat(db.<String>read(c -> c.sql("SELECT plain_text FROM note_blocks WHERE id = 'b1'").query(String.class).single()))
                    .isEqualTo("Original words");
            db.write(c -> c.sql("DELETE FROM meetings WHERE id = 'm1'").update());
            assertThat(new BriefStore(db).find("m1")).isEmpty();
            assertThat(db.<java.util.List<String>>read(c -> c.sql("PRAGMA foreign_key_check").query((rs, n) -> rs.getString(1)).list())).isEmpty();
        }
    }
}
