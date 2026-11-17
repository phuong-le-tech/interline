package app.interline.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static app.interline.persistence.SqlSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/** M2 persistence: the V3 migration (docs/ARCHITECTURE.md section 13 plus the M2 additions) and its FTS triggers. */
class MigrationV3Test {
    @TempDir
    Path directory;

    private String url;

    @BeforeEach
    void migrateFreshDatabase() {
        url = "jdbc:sqlite:" + directory.resolve("interline.db");
        flyway().migrate();
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(url, null, null)
                .locations("classpath:db/migration", "classpath:db/migration-m1", "classpath:db/migration-m2").load();
    }

    private Connection open() throws Exception {
        Connection connection = DriverManager.getConnection(url);
        execute(connection, "PRAGMA foreign_keys = ON");
        return connection;
    }

    @Test
    void M2_migrationV3CreatesTheNotesAndAiTables() throws Exception {
        try (Connection c = open()) {
            assertThat(strings(c, "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN "
                    + "('note_blocks','block_sources','note_snapshots','ai_runs','action_items','providers','templates')"))
                    .containsExactlyInAnyOrder("note_blocks", "block_sources", "note_snapshots", "ai_runs",
                            "action_items", "providers", "templates");
            assertThat(strings(c, "SELECT name FROM sqlite_master WHERE name IN ('blocks_fts','segments_fts')"))
                    .containsExactlyInAnyOrder("blocks_fts", "segments_fts");
            assertThat(strings(c, "SELECT version FROM flyway_schema_history WHERE success = 1 ORDER BY installed_rank"))
                    .containsExactly("1", "2", "3");
        }
    }

    @Test
    void M2_migrationV3AddsTheDocumentedColumns() throws Exception {
        try (Connection c = open()) {
            assertThat(strings(c, "SELECT name FROM pragma_table_info('note_blocks')"))
                    .contains("rowid_fts", "id", "meeting_id", "parent_id", "position", "kind", "origin", "content",
                            "plain_text", "anchor_ms", "client_rev", "ai_run_id", "ai_state", "created_at", "updated_at");
            assertThat(strings(c, "SELECT name FROM pragma_table_info('providers')"))
                    .contains("id", "kind", "name", "base_url", "model", "secret_account", "supports_json_schema",
                            "is_default", "created_at", "context_tokens", "price_in_per_mtok", "price_out_per_mtok");
            assertThat(strings(c, "SELECT name FROM pragma_table_info('ai_runs')"))
                    .contains("id", "meeting_id", "kind", "provider_id", "model", "prompt_version", "input_tokens",
                            "output_tokens", "status", "error", "started_at", "finished_at", "cost_estimate_usd");
            assertThat(strings(c, "SELECT name FROM pragma_table_info('meetings')")).contains("template_id");
            assertThat(strings(c, "SELECT name FROM pragma_table_info('action_items')"))
                    .contains("id", "meeting_id", "text", "owner_label", "due_date", "status", "source_segment_id", "ai_run_id");
        }
    }

    @Test
    void M2_migrationV3SeedsExactlyFourBuiltInTemplates() throws Exception {
        try (Connection c = open()) {
            assertThat(strings(c, "SELECT lower(name) FROM templates WHERE builtin = 1 ORDER BY name"))
                    .hasSize(4)
                    .anySatisfy(name -> assertThat(name).contains("1:1"))
                    .anySatisfy(name -> assertThat(name).contains("client"))
                    .anySatisfy(name -> assertThat(name).contains("interview"))
                    .anySatisfy(name -> assertThat(name).contains("team"));
            assertThat(strings(c, "SELECT count(*) FROM templates WHERE trim(instructions) = ''")).containsExactly("0");
        }
    }

    @Test
    void M2_migrationV3CreatesNoProviderByDefault() throws Exception {
        try (Connection c = open()) {
            assertThat(strings(c, "SELECT count(*) FROM providers")).containsExactly("0");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "INSERT INTO note_blocks(id,meeting_id,position,kind,origin,content,plain_text,created_at,updated_at) "
                    + "VALUES ('b','m1','a0','paragraph','robot','x','x',1,1)",
            "INSERT INTO note_blocks(id,meeting_id,position,kind,origin,content,plain_text,created_at,updated_at) "
                    + "VALUES ('b','m1','a0','image','user','x','x',1,1)",
            "INSERT INTO note_blocks(id,meeting_id,position,kind,origin,content,plain_text,ai_state,created_at,updated_at) "
                    + "VALUES ('b','m1','a0','paragraph','ai','x','x','rejected',1,1)",
            "INSERT INTO note_snapshots(id,meeting_id,kind,doc,created_at) VALUES ('s','m1','mystery','{}',1)",
            "INSERT INTO providers(id,kind,name,base_url,model,created_at) VALUES ('p','gemini','n','http://x','m',1)",
            "INSERT INTO ai_runs(id,meeting_id,kind,model,prompt_version,status,started_at) "
                    + "VALUES ('r','m1','enhance','m','enhance/v1','weird',1)"
    })
    void M2_migrationV3EnforcesDocumentedCheckConstraints(String insert) throws Exception {
        try (Connection c = open()) {
            meeting(c, "m1");
            rejectsConstraint(c, insert);
        }
    }

    @Test
    void M2_migrationV3CascadesBlocksAndSourcesWithTheirMeeting() throws Exception {
        try (Connection c = open()) {
            meeting(c, "m1");
            segment(c, 1, "m1", "Bonjour");
            block(c, "b1", "m1", "note");
            execute(c, "INSERT INTO block_sources(block_id, segment_id) VALUES ('b1', 1)");
            execute(c, "DELETE FROM meetings WHERE id = 'm1'");
            assertThat(strings(c, "SELECT id FROM note_blocks")).isEmpty();
            assertThat(strings(c, "SELECT block_id FROM block_sources")).isEmpty();
        }
    }

    @Test
    void AC17_ftsTriggersIndexBlocksAccentInsensitivelyOnInsert() throws Exception {
        try (Connection c = open()) {
            meeting(c, "m1");
            block(c, "b1", "m1", "Notes de la réunion du lundi");
            assertThat(blockMatches(c, "reunion")).containsExactly("b1");
            assertThat(blockMatches(c, "réunion")).containsExactly("b1");
            assertThat(blockMatches(c, "absent")).isEmpty();
        }
    }

    @Test
    void AC17_ftsTriggersReindexBlocksOnUpdate() throws Exception {
        try (Connection c = open()) {
            meeting(c, "m1");
            block(c, "b1", "m1", "Notes de la réunion");
            execute(c, "UPDATE note_blocks SET plain_text = 'budget annuel' WHERE id = 'b1'");
            assertThat(blockMatches(c, "reunion")).isEmpty();
            assertThat(blockMatches(c, "budget")).containsExactly("b1");
        }
    }

    @Test
    void AC17_ftsTriggersDropBlocksOnDelete() throws Exception {
        try (Connection c = open()) {
            meeting(c, "m1");
            block(c, "b1", "m1", "Notes de la réunion");
            execute(c, "DELETE FROM note_blocks WHERE id = 'b1'");
            assertThat(blockMatches(c, "reunion")).isEmpty();
        }
    }

    @Test
    void AC17_ftsTriggersIndexSegmentsOnInsertUpdateAndDelete() throws Exception {
        try (Connection c = open()) {
            meeting(c, "m1");
            segment(c, 7, "m1", "On prepare la reunion de lundi");
            assertThat(segmentMatches(c, "reunion")).containsExactly("7");
            execute(c, "UPDATE transcript_segments SET text = 'Le budget est valide' WHERE id = 7");
            assertThat(segmentMatches(c, "reunion")).isEmpty();
            assertThat(segmentMatches(c, "budget")).containsExactly("7");
            execute(c, "DELETE FROM transcript_segments WHERE id = 7");
            assertThat(segmentMatches(c, "budget")).isEmpty();
        }
    }

    @Test
    void AC17_ftsIndexesSegmentsAlreadyStoredByM1WhenV3Applies() throws Exception {
        Path upgraded = directory.resolve("upgrade.db");
        String upgradeUrl = "jdbc:sqlite:" + upgraded;
        Flyway.configure().dataSource(upgradeUrl, null, null)
                .locations("classpath:db/migration", "classpath:db/migration-m1").load().migrate();
        try (Connection c = DriverManager.getConnection(upgradeUrl)) {
            meeting(c, "m1");
            segment(c, 1, "m1", "Une reunion tres importante");
        }
        Flyway.configure().dataSource(upgradeUrl, null, null)
                .locations("classpath:db/migration", "classpath:db/migration-m1", "classpath:db/migration-m2")
                .load().migrate();
        try (Connection c = DriverManager.getConnection(upgradeUrl)) {
            assertThat(segmentMatches(c, "reunion")).containsExactly("1");
        }
    }

    @Test
    void M2_migrationV3UpgradesPopulatedV2DatabaseKeepingM1Rows() throws Exception {
        String upgradeUrl = "jdbc:sqlite:" + directory.resolve("populated.db");
        Flyway.configure().dataSource(upgradeUrl, null, null)
                .locations("classpath:db/migration", "classpath:db/migration-m1").load().migrate();
        try (Connection c = DriverManager.getConnection(upgradeUrl)) {
            meeting(c, "m1");
            execute(c, "INSERT INTO speakers(id,meeting_id,source,label) VALUES ('s1','m1','mic','Me')");
            segment(c, 1, "m1", "Hello");
            execute(c, "INSERT INTO settings(key,value,updated_at) VALUES ('language','fr',100)");
            execute(c, "INSERT INTO recovery_jobs(id,meeting_id,stream,path,offset_samples) VALUES ('j1','m1','mic','/x',0)");
        }
        Flyway.configure().dataSource(upgradeUrl, null, null)
                .locations("classpath:db/migration", "classpath:db/migration-m1", "classpath:db/migration-m2")
                .load().migrate();
        try (Connection c = DriverManager.getConnection(upgradeUrl)) {
            execute(c, "PRAGMA foreign_keys = ON");
            assertThat(strings(c, "SELECT id FROM meetings")).containsExactly("m1");
            assertThat(strings(c, "SELECT label FROM speakers")).containsExactly("Me");
            assertThat(strings(c, "SELECT text FROM transcript_segments")).containsExactly("Hello");
            assertThat(strings(c, "SELECT value FROM settings WHERE key = 'language'")).containsExactly("fr");
            assertThat(strings(c, "SELECT id FROM recovery_jobs")).containsExactly("j1");
            assertThat(strings(c, "SELECT template_id FROM meetings WHERE id = 'm1'")).containsNull();
            assertThat(strings(c, "PRAGMA foreign_key_check")).isEmpty();
        }
    }

    private static java.util.List<String> blockMatches(Connection c, String query) throws Exception {
        return strings(c, "SELECT b.id FROM blocks_fts f JOIN note_blocks b ON b.rowid_fts = f.rowid "
                + "WHERE blocks_fts MATCH ?", query);
    }

    private static java.util.List<String> segmentMatches(Connection c, String query) throws Exception {
        return strings(c, "SELECT rowid FROM segments_fts WHERE segments_fts MATCH ?", query);
    }
}
