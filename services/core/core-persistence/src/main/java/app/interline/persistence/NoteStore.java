package app.interline.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.List;
import java.util.Optional;

/**
 * SQL for note blocks. A user block is created once with its anchor; later writes change content only,
 * and only with a higher client rev (idempotent journal replay, last writer wins per block).
 */
public final class NoteStore {
    private static final String ORIGIN_USER = "user";
    private static final String ORIGIN_AI = "ai";
    private static final String STATE_EDITED = "edited";
    private static final String SELECT_BLOCK = "SELECT id, meeting_id, parent_id, position, kind, origin, content, plain_text, "
            + "anchor_ms, client_rev, ai_state FROM note_blocks ";

    private final SqliteDatabase database;

    public NoteStore(SqliteDatabase database) { this.database = database; }

    /** What the UI sends for one block. {@code anchorMs} is honoured on creation only. */
    public record BlockWrite(String id, String kind, String position, String content, String plainText,
                             Long anchorMs, long clientRev) { }

    public record BlockRow(String id, String meetingId, String parentId, String position, String kind, String origin,
                           String content, String plainText, Long anchorMs, long clientRev, String aiState) { }

    /** {@code applied} is false when the write was ignored because its rev was not higher than the stored one. */
    public record Upsert(boolean applied, BlockRow block) { }

    public Upsert putUserBlock(String meetingId, BlockWrite write, long nowMs) {
        return database.write(db -> {
            Optional<BlockRow> existing = find(db, meetingId, write.id());
            if (existing.isEmpty()) {
                db.sql("INSERT INTO note_blocks(id, meeting_id, position, kind, origin, content, plain_text, anchor_ms, "
                                + "client_rev, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                        .params(write.id(), meetingId, write.position(), write.kind(), ORIGIN_USER, write.content(),
                                write.plainText(), write.anchorMs(), write.clientRev(), nowMs, nowMs).update();
                return new Upsert(true, find(db, meetingId, write.id()).orElseThrow());
            }
            BlockRow current = existing.get();
            if (write.clientRev() <= current.clientRev()) return new Upsert(false, current);
            boolean editsAddition = ORIGIN_AI.equals(current.origin());
            db.sql("UPDATE note_blocks SET position = ?, kind = ?, content = ?, plain_text = ?, client_rev = ?, "
                            + "ai_state = CASE WHEN ? THEN ? ELSE ai_state END, updated_at = ? WHERE id = ? AND meeting_id = ?")
                    .params(write.position(), write.kind(), write.content(), write.plainText(), write.clientRev(),
                            editsAddition, STATE_EDITED, nowMs, write.id(), meetingId).update();
            return new Upsert(true, find(db, meetingId, write.id()).orElseThrow());
        });
    }

    /** All blocks of the meeting ordered by position. */
    public List<BlockRow> blocks(String meetingId) {
        return database.read(db -> db.sql(SELECT_BLOCK + "WHERE meeting_id = ? ORDER BY position, rowid_fts")
                .param(meetingId).query(NoteStore::row).list());
    }

    private static Optional<BlockRow> find(JdbcClient db, String meetingId, String blockId) {
        return db.sql(SELECT_BLOCK + "WHERE id = ? AND meeting_id = ?").params(blockId, meetingId)
                .query(NoteStore::row).optional();
    }

    private static BlockRow row(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long anchor = rs.getLong("anchor_ms");
        Long anchorMs = rs.wasNull() ? null : anchor;
        return new BlockRow(rs.getString("id"), rs.getString("meeting_id"), rs.getString("parent_id"),
                rs.getString("position"), rs.getString("kind"), rs.getString("origin"), rs.getString("content"),
                rs.getString("plain_text"), anchorMs, rs.getLong("client_rev"), rs.getString("ai_state"));
    }
}
