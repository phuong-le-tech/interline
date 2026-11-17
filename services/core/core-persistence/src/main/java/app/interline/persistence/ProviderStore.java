package app.interline.persistence;

import java.util.List;
import java.util.Optional;

/** SQL for model providers. The API key is never stored here, only the name of its account in the OS secret store. */
public final class ProviderStore {
    private static final String SELECT = "SELECT id, kind, name, base_url, model, secret_account, supports_json_schema, "
            + "is_default, created_at, context_tokens, price_in_per_mtok, price_out_per_mtok FROM providers ";

    private final SqliteDatabase database;

    public ProviderStore(SqliteDatabase database) { this.database = database; }

    public record ProviderRow(String id, String kind, String name, String baseUrl, String model, String secretAccount,
                              boolean supportsJsonSchema, boolean isDefault, long createdAt, Integer contextTokens,
                              Double priceInPerMtok, Double priceOutPerMtok) {
        public boolean hasKey() { return secretAccount != null; }
    }

    public List<ProviderRow> list() {
        return database.read(db -> db.sql(SELECT + "ORDER BY created_at, id").query(ProviderStore::row).list());
    }

    public Optional<ProviderRow> find(String id) {
        return database.read(db -> db.sql(SELECT + "WHERE id = ?").param(id).query(ProviderStore::row).optional());
    }

    /** The provider an enhancement uses: the one marked default. */
    public Optional<ProviderRow> defaultProvider() {
        return database.read(db -> db.sql(SELECT + "WHERE is_default = 1 ORDER BY created_at LIMIT 1")
                .query(ProviderStore::row).optional());
    }

    /** The first provider saved becomes the default; {@code makeDefault} moves the default to this one. */
    public void insert(ProviderRow row, boolean makeDefault) {
        database.write(db -> {
            boolean first = db.sql("SELECT count(*) FROM providers").query(Long.class).single() == 0;
            boolean asDefault = first || makeDefault;
            if (asDefault) db.sql("UPDATE providers SET is_default = 0").update();
            db.sql("INSERT INTO providers(id, kind, name, base_url, model, secret_account, supports_json_schema, is_default, "
                            + "created_at, context_tokens, price_in_per_mtok, price_out_per_mtok) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(row.id(), row.kind(), row.name(), row.baseUrl(), row.model(), row.secretAccount(),
                            row.supportsJsonSchema() ? 1 : 0, asDefault ? 1 : 0, row.createdAt(), row.contextTokens(),
                            row.priceInPerMtok(), row.priceOutPerMtok()).update();
            return null;
        });
    }

    public void update(ProviderRow row, boolean makeDefault) {
        database.write(db -> {
            if (makeDefault) db.sql("UPDATE providers SET is_default = 0").update();
            db.sql("UPDATE providers SET kind = ?, name = ?, base_url = ?, model = ?, supports_json_schema = ?, "
                            + "context_tokens = ?, price_in_per_mtok = ?, price_out_per_mtok = ?, "
                            + "is_default = CASE WHEN ? THEN 1 ELSE is_default END WHERE id = ?")
                    .params(row.kind(), row.name(), row.baseUrl(), row.model(), row.supportsJsonSchema() ? 1 : 0,
                            row.contextTokens(), row.priceInPerMtok(), row.priceOutPerMtok(), makeDefault, row.id()).update();
            return null;
        });
    }

    public void setSecretAccount(String id, String account) {
        database.write(db -> db.sql("UPDATE providers SET secret_account = ? WHERE id = ?").params(account, id).update());
    }

    /** Deletes the row; when it was the default, the oldest remaining provider takes over. */
    public void delete(String id) {
        database.write(db -> {
            db.sql("DELETE FROM providers WHERE id = ?").param(id).update();
            db.sql("UPDATE providers SET is_default = 1 WHERE id = (SELECT id FROM providers ORDER BY created_at, id LIMIT 1) "
                    + "AND NOT EXISTS (SELECT 1 FROM providers WHERE is_default = 1)").update();
            return null;
        });
    }

    private static ProviderRow row(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        int context = rs.getInt("context_tokens");
        Integer contextTokens = rs.wasNull() ? null : context;
        double in = rs.getDouble("price_in_per_mtok");
        Double priceIn = rs.wasNull() ? null : in;
        double out = rs.getDouble("price_out_per_mtok");
        Double priceOut = rs.wasNull() ? null : out;
        return new ProviderRow(rs.getString("id"), rs.getString("kind"), rs.getString("name"), rs.getString("base_url"),
                rs.getString("model"), rs.getString("secret_account"), rs.getInt("supports_json_schema") != 0,
                rs.getInt("is_default") != 0, rs.getLong("created_at"), contextTokens, priceIn, priceOut);
    }
}
