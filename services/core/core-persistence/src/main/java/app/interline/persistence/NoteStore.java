package app.interline.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * SQL for note blocks. A user block is created once with its anchor; later writes change content only,
 * and only with a higher client rev (idempotent journal replay, last writer wins per block).
 */
public final class NoteStore {
    static final String ORIGIN_USER = "user";
    static final String ORIGIN_AI = "ai";
    static final String STATE_PROPOSED = "proposed";
    static final String STATE_ACCEPTED = "accepted";
    static final String STATE_EDITED = "edited";
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

    public enum Acceptance { ACCEPTED, NOT_FOUND, NOT_AN_ADDITION }

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

    /** The user's own blocks, in document order. */
    public List<BlockRow> userBlocks(String meetingId) {
        return blocks(meetingId).stream().filter(b -> ORIGIN_USER.equals(b.origin())).toList();
    }

    /** Segment ids cited by each AI block of the meeting. */
    public Map<String, List<Long>> sources(String meetingId) {
        return database.read(db -> {
            Map<String, List<Long>> sources = new LinkedHashMap<>();
            db.sql("SELECT s.block_id, s.segment_id FROM block_sources s JOIN note_blocks b ON b.id = s.block_id "
                            + "WHERE b.meeting_id = ? ORDER BY s.block_id, s.segment_id").param(meetingId)
                    .query((rs, row) -> Map.entry(rs.getString(1), rs.getLong(2))).list()
                    .forEach(entry -> sources.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue()));
            return sources;
        });
    }

    /** Removes the block and the AI additions that hang under it. Returns false when it does not exist. */
    public boolean delete(String meetingId, String blockId) {
        return database.write(db -> {
            db.sql("DELETE FROM note_blocks WHERE meeting_id = ? AND origin = ? AND parent_id = ?")
                    .params(meetingId, ORIGIN_AI, blockId).update();
            return db.sql("DELETE FROM note_blocks WHERE meeting_id = ? AND id = ?").params(meetingId, blockId).update() > 0;
        });
    }

    /** Marks a proposed addition as accepted; an edited or accepted one keeps its state. */
    public Acceptance accept(String meetingId, String blockId, long nowMs) {
        return database.write(db -> {
            Optional<BlockRow> block = find(db, meetingId, blockId);
            if (block.isEmpty()) return Acceptance.NOT_FOUND;
            if (!ORIGIN_AI.equals(block.get().origin())) return Acceptance.NOT_AN_ADDITION;
            db.sql("UPDATE note_blocks SET ai_state = ?, updated_at = ? WHERE id = ? AND ai_state = ?")
                    .params(STATE_ACCEPTED, nowMs, blockId, STATE_PROPOSED).update();
            return Acceptance.ACCEPTED;
        });
    }

    private static Optional<BlockRow> find(JdbcClient db, String meetingId, String blockId) {
        return db.sql(SELECT_BLOCK + "WHERE id = ? AND meeting_id = ?").params(blockId, meetingId)
                .query(NoteStore::row).optional();
    }

    static BlockRow row(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long anchor = rs.getLong("anchor_ms");
        Long anchorMs = rs.wasNull() ? null : anchor;
        return new BlockRow(rs.getString("id"), rs.getString("meeting_id"), rs.getString("parent_id"),
                rs.getString("position"), rs.getString("kind"), rs.getString("origin"), rs.getString("content"),
                rs.getString("plain_text"), anchorMs, rs.getLong("client_rev"), rs.getString("ai_state"));
    }
}
