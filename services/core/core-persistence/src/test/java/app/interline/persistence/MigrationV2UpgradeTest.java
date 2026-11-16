package app.interline.persistence;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

/** M1: populated V1 database upgrades to V2 while keeping rows and cascades. */
class MigrationV2UpgradeTest {
    @TempDir
    Path directory;

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
