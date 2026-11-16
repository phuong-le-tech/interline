package app.interline.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AC13: real Flyway migrations against a temporary on-disk SQLite database. */
class MigrationTest {
    @TempDir
    Path directory;

    private Path database;
    private String url;

    @BeforeEach
    void migrateFreshDatabase() {
        database = directory.resolve("interline.db");
        url = "jdbc:sqlite:" + database;
        flyway().migrate();
    }

    @Test
    void AC13_createsExactlyTheSixM0Tables() throws Exception {
        assertThat(Files.isRegularFile(database)).isTrue();
        try (Connection connection = open()) {
            assertThat(strings(connection, "SELECT name FROM sqlite_master WHERE type = 'table' "
                    + "AND name NOT LIKE 'sqlite_%' AND name <> 'flyway_schema_history'"))
                    .containsExactlyInAnyOrder("meetings", "capture_runs", "speakers",
                            "transcript_segments", "timeline_markers", "settings");
        }
    }

    @Test
    void AC13_excludesDeferredMeetingReferences() throws Exception {
        try (Connection connection = open()) {
            assertThat(strings(connection, "SELECT name FROM pragma_table_info('meetings')"))
                    .contains("id", "title", "status", "language", "started_at", "ended_at",
                            "t0_wall_ms", "audio_dir", "audio_purge_at", "created_at", "updated_at", "deleted_at")
                    .doesNotContain("calendar_event_id", "template_id");
        }
    }

    @Test
    void AC13_hasAllDocumentedM0Columns() throws Exception {
        try (Connection connection = open()) {
            assertThat(strings(connection, "SELECT name FROM pragma_table_info('capture_runs')"))
                    .contains("id", "meeting_id", "agent_name", "agent_version", "protocol", "started_at",
                            "ended_at", "end_reason", "last_seq_mic", "last_seq_system", "stt_done_mic", "stt_done_system");
            assertThat(strings(connection, "SELECT name FROM pragma_table_info('speakers')"))
                    .contains("id", "meeting_id", "source", "label");
            // person_id itself may be omitted until people ships; its FK must not block M0 inserts.
            assertThat(strings(connection, "SELECT name FROM pragma_table_info('transcript_segments')"))
                    .contains("id", "meeting_id", "speaker_id", "agent_seg", "start_ms", "end_ms", "text",
                            "text_edited", "avg_logprob", "model", "suppressed", "recovered");
            assertThat(strings(connection, "SELECT name FROM pragma_table_info('timeline_markers')"))
                    .contains("id", "meeting_id", "at_ms", "kind", "detail");
            assertThat(strings(connection, "SELECT name FROM pragma_table_info('settings')"))
                    .contains("key", "value", "updated_at");
        }
    }

    @Test
    void AC13_keepsWalJournalModeAfterReopening() throws Exception {
        try (Connection connection = open()) {
            assertThat(strings(connection, "PRAGMA journal_mode")).containsExactly("wal");
        }
        try (Connection reopened = open()) {
            assertThat(strings(reopened, "PRAGMA journal_mode")).containsExactly("wal");
        }
    }

    @Test
    void AC13_reopensWithoutLosingDataOrReapplyingV1() throws Exception {
        try (Connection connection = open()) {
            meeting(connection, "m1", "draft");
            execute(connection, "INSERT INTO settings(key, value, updated_at) VALUES ('language', 'fr', 123)");
        }
        assertThat(flyway().migrate().migrationsExecuted).isZero();
        assertThat(flyway().info().current().getVersion().getVersion()).isEqualTo("1");
        try (Connection reopened = open()) {
            assertThat(strings(reopened, "SELECT id FROM meetings")).containsExactly("m1");
            assertThat(strings(reopened, "SELECT value FROM settings WHERE key = 'language'"))
                    .containsExactly("fr");
            assertThat(strings(reopened, "SELECT version FROM flyway_schema_history WHERE success = 1"))
                    .containsExactly("1");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"draft", "recording", "degraded", "processing", "done", "failed"})
    void AC13_acceptsDocumentedMeetingStatuses(String status) throws Exception {
        try (Connection connection = open()) {
            meeting(connection, "m1", status);
            assertThat(strings(connection, "SELECT title FROM meetings WHERE id = 'm1'"))
                    .containsExactly("");
        }
    }

    @Test
    void AC13_rejectsUnknownMeetingStatus() throws Exception {
        try (Connection connection = open()) {
            meeting(connection, "m1", "draft");
            rejectsConstraint(connection, "UPDATE meetings SET status = 'unknown' WHERE id = 'm1'");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE meetings SET status = NULL", "UPDATE meetings SET title = NULL",
            "UPDATE meetings SET created_at = NULL", "UPDATE meetings SET updated_at = NULL",
            "UPDATE capture_runs SET meeting_id = NULL", "UPDATE capture_runs SET agent_name = NULL",
            "UPDATE capture_runs SET agent_version = NULL", "UPDATE capture_runs SET protocol = NULL",
            "UPDATE capture_runs SET started_at = NULL", "UPDATE speakers SET meeting_id = NULL",
            "UPDATE speakers SET source = NULL", "UPDATE speakers SET label = NULL",
            "UPDATE transcript_segments SET meeting_id = NULL", "UPDATE transcript_segments SET agent_seg = NULL",
            "UPDATE transcript_segments SET start_ms = NULL", "UPDATE transcript_segments SET end_ms = NULL",
            "UPDATE transcript_segments SET text = NULL", "UPDATE transcript_segments SET model = NULL",
            "UPDATE transcript_segments SET suppressed = NULL", "UPDATE transcript_segments SET recovered = NULL",
            "UPDATE timeline_markers SET meeting_id = NULL", "UPDATE timeline_markers SET at_ms = NULL",
            "UPDATE timeline_markers SET kind = NULL", "UPDATE settings SET value = NULL",
            "UPDATE settings SET updated_at = NULL"
    })
    void AC13_enforcesRequiredFields(String update) throws Exception {
        try (Connection connection = open()) {
            fixture(connection);
            rejectsConstraint(connection, update);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mic", "system", "import"})
    void AC13_acceptsSpeakerSourcesWithoutADeferredPeopleTable(String source) throws Exception {
        try (Connection connection = open()) {
            meeting(connection, "m1", "recording");
            execute(connection, "INSERT INTO speakers(id, meeting_id, source, label) VALUES ('s1','m1','"
                    + source + "','Speaker')");
            // M0 must not retain a foreign key to the absent people table, even for NULL values.
            assertThat(strings(connection, "SELECT id FROM speakers")).containsExactly("s1");
        }
    }

    @Test
    void AC13_rejectsUnknownSpeakerSource() throws Exception {
        try (Connection connection = open()) {
            fixture(connection);
            rejectsConstraint(connection, "UPDATE speakers SET source = 'unknown'");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stopped", "agent_crash", "core_crash", "app_crash", "paused"})
    void AC13_acceptsCaptureEndReasons(String reason) throws Exception {
        try (Connection connection = open()) {
            fixture(connection);
            execute(connection, "UPDATE capture_runs SET end_reason = '" + reason + "'");
            assertThat(strings(connection, "SELECT end_reason FROM capture_runs")).containsExactly(reason);
        }
    }

    @Test
    void AC13_rejectsUnknownCaptureEndReason() throws Exception {
        try (Connection connection = open()) {
            fixture(connection);
            rejectsConstraint(connection, "UPDATE capture_runs SET end_reason = 'unknown'");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"gap", "device_change", "pause", "resume"})
    void AC13_acceptsTimelineMarkerKinds(String kind) throws Exception {
        try (Connection connection = open()) {
            fixture(connection);
            execute(connection, "UPDATE timeline_markers SET kind = '" + kind + "'");
            assertThat(strings(connection, "SELECT kind FROM timeline_markers")).containsExactly(kind);
        }
    }

    @Test
    void AC13_rejectsUnknownTimelineMarkerKind() throws Exception {
        try (Connection connection = open()) {
            fixture(connection);
            rejectsConstraint(connection, "UPDATE timeline_markers SET kind = 'unknown'");
        }
    }

    @Test
    void AC13_defaultsTranscriptFlagsToZero() throws Exception {
        try (Connection connection = open()) {
            fixture(connection);
            assertThat(strings(connection, "SELECT suppressed || ':' || recovered FROM transcript_segments"))
                    .containsExactly("0:0");
        }
    }

    @Test
    void AC13_enforcesSegmentDedupWithinMeetingAndSpeakerScope() throws Exception {
        try (Connection connection = open()) {
            fixture(connection);
            rejectsConstraint(connection, segmentSql("m1", "s1", "seg1"));
            speaker(connection, "s2", "m1");
            execute(connection, segmentSql("m1", "s2", "seg1"));
            meeting(connection, "m2", "recording");
            speaker(connection, "s3", "m2");
            execute(connection, segmentSql("m2", "s3", "seg1"));
            // Hold speaker constant to independently prove meeting_id participates in the unique key.
            execute(connection, segmentSql("m2", "s1", "seg1"));
            execute(connection, segmentSql("m1", "s1", "seg2"));
            assertThat(strings(connection, "SELECT agent_seg FROM transcript_segments")).hasSize(5);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE capture_runs SET meeting_id = 'missing'", "UPDATE speakers SET meeting_id = 'missing'",
            "UPDATE transcript_segments SET meeting_id = 'missing'",
            "UPDATE transcript_segments SET speaker_id = 'missing'",
            "UPDATE timeline_markers SET meeting_id = 'missing'"
    })
    void AC13_enforcesForeignKeys(String update) throws Exception {
        try (Connection connection = open()) {
            fixture(connection);
            rejectsConstraint(connection, update);
        }
    }

    @Test
    void AC13_cascadesMeetingDeletionWithoutDeletingOtherMeetingsOrSettings() throws Exception {
        try (Connection connection = open()) {
            fixture(connection);
            meeting(connection, "m2", "draft");
            speaker(connection, "s2", "m2");
            execute(connection, segmentSql("m2", "s2", "seg2"));
            execute(connection, "DELETE FROM meetings WHERE id = 'm1'");
            assertThat(strings(connection, "SELECT id FROM meetings")).containsExactly("m2");
            assertThat(strings(connection, "SELECT id FROM capture_runs")).isEmpty();
            assertThat(strings(connection, "SELECT id FROM timeline_markers")).isEmpty();
            assertThat(strings(connection, "SELECT id FROM speakers")).containsExactly("s2");
            assertThat(strings(connection, "SELECT agent_seg FROM transcript_segments")).containsExactly("seg2");
            assertThat(strings(connection, "SELECT key FROM settings")).containsExactly("language");
            assertThat(strings(connection, "PRAGMA foreign_key_check")).isEmpty();
        }
    }

    @Test
    void AC13_setsSegmentSpeakerToNullWhenSpeakerIsDeleted() throws Exception {
        try (Connection connection = open()) {
            fixture(connection);
            execute(connection, "DELETE FROM speakers WHERE id = 's1'");
            assertThat(strings(connection, "SELECT agent_seg FROM transcript_segments WHERE speaker_id IS NULL"))
                    .containsExactly("seg1");
        }
    }

    @Test
    void AC13_enforcesUniqueSettingKeys() throws Exception {
        try (Connection connection = open()) {
            fixture(connection);
            rejectsConstraint(connection, "INSERT INTO settings(key, value, updated_at) VALUES ('language','en',2)");
        }
    }

    @Test
    void AC13_shippedSqliteHasUsableAccentInsensitiveFts5() throws Exception {
        try (Connection connection = open()) {
            assertThat(strings(connection, "SELECT sqlite_compileoption_used('ENABLE_FTS5')")).containsExactly("1");
            // A TEMP probe verifies the bundled engine without adding deferred FTS tables to V1.
            execute(connection, "CREATE VIRTUAL TABLE temp.ac13_fts USING fts5(text, "
                    + "tokenize='unicode61 remove_diacritics 2')");
            execute(connection, "INSERT INTO ac13_fts(text) VALUES ('R\u00e9union caf\u00e9 cr\u00e8me')");
            assertThat(strings(connection, "SELECT text FROM ac13_fts WHERE ac13_fts MATCH 'reunion AND cafe AND creme'"))
                    .containsExactly("R\u00e9union caf\u00e9 cr\u00e8me");
            assertThat(strings(connection, "SELECT text FROM ac13_fts WHERE ac13_fts MATCH 'unrelated'"))
                    .isEmpty();
        }
    }

    @Test
    void M1_upgradesPopulatedV1DatabaseToV2KeepingRowsAndCascades() throws Exception {
        String upgradeUrl = "jdbc:sqlite:" + directory.resolve("upgrade.db");
        Flyway.configure().dataSource(upgradeUrl, null, null)
                .locations("classpath:db/migration").load().migrate();
        try (Connection connection = DriverManager.getConnection(upgradeUrl)) {
            fixture(connection);
        }

        Flyway.configure().dataSource(upgradeUrl, null, null)
                .locations("classpath:db/migration", "classpath:db/migration-m1").load().migrate();

        try (Connection connection = DriverManager.getConnection(upgradeUrl)) {
            execute(connection, "PRAGMA foreign_keys = ON");
            assertThat(strings(connection, "SELECT id FROM meetings")).containsExactly("m1");
            assertThat(strings(connection, "SELECT agent_seg FROM transcript_segments")).containsExactly("seg1");
            assertThat(strings(connection, "PRAGMA foreign_key_check")).isEmpty();
            execute(connection, "UPDATE meetings SET status = 'recovered' WHERE id = 'm1'");
            rejectsConstraint(connection, "UPDATE meetings SET status = 'unknown' WHERE id = 'm1'");
            execute(connection, "DELETE FROM meetings WHERE id = 'm1'");
            assertThat(strings(connection, "SELECT id FROM capture_runs")).isEmpty();
            assertThat(strings(connection, "SELECT id FROM speakers")).isEmpty();
            assertThat(strings(connection, "SELECT agent_seg FROM transcript_segments")).isEmpty();
        }
    }

    private Flyway flyway() {
        return Flyway.configure().dataSource(url, null, null)
                .locations("classpath:db/migration").load();
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection(url);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }
        assertThat(strings(connection, "PRAGMA foreign_keys")).containsExactly("1");
        return connection;
    }

    private static void fixture(Connection connection) {
        meeting(connection, "m1", "recording");
        speaker(connection, "s1", "m1");
        execute(connection, "INSERT INTO capture_runs(id,meeting_id,agent_name,agent_version,protocol,started_at) "
                + "VALUES ('r1','m1','mock','1.0',1,100)");
        execute(connection, segmentSql("m1", "s1", "seg1"));
        execute(connection, "INSERT INTO timeline_markers(meeting_id,at_ms,kind) VALUES ('m1',100,'gap')");
        execute(connection, "INSERT INTO settings(key,value,updated_at) VALUES ('language','fr',100)");
    }

    private static void meeting(Connection connection, String id, String status) {
        execute(connection, "INSERT INTO meetings(id,status,created_at,updated_at) VALUES ('"
                + id + "','" + status + "',100,100)");
    }

    private static void speaker(Connection connection, String id, String meeting) {
        execute(connection, "INSERT INTO speakers(id,meeting_id,source,label) VALUES ('"
                + id + "','" + meeting + "','mic','Speaker')");
    }

    private static String segmentSql(String meeting, String speaker, String segment) {
        return "INSERT INTO transcript_segments(meeting_id,speaker_id,agent_seg,start_ms,end_ms,text,model) "
                + "VALUES ('" + meeting + "','" + speaker + "','" + segment + "',0,100,'Hello','test')";
    }

    private static void execute(Connection connection, String sql) {
        assertThatCode(() -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        }).as("SQL should succeed: %s", sql).doesNotThrowAnyException();
    }

    private static void rejectsConstraint(Connection connection, String sql) {
        assertThatThrownBy(() -> {
            try (var statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        }).as("SQL should violate a constraint: %s", sql)
                .isInstanceOf(SQLException.class).hasMessageContaining("SQLITE_CONSTRAINT");
    }

    private static List<String> strings(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }
}
