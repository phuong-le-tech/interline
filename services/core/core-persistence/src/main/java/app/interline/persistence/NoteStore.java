package app.interline.persistence;

import java.util.List;

/**
 * SQL for note blocks (M2). Skeleton: the behaviour is specified by NoteStoreTest and MigrationV3Test.
 * A user block is created once with its anchor; later writes change content only, and only with a higher client rev.
 */
public final class NoteStore {
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
        throw new UnsupportedOperationException("M2 skeleton");
    }

    /** All blocks of the meeting ordered by position. */
    public List<BlockRow> blocks(String meetingId) {
        throw new UnsupportedOperationException("M2 skeleton");
    }
}
