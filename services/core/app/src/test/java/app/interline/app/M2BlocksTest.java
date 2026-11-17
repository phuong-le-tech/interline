package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static app.interline.app.M2Support.*;
import static org.assertj.core.api.Assertions.assertThat;

/** AC1 and AC3 over the real HTTP API and the real database: PUT /meetings/{id}/blocks/{blockId} with client_rev. */
@Timeout(120)
class M2BlocksTest {
    @TempDir Path data;
    private ConfigurableApplicationContext context;
    private int port;
    private Golden golden;

    @BeforeEach void start() {
        context = boot(data);
        port = port(context);
        golden = golden(1);
        seedMeeting(data, golden);
    }

    @AfterEach void stop() {
        context.close();
    }

    @Test void AC1_blocksPersist_threeBlocksComeBackWithSameIdAnchorOriginAndText() throws Exception {
        putNotes(port, golden);

        List<JsonNode> blocks = blocks(port, golden.meetingId());

        assertThat(blocks).hasSize(3);
        for (int i = 0; i < 3; i++) {
            Note note = golden.notes().get(i);
            JsonNode block = blocks.get(i);
            assertThat(block.path("id").asText()).isEqualTo(note.id());
            assertThat(block.path("anchor_ms").asLong()).isEqualTo(note.anchorMs());
            assertThat(block.path("origin").asText()).isEqualTo("user");
            assertThat(block.path("kind").asText()).isEqualTo(note.kind());
            assertThat(block.path("content").asText()).isEqualTo(note.text());
            assertThat(block.path("plain_text").asText()).isEqualTo(note.text());
        }
        assertThat(rows(data, "SELECT content FROM note_blocks WHERE id = ?", golden.notes().get(0).id()))
                .containsExactly(Map.of("content", golden.notes().get(0).text()));
    }

    @Test void AC1_blocksPersist_laterEditChangesTextButNeverTheAnchor() {
        Note note = golden.notes().get(0);
        putBlock(port, golden.meetingId(), note.id(), 1, "bullet", "a0", "first", note.anchorMs());

        putBlock(port, golden.meetingId(), note.id(), 2, "bullet", "a0", "second", 99_999L);

        JsonNode block = blocks(port, golden.meetingId()).get(0);
        assertThat(block.path("plain_text").asText()).isEqualTo("second");
        assertThat(block.path("anchor_ms").asLong()).isEqualTo(note.anchorMs());
    }

    @Test void AC1_blocksPersist_blockOfAnUnknownMeetingIsRejected() {
        assertThat(putBlock(port, "no-such-meeting", "b1", 1, "paragraph", "a0", "x", 0L).status()).isEqualTo(404);
    }

    @Test void AC1_blocksPersist_putReplyReportsIdRevAndApplied() {
        Note note = golden.notes().get(0);

        JsonNode reply = ok(putBlock(port, golden.meetingId(), note.id(), 7, "bullet", "a0", "x", 1L), "put").json();

        assertThat(reply.path("id").asText()).isEqualTo(note.id());
        assertThat(reply.path("client_rev").asLong()).isEqualTo(7);
        assertThat(reply.path("applied").asBoolean()).isTrue();
    }

    @Test void AC3_idempotentRev_sameRevTwiceIsANoOp() {
        Note note = golden.notes().get(0);
        putBlock(port, golden.meetingId(), note.id(), 2, "bullet", "a0", "v2", note.anchorMs());

        var replay = putBlock(port, golden.meetingId(), note.id(), 2, "bullet", "a0", "replayed", note.anchorMs());

        assertThat(replay.status()).isEqualTo(200);
        assertThat(replay.json().path("applied").asBoolean()).isFalse();
        assertThat(blocks(port, golden.meetingId()).get(0).path("plain_text").asText()).isEqualTo("v2");
    }

    @Test void AC3_idempotentRev_lowerRevNeverOverwrites() {
        Note note = golden.notes().get(0);
        putBlock(port, golden.meetingId(), note.id(), 5, "bullet", "a0", "newest", note.anchorMs());

        var stale = putBlock(port, golden.meetingId(), note.id(), 4, "bullet", "a0", "stale", note.anchorMs());

        assertThat(stale.json().path("applied").asBoolean()).isFalse();
        JsonNode block = blocks(port, golden.meetingId()).get(0);
        assertThat(block.path("plain_text").asText()).isEqualTo("newest");
        assertThat(block.path("client_rev").asLong()).isEqualTo(5);
    }

    @Test void AC3_idempotentRev_higherRevWinsAndReplayInOrderEndsOnTheLastEdit() {
        Note note = golden.notes().get(0);
        for (int rev = 1; rev <= 4; rev++) {
            putBlock(port, golden.meetingId(), note.id(), rev, "bullet", "a0", "typed " + rev, note.anchorMs());
        }
        // The same journal replayed after a reconnect must change nothing.
        for (int rev = 1; rev <= 4; rev++) {
            putBlock(port, golden.meetingId(), note.id(), rev, "bullet", "a0", "typed " + rev, note.anchorMs());
        }

        assertThat(blocks(port, golden.meetingId())).singleElement()
                .satisfies(b -> assertThat(b.path("plain_text").asText()).isEqualTo("typed 4"));
    }

    @ParameterizedTest(name = "AC3_invalidRev_{0}")
    @ValueSource(longs = {0, -1, -100})
    void AC3_invalidRev_zeroOrNegativeIsABadRequest(long rev) {
        var response = putBlock(port, golden.meetingId(), golden.notes().get(0).id(), rev, "bullet", "a0", "x", 0L);

        assertThat(response.status()).isEqualTo(400);
        assertThat(blocks(port, golden.meetingId())).isEmpty();
    }

    @ParameterizedTest(name = "AC3_invalidRev_missing")
    @NullSource
    void AC3_invalidRev_missingRevIsABadRequest(Long rev) {
        var response = putBlock(port, golden.meetingId(), golden.notes().get(0).id(), rev, "bullet", "a0", "x", 0L);

        assertThat(response.status()).isEqualTo(400);
        assertThat(blocks(port, golden.meetingId())).isEmpty();
    }

    @Test void AC3_invalidRev_nonNumericRevIsABadRequest() {
        var response = putBlock(port, golden.meetingId(), golden.notes().get(0).id(), "three", "bullet", "a0", "x", 0L);

        assertThat(response.status()).isEqualTo(400);
    }
}
