package app.interline.app;

import app.interline.agent.AudioChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static app.interline.app.AppTestSupport.*;
import static org.assertj.core.api.Assertions.*;

/** AC7 targets the durable ingress adapter, not a duplicate SQL statement or a mock facade. */
class DedupTest {
    @TempDir Path data;

    private DurableCaptureIngest open() {
        DurableCaptureIngest[] store = new DurableCaptureIngest[1];
        assertThatCode(() -> store[0] = new DurableCaptureIngest(data))
                .as("Production durable ingress must open its real SQLite database and audio store")
                .doesNotThrowAnyException();
        assertThatCode(() -> store[0].openMeeting("m1"))
                .as("Production ingress creates the meeting, speakers and capture run").doesNotThrowAnyException();
        return store[0];
    }

    @Test void AC7_oldestAndLatestFrameReplayReackWithoutAppendingBytesEvenAfterReopen() throws Exception {
        try (var store = open()) {
            for (int seq = 0; seq < 150; seq++) {
                var ack = store.audio("m1", frame(0, seq));
                assertThat(ack.stream()).isZero(); assertThat(ack.seq()).isEqualTo(seq);
                assertThat(ack.window()).isPositive();
            }
            // More than the advertised default window separates oldest from latest.
            for (int seq : new int[]{0, 149, 0, 149}) {
                var ack = store.audio("m1", frame(0, seq));
                assertThat(ack.seq()).as("Re-ack the durable contiguous high watermark").isEqualTo(149);
                assertThat(ack.stream()).isZero();
            }
        }
        byte[] before = track("m1", "mic");
        assertThat(before).isEqualTo(pcm(0, 0, 240000));
        try (var reopened = new DurableCaptureIngest(data)) {
            for (int seq : new int[]{0, 149}) assertThat(reopened.audio("m1", frame(0, seq)).seq()).isEqualTo(149);
        }
        assertThat(track("m1", "mic")).isEqualTo(before);
        var runs = rows(data, "SELECT last_seq_mic FROM capture_runs WHERE meeting_id='m1'");
        assertThat(runs).hasSize(1);
        assertThat(((Number) runs.getFirst().get("last_seq_mic")).longValue()).isEqualTo(149);
    }

    @Test void AC7_frameIdentityIsScopedToMeetingAndStream() throws Exception {
        try (var store = open()) {
            store.openMeeting("m2");
            for (String meeting : List.of("m1", "m2")) for (int stream : new int[]{0, 1}) {
                assertThat(store.audio(meeting, frame(stream, 0)).seq()).isZero();
                assertThat(store.audio(meeting, frame(stream, 0)).seq()).isZero();
            }
        }
        for (String meeting : List.of("m1", "m2")) {
            assertThat(track(meeting, "mic")).isEqualTo(pcm(0, 0, 1600));
            assertThat(track(meeting, "system")).isEqualTo(pcm(1, 0, 1600));
        }
    }

    @Test void AC7_concurrentFinalReplayPersistsOnceWithStreamAndMeetingIsolation() throws Exception {
        try (var store = open(); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            store.openMeeting("m2");
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<?>> calls = new ArrayList<>();
            for (int i = 0; i < 32; i++) calls.add(pool.submit(() -> {
                gate.await(); store.finalSegment("m1", "mic", "same-seg", 0, 1600, "Bonjour."); return null;
            }));
            gate.countDown();
            for (var call : calls) assertThatCode(() -> call.get(5, TimeUnit.SECONDS)).doesNotThrowAnyException();
            store.finalSegment("m1", "system", "same-seg", 0, 1600, "Bonsoir.");
            store.finalSegment("m2", "mic", "same-seg", 0, 1600, "Autre reunion.");
            store.finalSegment("m1", "mic", "next-seg", 1600, 3200, "Suite.");
            // A stale duplicate must neither overwrite text nor regress the STT watermark.
            store.finalSegment("m1", "mic", "same-seg", 0, 1600, "Bonjour.");
        }
        var finals = rows(data, "SELECT t.meeting_id,s.source,t.agent_seg,t.text FROM transcript_segments t JOIN speakers s ON s.id=t.speaker_id");
        assertThat(finals).containsExactlyInAnyOrder(
                Map.of("meeting_id", "m1", "source", "mic", "agent_seg", "same-seg", "text", "Bonjour."),
                Map.of("meeting_id", "m1", "source", "system", "agent_seg", "same-seg", "text", "Bonsoir."),
                Map.of("meeting_id", "m2", "source", "mic", "agent_seg", "same-seg", "text", "Autre reunion."),
                Map.of("meeting_id", "m1", "source", "mic", "agent_seg", "next-seg", "text", "Suite."));
        var runs = rows(data, "SELECT stt_done_mic,stt_done_system FROM capture_runs WHERE meeting_id='m1'");
        assertThat(runs).hasSize(1);
        assertThat(((Number) runs.getFirst().get("stt_done_mic")).longValue()).isEqualTo(3200);
        assertThat(((Number) runs.getFirst().get("stt_done_system")).longValue()).isEqualTo(1600);
    }

    private static AudioChunk frame(int stream, int seq) throws java.io.IOException {
        return new AudioChunk(stream, 0, seq, seq * 1600L, 0, pcm(stream, seq * 1600, 1600));
    }
    private byte[] track(String meeting, String stream) throws Exception {
        Path dir = data.resolve("audio").resolve(meeting).resolve(stream);
        assertThat(dir).isDirectory();
        try (var files = Files.list(dir); var bytes = new java.io.ByteArrayOutputStream()) {
            for (Path file : files.filter(p -> p.toString().endsWith(".pcm")).sorted(Comparator.comparingLong(p -> Long.parseLong(p.getFileName().toString().replace(".pcm", "")))).toList()) bytes.write(Files.readAllBytes(file));
            return bytes.toByteArray();
        }
    }
}
