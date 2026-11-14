package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static app.interline.app.AppTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** AC5, AC6, AC9 exercise real HTTP, SQLite, audio files and a separately spawned MockAgent. */
class PipelineTest {
    @org.junit.jupiter.api.io.TempDir Path fixtureDirectory;

    @Test void AC5_missingSyntheticFixtureIsGeneratedDeterministicallyWithStereoToneRegions() throws Exception {
        Path wav = fixtureDirectory.resolve("standup-fr-2min.wav");
        Path script = ROOT.resolve("fixtures/audio/standup-fr-2min.transcript.json");

        org.assertj.core.api.Assertions.assertThatCode(() -> app.interline.mockagent.FixtureWav.generateIfMissing(wav, script))
                .as("MockAgent must generate the synthetic fixture on first use").doesNotThrowAnyException();

        assertThat(wav).isRegularFile();
        var audio = javax.sound.sampled.AudioSystem.getAudioFileFormat(wav.toFile());
        assertThat(audio.getFormat().getSampleRate()).isEqualTo(16000.0f);
        assertThat(audio.getFormat().getChannels()).isEqualTo(2);
        assertThat(audio.getFormat().getSampleSizeInBits()).isEqualTo(16);
        assertThat(audio.getFormat().isBigEndian()).isFalse();
        assertThat(audio.getFrameLength()).isEqualTo(1920000);
        byte[] first = Files.readAllBytes(wav);
        Files.delete(wav);
        app.interline.mockagent.FixtureWav.generateIfMissing(wav, script);
        assertThat(Files.readAllBytes(wav)).isEqualTo(first);
        try (var input = javax.sound.sampled.AudioSystem.getAudioInputStream(wav.toFile())) {
            byte[] pcm = input.readAllBytes();
            JsonNode segments = JSON.readTree(script.toFile()).path("segments");
            for (String stream : List.of("mic", "system")) {
                int channel = stream.equals("mic") ? 0 : 1;
                var regions = java.util.stream.StreamSupport.stream(segments.spliterator(), false)
                        .filter(s -> s.path("stream").asText().equals(stream)).toList();
                assertThat(regions).hasSizeBetween(8, 12);
                boolean[] voiced = new boolean[(int) SAMPLES];
                for (JsonNode region : regions) {
                    int start = region.path("start_sample").asInt();
                    int end = region.path("end_sample").asInt();
                    Arrays.fill(voiced, start, end, true);
                    assertThat(java.util.stream.IntStream.range(start, end)
                            .anyMatch(i -> pcm[i * 4 + channel * 2] != 0 || pcm[i * 4 + channel * 2 + 1] != 0))
                            .as("Tone inside %s %s", stream, region.path("seg")).isTrue();
                }
                assertThat(java.util.stream.IntStream.range(0, (int) SAMPLES)
                        .filter(i -> !voiced[i])
                        .allMatch(i -> pcm[i * 4 + channel * 2] == 0 && pcm[i * 4 + channel * 2 + 1] == 0))
                        .as("Exact silence outside %s scripted regions", stream).isTrue();
            }
        }
    }
    @SpringBootTest(classes = InterlineApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
    abstract static class Harness {
        @LocalServerPort int port;
        String start() throws Exception {
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() ->
                    assertThat(ProcessHandle.current().descendants()
                            .filter(p -> p.info().commandLine().orElse("").contains("mock-agent")).toList())
                            .as("Recording must use a real spawned MockAgent, never an in-process replacement")
                            .isNotEmpty());
            String id = request(port, "POST", "/recording/start").path("meetingId").asText();
            assertThat(id).isNotBlank();
            return id;
        }
        void finish(String id, Path dir, Events events) throws Exception {
            // The final scripted event is emitted only once both tracks reach the fixture boundary.
            await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> {
                List<Event> finals = events.received.stream().filter(e -> e.name().equals("stt.final") && e.data().path("meetingId").asText().equals(id)).toList();
                assertThat(finals).anySatisfy(e -> assertThat(e.data().path("seg").asText()).isEqualTo("m-8"));
                assertThat(finals).anySatisfy(e -> assertThat(e.data().path("seg").asText()).isEqualTo("s-8"));
            });
            request(port, "POST", "/recording/stop");
            events.state(id, "done");
            assertThat(rows(dir, "SELECT status FROM meetings WHERE id=?", id)).containsExactly(Map.of("status", "done"));
            JsonNode meetings = request(port, "GET", "/meetings");
            assertThat(meetings.isArray()).as("Meeting list is a JSON array").isTrue();
            assertThat(meetings.findValuesAsText("id")).contains(id);
            assertTranscript(id, dir);
        }
        void assertTranscript(String id, Path dir) throws Exception {
            JsonNode expected = JSON.readTree(ROOT.resolve("fixtures/audio/standup-fr-2min.transcript.json").toFile()).path("segments");
            JsonNode actual = request(port, "GET", "/meetings/" + id + "/transcript");
            assertThat(actual.isArray()).isTrue();
            assertThat(actual.size()).isEqualTo(expected.size());
            Set<String> keys = new HashSet<>();
            for (JsonNode segment : expected) {
                List<JsonNode> matching = new ArrayList<>();
                actual.forEach(a -> { if (a.path("stream").equals(segment.path("stream")) && a.path("seg").equals(segment.path("seg"))) matching.add(a); });
                assertThat(matching).hasSize(1);
                JsonNode a = matching.getFirst();
                assertThat(a.path("text").asText()).isEqualTo(segment.path("text").asText());
                assertThat(a.path("speaker").asText()).isEqualTo(segment.path("stream").asText().equals("mic") ? "Me" : "Them");
                assertThat(a.path("startMs").asLong()).isEqualTo(segment.path("start_sample").asLong() / 16);
                assertThat(a.path("endMs").asLong()).isEqualTo(segment.path("end_sample").asLong() / 16);
                keys.add(segment.path("stream").asText() + ":" + segment.path("seg").asText());
            }
            assertThat(keys).hasSize(expected.size());
            var persisted = rows(dir, "SELECT s.source,t.agent_seg,t.text,t.start_ms,t.end_ms,s.label FROM transcript_segments t JOIN speakers s ON s.id=t.speaker_id WHERE t.meeting_id=?", id);
            assertThat(persisted).hasSize(expected.size());
            for (JsonNode segment : expected) {
                assertThat(persisted).anySatisfy(row -> {
                    assertThat(row.get("source")).isEqualTo(segment.path("stream").asText());
                    assertThat(row.get("agent_seg")).isEqualTo(segment.path("seg").asText());
                    assertThat(row.get("text")).isEqualTo(segment.path("text").asText());
                    assertThat(((Number) row.get("start_ms")).longValue()).isEqualTo(segment.path("start_sample").asLong() / 16);
                    assertThat(((Number) row.get("end_ms")).longValue()).isEqualTo(segment.path("end_sample").asLong() / 16);
                    assertThat(row.get("label")).isEqualTo(segment.path("stream").asText().equals("mic") ? "Me" : "Them");
                });
            }
        }
    }

    @Nested
    class Normal extends Harness {
        static final Path DATA = workspace("pipeline-normal-");
        @DynamicPropertySource static void configure(DynamicPropertyRegistry r) {
            properties(r, DATA, null);
            Path launcher = PipelineAgentProbe.launcher(DATA, false);
            r.add("interline.agent", launcher::toString);
        }
        @Test void AC5_fixtureIsDurableByteExactAndStopDrainsToDone() throws Exception {
            try (PipelineAgentProbe probe = new PipelineAgentProbe(DATA); Events events = new Events(port)) {
                String id = start(); events.state(id, "recording");
                finish(id, DATA, events);
                var acks = probe.observations().stream().filter(o -> o.path("type").asText().equals("audio.ack")).toList();
                assertThat(acks).as("Core must acknowledge real audio").isNotEmpty();
                assertThat(acks).allSatisfy(a -> assertThat(a.path("durable").asBoolean())
                        .as("Acknowledged frame bytes must already be readable from their PCM file: %s", a).isTrue());
                for (String stream : List.of("mic", "system")) {
                    assertThat(acks).anySatisfy(a -> {
                        assertThat(a.path("message").path("body").path("stream").asText()).isEqualTo(stream);
                        assertThat(a.path("message").path("body").path("seq").asLong()).isEqualTo(1199);
                    });
                }
                var runs = rows(DATA, "SELECT * FROM capture_runs WHERE meeting_id=?", id);
                assertThat(runs).hasSize(1);
                Map<String, Object> run = runs.getFirst();
                assertThat(run.get("end_reason")).isEqualTo("stopped");
                for (String stream : List.of("mic", "system")) {
                    assertThat(((Number) run.get("last_seq_" + stream)).longValue()).isEqualTo(1199);
                    assertThat(((Number) run.get("stt_done_" + stream)).longValue()).isEqualTo(SAMPLES);
                    Path track = DATA.resolve("audio").resolve(id).resolve(stream);
                    assertThat(track).isDirectory();
                    List<Path> chunks;
                    try (var files = Files.list(track)) { chunks = files.filter(p -> p.toString().endsWith(".pcm")).sorted(Comparator.comparingLong(p -> Long.parseLong(p.getFileName().toString().replace(".pcm", "")))).toList(); }
                    assertThat(chunks).hasSize(12);
                    for (int i = 0; i < chunks.size(); i++) {
                        assertThat(chunks.get(i).getFileName().toString()).isEqualTo(i + ".pcm");
                        assertThat(Files.readAllBytes(chunks.get(i))).as("%s chunk %s - no holes or duplicate frames", stream, i)
                                .isEqualTo(pcm(stream.equals("mic") ? 0 : 1, i * 160000, 160000));
                    }
                }
                assertThat(rows(DATA, "SELECT * FROM timeline_markers WHERE meeting_id=?", id)).isEmpty();
            }
        }
    }

    @Nested
    class Crash extends Harness {
        static final Path DATA = workspace("pipeline-crash-");
        @DynamicPropertySource static void configure(DynamicPropertyRegistry r) {
            properties(r, DATA, "--crash-at=45s");
            Path launcher = PipelineAgentProbe.launcher(DATA, false);
            r.add("interline.agent", launcher::toString);
        }
        @Test void AC6_crashRecoversSameMeetingExactlyOnceWithoutTranscriptDuplicates() throws Exception {
            try (PipelineAgentProbe probe = new PipelineAgentProbe(DATA); Events events = new Events(port)) {
                String id = start();
                events.state(id, "degraded");
                await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(events.states(id)).containsSubsequence("recording", "degraded", "recording"));
                finish(id, DATA, events);
                var observations = probe.observations();
                var starts = observations.stream().filter(o -> o.path("type").asText().equals("session.start")).toList();
                assertThat(starts).as("Crash at 45 seconds must not recur after recovery").hasSize(2);
                long initialPid = starts.getFirst().path("pid").asLong();
                long resumedPid = starts.getLast().path("pid").asLong();
                assertThat(resumedPid).isNotEqualTo(initialPid);
                JsonNode resumed = starts.getLast().path("message").path("body");
                assertThat(resumed.path("session_id").asText()).isEqualTo(id);
                assertThat(resumed.path("resume").path("previous_spool_dirs").isArray()).isTrue();
                assertThat(resumed.path("resume").path("previous_spool_dirs").size()).isPositive();
                for (String stream : List.of("mic", "system")) {
                    long ack = observations.stream().filter(o -> o.path("pid").asLong() == initialPid)
                            .filter(o -> o.path("type").asText().equals("audio.ack"))
                            .map(o -> o.path("message").path("body")).filter(b -> b.path("stream").asText().equals(stream))
                            .mapToLong(b -> b.path("seq").asLong()).max().orElseThrow();
                    long sttDone = observations.stream().filter(o -> o.path("pid").asLong() == initialPid)
                            .filter(o -> o.path("type").asText().equals("stt.final"))
                            .map(o -> o.path("message").path("body")).filter(b -> b.path("stream").asText().equals(stream))
                            .mapToLong(b -> b.path("end_sample").asLong()).max().orElseThrow();
                    JsonNode resume = resumed.path("resume");
                    long resumeAcked = resume.path("acked").path(stream).asLong(-2);
                    // Last acks can be lost in the dying agent's pipe, so the core may have acked more than the probe saw.
                    assertThat(resumeAcked).isGreaterThanOrEqualTo(ack);
                    assertThat(resume.path("next_seq").path(stream).asLong(-2)).isEqualTo(resumeAcked + 1);
                    var firstRun = rows(DATA, "SELECT * FROM capture_runs WHERE meeting_id=? ORDER BY started_at,id", id).getFirst();
                    assertThat(resumeAcked).isEqualTo(((Number) firstRun.get("last_seq_" + stream)).longValue());
                    assertThat(resume.path("stt_done_until_sample").path(stream).asLong(-2)).isEqualTo(sttDone);
                    var recovered = observations.stream().filter(o -> o.path("pid").asLong() == resumedPid)
                            .filter(o -> o.path("type").asText().equals("audio.chunk"))
                            .filter(o -> o.path("stream").asText().equals(stream))
                            .filter(o -> (o.path("flags").asInt() & 1) != 0).toList();
                    assertThat(recovered).as("Recovered binary frames for %s", stream).isNotEmpty();
                }
                // Recovery must not append replayed PCM twice, even if transcript dedup is correct.
                for (String stream : List.of("mic", "system")) {
                    Path track = DATA.resolve("audio").resolve(id).resolve(stream);
                    assertThat(track).isDirectory();
                    try (var files = Files.list(track); var bytes = new java.io.ByteArrayOutputStream()) {
                        var chunks = files.filter(p -> p.toString().endsWith(".pcm"))
                                .sorted(Comparator.comparingLong(p -> Long.parseLong(p.getFileName().toString().replace(".pcm", "")))).toList();
                        assertThat(chunks).hasSize(12);
                        for (Path chunk : chunks) bytes.write(Files.readAllBytes(chunk));
                        assertThat(bytes.toByteArray()).as("Recovered %s has neither missing nor duplicated fixture frames", stream)
                                .isEqualTo(pcm(stream.equals("mic") ? 0 : 1, 0, (int) SAMPLES));
                    }
                }
                var runs = rows(DATA, "SELECT * FROM capture_runs WHERE meeting_id=? ORDER BY started_at,id", id);
                assertThat(runs).hasSize(2);
                assertThat(runs.get(0).get("end_reason")).isEqualTo("agent_crash");
                assertThat(runs.get(0).get("ended_at")).isNotNull();
                assertThat(runs.get(1).get("end_reason")).isEqualTo("stopped");
                for (String stream : List.of("mic", "system")) {
                    long before = ((Number) runs.getFirst().get("stt_done_" + stream)).longValue();
                    assertThat(before).isBetween(0L, SAMPLES - 1);
                    assertThat(((Number) runs.getLast().get("stt_done_" + stream)).longValue()).isEqualTo(SAMPLES);
                    assertThat(((Number) runs.getLast().get("last_seq_" + stream)).longValue()).isGreaterThan(((Number) runs.getFirst().get("last_seq_" + stream)).longValue());
                }
                var gaps = rows(DATA, "SELECT * FROM timeline_markers WHERE meeting_id=? AND kind='gap'", id);
                assertThat(gaps).hasSize(1);
                assertThat(((Number) gaps.getFirst().get("at_ms")).longValue()).isBetween(44000L, 47000L);
                assertThat(rows(DATA, "SELECT agent_seg FROM transcript_segments WHERE meeting_id=? GROUP BY speaker_id,agent_seg HAVING count(*)>1", id)).isEmpty();
                assertThat(rows(DATA, "SELECT id FROM meetings")).containsExactly(Map.of("id", id));
            }
        }
    }

    @Nested
    class Watchdog extends Harness {
        static final Path DATA = workspace("pipeline-watchdog-");
        /** Proposed duration binding: default 2s, three missed intervals.
         * Backoff stays 500ms,1s,2s,4s,4s; budget stays six crashes in 60s.
         */
        @DynamicPropertySource static void configure(DynamicPropertyRegistry r) {
            properties(r, DATA, null);
            Path launcher = PipelineAgentProbe.launcher(DATA, true);
            r.add("interline.agent", launcher::toString);
            r.add("interline.agent-heartbeat-interval", () -> "50ms");
        }
        @Test void AC8_sixSilentChildrenExhaustBudgetAndPublishCaptureUnavailable() throws Exception {
            try (PipelineAgentProbe probe = new PipelineAgentProbe(DATA); Events events = new Events(port)) {
                await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                        assertThat(events.received).anySatisfy(e -> assertThat(e.name()).isEqualTo("capture.unavailable")));
                assertThat(probe.observations().stream().filter(o -> o.path("type").asText().equals("launch")).toList())
                        .as("Unavailable must not be published before the sixth real launch").hasSize(6);
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    var launches = probe.observations().stream().filter(o -> o.path("type").asText().equals("launch")).toList();
                    assertThat(launches).as("Six real child crashes exhaust the budget").hasSize(6);
                    assertThat(launches).allSatisfy(o -> assertThat(ProcessHandle.of(o.path("pid").asLong())
                            .map(ProcessHandle::isAlive).orElse(false)).isFalse());
                    assertThat(probe.observations().stream().filter(o -> o.path("type").asText().equals("hello.ack")).toList())
                            .as("Each child completed a real handshake before withholding heartbeats").hasSize(6);
                    long first = launches.getFirst().path("nano").asLong();
                    long last = launches.getLast().path("nano").asLong();
                    assertThat(last - first).isLessThan(60_000_000_000L);
                });
                // Observe beyond capped backoff: a seventh launch violates the exhausted budget.
                await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(7)).untilAsserted(() ->
                        assertThat(probe.observations().stream().filter(o -> o.path("type").asText().equals("launch")).toList()).hasSize(6));
            }
        }
    }

    @Nested
    class Permission extends Harness {
        static final Path DATA = workspace("pipeline-deny-");
        @DynamicPropertySource static void configure(DynamicPropertyRegistry r) { properties(r, DATA, "--deny=system_audio"); }
        @Test void AC9_permissionDenialFailsVisiblyAndLeavesNoAgent() throws Exception {
            // Capture the idle agent before starting the denied session.
            Set<ProcessHandle> agents = java.util.concurrent.ConcurrentHashMap.newKeySet();
            try (Events events = new Events(port)) {
                await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
                    ProcessHandle.current().descendants()
                            .filter(p -> p.info().commandLine().orElse("").contains("mock-agent"))
                            .forEach(agents::add);
                    assertThat(agents).as("Spring context must launch the real MockAgent").isNotEmpty();
                });
                String id = start();
                events.state(id, "failed");
                await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                    assertThat(events.received).anySatisfy(e -> {
                        assertThat(e.name()).isEqualTo("recording.error");
                        assertThat(e.data().path("code").asText()).isEqualTo("permission_missing");
                        assertThat(e.data().path("meetingId").asText()).isEqualTo(id);
                    });
                    assertThat(events.received).anySatisfy(e -> {
                        assertThat(e.name()).isEqualTo("capture.unavailable");
                        assertThat(e.data().path("code").asText()).isEqualTo("permission_missing");
                    });
                    assertThat(rows(DATA, "SELECT status FROM meetings WHERE id=?", id)).containsExactly(Map.of("status", "failed"));
                    assertThat(agents).noneMatch(ProcessHandle::isAlive);
                    assertThat(ProcessHandle.current().descendants().filter(p -> p.info().commandLine().orElse("").contains("mock-agent")).toList()).isEmpty();
                });
                assertThat(events.states(id)).doesNotContain("done");
            }
        }
    }
}
