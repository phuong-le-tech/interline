package app.interline.persistence;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JDBC helpers shared by the M2 persistence tests (same idiom as MigrationV2UpgradeTest). */
final class SqlSupport {
    private SqlSupport() { }

    static void execute(Connection connection, String sql, Object... params) {
        assertThatCode(() -> {
            try (var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
                statement.executeUpdate();
            }
        }).as("SQL should succeed: %s", sql).doesNotThrowAnyException();
    }

    static void rejectsConstraint(Connection connection, String sql, Object... params) {
        assertThatThrownBy(() -> {
            try (var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
                statement.executeUpdate();
            }
        }).as("SQL should violate a constraint: %s", sql)
                .isInstanceOf(SQLException.class).hasMessageContaining("SQLITE_CONSTRAINT");
    }

    static List<String> strings(Connection connection, String sql, Object... params) throws SQLException {
        List<String> values = new ArrayList<>();
        try (var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) values.add(rows.getString(1));
            }
        }
        return values;
    }

    static void meeting(Connection connection, String id) {
        execute(connection, "INSERT INTO meetings(id,status,created_at,updated_at) VALUES (?, 'done', 100, 100)", id);
    }

    static void block(Connection connection, String id, String meeting, String plainText) {
        execute(connection, "INSERT INTO note_blocks(id,meeting_id,position,kind,origin,content,plain_text,created_at,updated_at) "
                + "VALUES (?, ?, 'a0', 'paragraph', 'user', ?, ?, 100, 100)", id, meeting, plainText, plainText);
    }

    static void segment(Connection connection, long id, String meeting, String text) {
        execute(connection, "INSERT INTO transcript_segments(id,meeting_id,agent_seg,start_ms,end_ms,text,model) "
                + "VALUES (?, ?, ?, 0, 100, ?, 'test')", id, meeting, "seg" + id, text);
    }
}
