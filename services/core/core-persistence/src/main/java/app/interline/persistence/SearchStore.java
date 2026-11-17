package app.interline.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Full-text search over note blocks and transcript segments (FTS5, accents ignored). The user's text is turned into
 * quoted prefix terms, so it can never be read as FTS5 syntax.
 */
public final class SearchStore {
    private static final int LIMIT_PER_KIND = 20;
    private static final int SNIPPET_TOKENS = 12;
    private static final String MARK_START = "«";
    private static final String MARK_END = "»";
    private static final String ELLIPSIS = "…";
    private static final Pattern NOT_TERM = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final SqliteDatabase database;

    public SearchStore(SqliteDatabase database) { this.database = database; }

    /** {@code type} is "block" or "segment"; matches in the snippet are wrapped in « and ». */
    public record Hit(String type, String meetingId, String id, String snippet) { }

    /** Blank queries match nothing; callers reject them first. */
    public List<Hit> search(String query) {
        String match = toMatchExpression(query);
        if (match.isEmpty()) return List.of();
        return database.read(db -> {
            List<Hit> hits = new ArrayList<>();
            hits.addAll(db.sql("SELECT b.meeting_id, b.id, snippet(blocks_fts, 0, ?, ?, ?, ?) FROM blocks_fts "
                            + "JOIN note_blocks b ON b.rowid_fts = blocks_fts.rowid "
                            + "JOIN meetings m ON m.id = b.meeting_id AND m.deleted_at IS NULL "
                            + "WHERE blocks_fts MATCH ? ORDER BY rank LIMIT ?")
                    .params(MARK_START, MARK_END, ELLIPSIS, SNIPPET_TOKENS, match, LIMIT_PER_KIND)
                    .query((rs, row) -> new Hit("block", rs.getString(1), rs.getString(2), rs.getString(3))).list());
            hits.addAll(db.sql("SELECT t.meeting_id, t.id, snippet(segments_fts, 0, ?, ?, ?, ?) FROM segments_fts "
                            + "JOIN transcript_segments t ON t.id = segments_fts.rowid "
                            + "JOIN meetings m ON m.id = t.meeting_id AND m.deleted_at IS NULL "
                            + "WHERE segments_fts MATCH ? AND t.suppressed = 0 ORDER BY rank LIMIT ?")
                    .params(MARK_START, MARK_END, ELLIPSIS, SNIPPET_TOKENS, match, LIMIT_PER_KIND)
                    .query((rs, row) -> new Hit("segment", rs.getString(1), String.valueOf(rs.getLong(2)), rs.getString(3))).list());
            return hits;
        });
    }

    static String toMatchExpression(String query) {
        List<String> terms = new ArrayList<>();
        for (String term : NOT_TERM.split(query)) {
            if (!term.isEmpty()) terms.add("\"" + term + "\"*");
        }
        return String.join(" ", terms);
    }
}
