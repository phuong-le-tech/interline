package app.interline.persistence;

import java.util.List;
import java.util.Optional;

/** SQL for meeting templates: the built-in ones seeded by the migration. */
public final class TemplateStore {
    private final SqliteDatabase database;

    public TemplateStore(SqliteDatabase database) { this.database = database; }

    public record TemplateRow(String id, String name, String instructions, boolean builtin) { }

    public List<TemplateRow> list() {
        return database.read(db -> db.sql("SELECT id, name, instructions, builtin FROM templates ORDER BY builtin DESC, name")
                .query(TemplateStore::row).list());
    }

    public Optional<TemplateRow> find(String id) {
        return database.read(db -> db.sql("SELECT id, name, instructions, builtin FROM templates WHERE id = ?").param(id)
                .query(TemplateStore::row).optional());
    }

    private static TemplateRow row(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new TemplateRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4) != 0);
    }
}
