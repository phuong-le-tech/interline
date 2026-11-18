package app.interline.persistence;

import app.interline.persistence.NoteStore.BlockWrite;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** M2 persistence: user block upserts through the real SqliteDatabase and Flyway migrations. */
class NoteStoreTest {
    @TempDir
    Path directory;

    private SqliteDatabase database;
    private NoteStore store;

    @BeforeEach
    void open() {
        database = SqliteDatabase.open(directory.resolve("db.sqlite"));
        store = new NoteStore(database);
        database.write(db -> db.sql("INSERT INTO meetings(id,status,created_at,updated_at) VALUES ('m1','done',100,100)").update());
    }

    private static BlockWrite write(String id, String content, Long anchorMs, long rev) {
        return new BlockWrite(id, "paragraph", "a0", content, content, anchorMs, rev);
    }

    @Test
    void AC1_anchorImmutable_laterEditsKeepTheAnchorSetAtCreation() {
        store.putUserBlock("m1", write("b1", "first draft", 5_000L, 1), 1_000);

        var edited = store.putUserBlock("m1", write("b1", "second draft", 9_999L, 2), 2_000);

        assertThat(edited.applied()).isTrue();
        assertThat(edited.block().plainText()).isEqualTo("second draft");
        assertThat(edited.block().anchorMs()).isEqualTo(5_000L);
        assertThat(store.blocks("m1")).singleElement().satisfies(row -> {
            assertThat(row.anchorMs()).isEqualTo(5_000L);
            assertThat(row.origin()).isEqualTo("user");
            assertThat(row.clientRev()).isEqualTo(2);
        });
    }

    @Test
    void AC1_emptyTextBlockPersists() {
        store.putUserBlock("m1", write("b1", "", 0L, 1), 1_000);

        assertThat(store.blocks("m1")).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo("b1");
            assertThat(row.content()).isEmpty();
            assertThat(row.plainText()).isEmpty();
            assertThat(row.anchorMs()).isZero();
        });
    }

    @Test
    void AC1_textIsStoredByteForByteIncludingUnicodeAndTrailingSpaces() {
        String text = "Budget T3 validé, réunion cadrage  \u2192 ñ";
        store.putUserBlock("m1", write("b1", text, 10L, 1), 1_000);

        assertThat(store.blocks("m1")).singleElement().satisfies(row -> {
            assertThat(row.content()).isEqualTo(text);
            assertThat(row.plainText()).isEqualTo(text);
        });
    }

    @Test
    void AC3_idempotentRev_sameRevTwiceIsANoOp() {
        store.putUserBlock("m1", write("b1", "kept", 1L, 3), 1_000);

        var replay = store.putUserBlock("m1", write("b1", "different", 1L, 3), 2_000);

        assertThat(replay.applied()).isFalse();
        assertThat(store.blocks("m1")).singleElement().satisfies(row -> assertThat(row.plainText()).isEqualTo("kept"));
    }

    @Test
    void AC3_idempotentRev_lowerRevNeverOverwrites() {
        store.putUserBlock("m1", write("b1", "newest", 1L, 5), 1_000);

        var stale = store.putUserBlock("m1", write("b1", "stale", 1L, 4), 2_000);

        assertThat(stale.applied()).isFalse();
        assertThat(store.blocks("m1")).singleElement().satisfies(row -> {
            assertThat(row.plainText()).isEqualTo("newest");
            assertThat(row.clientRev()).isEqualTo(5);
        });
    }

    @Test
    void AC3_idempotentRev_higherRevWins() {
        store.putUserBlock("m1", write("b1", "old", 1L, 1), 1_000);

        var next = store.putUserBlock("m1", write("b1", "new", 1L, 2), 2_000);

        assertThat(next.applied()).isTrue();
        assertThat(store.blocks("m1")).singleElement().satisfies(row -> assertThat(row.plainText()).isEqualTo("new"));
    }
}
