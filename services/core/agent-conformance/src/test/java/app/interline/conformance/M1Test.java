package app.interline.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.*;

/** Black-box checks: independent framing oracle, actual executable and OS pipes, no in-process agent. */
@Timeout(40)
class M1Test {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SESSION = "m-conformance";
    private static final String[] STREAMS = {"mic", "system"};
    private static final int SAMPLES = 128000;
    @TempDir Path work;

    @Test void AC4_contract_helloWithinOneSecondHasVersionIdentityAndCapabilities() throws Exception {
        try (Peer p = launch()) {
            Packet hello = p.next(1000);
            assertThat(hello.at - p.spawned).as("hello deadline, measured from process launch").isLessThanOrEqualTo(1_000_000_000L);
            assertThat(hello.type()).isEqualTo("hello");
            assertThat(hello.json.path("id").asText()).startsWith("a-");
            JsonNode b = hello.body();
            assertThat(b.path("protocol").path("min").asInt()).isLessThanOrEqualTo(1).isPositive();
            assertThat(b.path("protocol").path("max").asInt()).isGreaterThanOrEqualTo(1);
            for (String key : List.of("name", "version", "os", "os_version", "arch"))
                assertThat(b.path("agent").path(key).asText()).as(key).isNotBlank();
            assertThat(b.path("capabilities").path("test_input").asBoolean()).isTrue();
            assertThat(b.path("capabilities").path("capture").path("streams").toString()).contains("mic", "system");
            p.ackHello(hello);
            p.shutdown();
        }
    }

    @Test void AC4_contract_helloRejectExitsThreeWithinTwoSeconds() throws Exception {
        try (Peer p = launch()) {
            Packet hello = p.next(1000);
            ObjectNode body = obj().put("reason", "protocol_version_unsupported");
            body.putObject("core_supports").put("min", 2).put("max", 2);
            p.send(null, "hello.reject", hello.json.path("id").asText(), body);
            p.exit(3, 2000);
        }
    }

    @ParameterizedTest(name = "AC4_contract_heartbeatEveryTwoSeconds_{0}")
    @ValueSource(booleans = {false, true})
    void AC4_contract_heartbeatEveryTwoSecondsIdleOrCapturing(boolean capturing) throws Exception {
        try (Peer p = connected()) {
            if (capturing) p.start(50);
            Packet previous = p.until("heartbeat", 2200);
            for (int i = 0; i < 3; i++) {
                Packet current = p.until("heartbeat", 2200);
                // 200 ms scheduling tolerance, not the six-second hung watchdog threshold.
                assertThat(current.at - previous.at).isBetween(1_800_000_000L, 2_200_000_000L);
                assertThat(current.json.has("id") || current.json.has("re")).isFalse();
                for (String key : List.of("stt_backlog_ms", "rss_mb")) {
                    assertThat(current.body().path(key).isIntegralNumber()).as(key).isTrue();
                    assertThat(current.body().path(key).asLong()).isNotNegative();
                }
                previous = current;
            }
        }
    }

    @Test void AC4_contract_audioHasContiguousPerStreamSequenceTimelineAnd100msPcm() throws Exception {
        try (Peer p = connected()) {
            p.start(50);
            long[] seq = {0, 0};
            long[] host = {0, 0};
            while (seq[0] < 12 || seq[1] < 12) {
                Packet packet = p.next(2000);
                if (packet.kind != 2) continue;
                Audio a = packet.audio();
                assertThat(a.stream).isBetween(0, 1);
                assertThat(a.flags).isZero();
                assertThat(a.reserved).isZero();
                assertThat(a.seq).isEqualTo(seq[a.stream]++);
                assertThat(a.sample).isEqualTo(a.seq * 1600);
                assertThat(a.pcm).hasSize(3200);
                assertThat(a.host).isGreaterThanOrEqualTo(host[a.stream]);
                host[a.stream] = a.host;
                // The default fixture is a known per-channel sine, not arbitrary nonzero bytes.
                if (p.mock) {
                    ByteBuffer pcm = ByteBuffer.wrap(a.pcm).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < 1600; i++)
                        assertThat(pcm.getShort()).isEqualTo(tone(a.sample + i, a.stream));
                }
                p.ack(a, 50);
            }
            assertThat(seq).containsExactly(12, 12);
        }
    }

    @ParameterizedTest(name = "AC4_contract_windowBoundsAndReopensWithoutLoss_{0}")
    @ValueSource(ints = {3, 50})
    void AC4_contract_windowBoundsAndReopensWithoutLoss(int window) throws Exception {
        try (Peer p = connected()) {
            p.start(window);
            long[] counts = {0, 0};
            for (Packet packet : p.collect(window * 100L + 700)) {
                if (packet.kind == 2) {
                    Audio a = packet.audio();
                    assertThat(a.seq).isEqualTo(counts[a.stream]++);
                }
            }
            assertThat(counts).as("exactly the negotiated unacked window per stream").containsExactly(window, window);
            for (int stream = 0; stream < 2; stream++)
                p.send(null, "audio.ack", null, obj().put("stream", STREAMS[stream]).put("seq", window - 1).put("window", window));
            long[] replay = {window, window};
            while (replay[0] < window + 3 || replay[1] < window + 3) {
                Packet packet = p.next(2000);
                if (packet.kind != 2) continue;
                Audio a = packet.audio();
                assertThat(a.seq).isEqualTo(replay[a.stream]++);
                assertThat(a.sample).isEqualTo(a.seq * 1600);
                p.ack(a, window);
            }
        }
    }

    @Test void AC4_contract_levelsAreTenHzPerStreamAndFiniteRms() throws Exception {
        try (Peer p = connected()) {
            p.start(50);
            List<Packet> packets = p.collect(2200);
            for (String stream : STREAMS) {
                List<Packet> levels = packets.stream().filter(e -> e.type().equals("level") && e.body().path("stream").asText().equals(stream)).toList();
                assertThat(levels).as("10 Hz level events for %s over 2.2 s", stream).hasSizeBetween(19, 25);
                for (Packet level : levels) {
                    assertThat(level.body().path("session_id").asText()).isEqualTo(SESSION);
                    assertThat(level.body().path("rms").isNumber()).isTrue();
                    assertThat(level.body().path("rms").asDouble()).isFinite().isBetween(0.0, 1.0);
                }
                for (int i = 1; i < levels.size(); i++)
                    assertThat(levels.get(i).at - levels.get(i - 1).at).isBetween(40_000_000L, 180_000_000L);
            }
        }
    }

    @Test void AC4_contract_partialsHaveIncreasingRevisionsAndAreReplacedByMatchingFinal() throws Exception {
        try (Peer p = connected()) {
            p.start(50);
            List<Packet> packets = p.captureToEnd();
            Map<String, Long> revisions = new HashMap<>();
            Map<String, Integer> counts = new HashMap<>();
            Set<String> finalized = new HashSet<>();
            for (Packet e : packets) {
                if (e.type().equals("stt.partial")) {
                    String key = segmentKey(e);
                    assertThat(finalized).doesNotContain(key);
                    assertThat(e.body().path("rev").isIntegralNumber()).isTrue();
                    long revision = e.body().path("rev").asLong();
                    assertThat(revision).isGreaterThan(revisions.getOrDefault(key, -1L));
                    assertThat(e.body().path("text").asText()).isNotBlank();
                    revisions.put(key, revision);
                    counts.merge(key, 1, Integer::sum);
                }
                if (e.type().equals("stt.final")) finalized.add(segmentKey(e));
            }
            assertThat(revisions).as("partials requested explicitly, not silently skipped on missing capability").isNotEmpty();
            assertThat(finalized).containsAll(revisions.keySet());
            assertThat(counts.values()).allSatisfy(count -> assertThat(count).isGreaterThanOrEqualTo(p.mock ? 2 : 1));
        }
    }

    @Test void AC4_contract_finalsHaveSessionStreamSegmentBoundsAndSixScriptedUtterances() throws Exception {
        try (Peer p = connected()) {
            p.start(50);
            List<Packet> packets = p.captureToEnd();
            List<Packet> finals = packets.stream().filter(e -> e.type().equals("stt.final")).toList();
            assertThat(finals).isNotEmpty();
            assertThat(finals.stream().map(e -> e.body().path("stream").asText()).distinct().toList())
                    .containsExactlyInAnyOrder(STREAMS);
            // Six exact regions belong only to the scripted recognizer, not native whisper chunking.
            if (p.mock) assertThat(finals).hasSize(6);
            Set<String> seen = new HashSet<>();
            for (Packet e : finals) {
                JsonNode b = e.body();
                assertThat(b.path("session_id").asText()).isEqualTo(SESSION);
                assertThat(b.path("stream").asText()).isIn((Object[]) STREAMS);
                assertThat(seen.add(segmentKey(e))).as("unique final segment").isTrue();
                assertThat(b.path("start_sample").isIntegralNumber()).isTrue();
                assertThat(b.path("end_sample").asLong()).isGreaterThan(b.path("start_sample").asLong()).isLessThanOrEqualTo(p.mock ? SAMPLES : 384000);
                assertThat(b.path("text").asText()).isNotBlank();
                assertThat(b.path("model").asText()).isNotBlank();
                if (p.mock) {
                    int n = Integer.parseInt(b.path("seg").asText().substring(2));
                    assertThat(b.path("text").asText()).isEqualTo(utterances()[n]);
                    assertThat(b.path("start_sample").asLong()).isEqualTo(n * 16000L);
                    assertThat(b.path("end_sample").asLong()).isEqualTo((n + 1) * 16000L);
                }
                assertThat(packets).anySatisfy(v -> {
                    assertThat(v.type()).isEqualTo("vad.segment");
                    assertThat(segmentKey(v)).isEqualTo(segmentKey(e));
                    assertThat(v.body().path("state").asText()).isEqualTo("closed");
                });
            }
        }
    }

    @Test void AC4_contract_stopDrainsAndReportsExactFinalSequencesThenNoSessionEvents() throws Exception {
        try (Peer p = connected()) {
            p.start(50);
            long[] last = {-1, -1};
            long until = System.nanoTime() + 650_000_000L;
            while (System.nanoTime() < until) {
                Packet e = p.next(2000);
                if (e.kind == 2) { Audio a = e.audio(); last[a.stream] = a.seq; p.ack(a, 50); }
            }
            p.send("c-stop", "session.stop", null, obj().put("session_id", SESSION));
            while (true) {
                Packet e = p.next(3000);
                if (e.kind == 2) { Audio a = e.audio(); last[a.stream] = a.seq; }
                if (!e.type().equals("session.stopped")) continue;
                assertThat(e.json.path("re").asText()).isEqualTo("c-stop");
                assertThat(e.body().path("session_id").asText()).isEqualTo(SESSION);
                for (int i = 0; i < 2; i++) assertThat(e.body().path("final_seq").path(STREAMS[i]).asLong()).isEqualTo(last[i]);
                break;
            }
            assertThat(p.collect(600)).noneMatch(e -> e.kind == 2 || Set.of("level", "stt.partial", "stt.final", "vad.segment").contains(e.type()));
            p.shutdown();
        }
    }

    @ParameterizedTest(name = "AC4_contract_unsupported_{0}")
    @ValueSource(strings = {"future.request", "session.pause", "session.resume"})
    void AC4_contract_unknownRequestIsCorrelatedUnsupportedMessageAndSessionSurvives(String type) throws Exception {
        try (Peer p = connected()) {
            p.send("c-unknown", type, null, obj().put("future_field", true));
            Packet e = p.until("error", 2000);
            assertThat(e.json.path("re").asText()).isEqualTo("c-unknown");
            assertThat(e.body().path("code").asText()).isEqualTo("unsupported_message");
            assertThat(e.body().path("severity").asText()).isEqualTo("warn");
            p.start(50);
            assertThat(p.nextAudio().seq).isZero();
        }
    }

    @Test void AC4_contract_unknownEventsAreIgnoredAndUnknownFieldsDoNotBreakRequests() throws Exception {
        try (Peer p = connected()) {
            p.send(null, "future.event", null, obj());
            assertThat(p.collect(300)).noneMatch(e -> e.type().equals("error"));
            ObjectNode shutdown = obj().put("future_field", true);
            p.send("c-shutdown", "shutdown", null, shutdown);
            Packet ok = p.until("shutdown.ok", 2000);
            assertThat(ok.json.path("re").asText()).isEqualTo("c-shutdown");
            p.exit(0, 2000);
        }
    }

    @ParameterizedTest(name = "AC4_contract_badFrameReportsFatalThenCloses_{0}")
    @ValueSource(strings = {"unknown-kind", "zero-length", "control-over-limit", "audio-over-limit", "invalid-json", "truncated-prefix", "truncated-payload"})
    void AC4_contract_badFrameReportsFatalThenCloses(String defect) throws Exception {
        try (Peer p = connected()) {
            byte[] bytes = switch (defect) {
                case "unknown-kind" -> new byte[]{0, 0, 0, 1, 99};
                case "zero-length" -> new byte[]{0, 0, 0, 0};
                case "control-over-limit" -> ByteBuffer.allocate(5).putInt(1048577).put((byte) 1).array();
                case "audio-over-limit" -> ByteBuffer.allocate(5).putInt(65537).put((byte) 2).array();
                case "invalid-json" -> new byte[]{0, 0, 0, 2, 1, '{'};
                case "truncated-prefix" -> new byte[]{0, 0};
                default -> new byte[]{0, 0, 0, 10, 1, '{'};
            };
            p.out.write(bytes); p.out.flush();
            if (defect.startsWith("truncated")) p.out.close();
            Packet error = p.until("error", 2000);
            assertThat(error.body().path("code").asText()).isEqualTo("bad_frame");
            assertThat(error.body().path("severity").asText()).isEqualTo("fatal");
            assertThat(p.process.waitFor(2, TimeUnit.SECONDS)).as("bad_frame must close the process transport").isTrue();
            // The contract requires fatal bad_frame and closure, not a specific exit code.
            p.closedOutput();
        }
    }

    @ParameterizedTest(name = "AC4_contract_stdinEofExitsZeroWithinTwoSeconds_{0}")
    @ValueSource(booleans = {false, true})
    void AC4_contract_stdinEofExitsZeroWithinTwoSeconds(boolean capturing) throws Exception {
        try (Peer p = connected()) {
            if (capturing) { p.start(50); p.nextAudio(); }
            p.out.close();
            p.exit(0, 2000);
            p.closedOutput(true); // Frames written before the parent closed stdin may still be buffered.
        }
    }

    @ParameterizedTest(name = "AC4_contract_shutdownCorrelatesAndExitsZero_{0}")
    @ValueSource(booleans = {false, true})
    void AC4_contract_shutdownCorrelatesAndExitsZero(boolean capturing) throws Exception {
        try (Peer p = connected()) {
            if (capturing) { p.start(50); p.nextAudio(); }
            p.shutdown();
            p.closedOutput();
        }
    }

    @ParameterizedTest(name = "AC4_contract_permissions_{0}")
    @ValueSource(strings = {"permission.status", "permission.request"})
    void AC4_contract_permissionStatusReturnsBothStatesWithoutPromptAndStaysAlive(String type) throws Exception {
        try (Peer p = connected()) {
            ObjectNode body = obj();
            if (type.equals("permission.request")) body.put("permission", "microphone");
            else body.putArray("permissions").add("microphone").add("system_audio");
            p.send("c-permissions", type, null, body);
            Packet e = p.match(v -> v.json != null && v.json.path("re").asText().equals("c-permissions"), 2000);
            assertThat(e.type()).isEqualTo(type);
            assertThat(e.body().path("microphone").asText()).isIn("granted", "denied", "not_determined", "restricted");
            assertThat(e.body().path("system_audio").asText()).isIn("granted", "unknown", "denied");
            p.shutdown();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "interline.conformance.agent", matches = "^$")
    void AC4_contract_mockDeviceChangeIsAnObservableSessionEvent() throws Exception {
        try (Peer p = connected("--device-change-at=1s")) {
            p.start(50);
            Packet change = p.until("device.changed", 2500);
            assertThat(change.body().path("session_id").asText()).isEqualTo(SESSION);
            assertThat(change.body().path("stream").asText()).isIn((Object[]) STREAMS);
            assertThat(change.json.has("re")).isFalse();
        }
    }

    @ParameterizedTest(name = "AC4_contract_transcribeFileEmitsCorrelatedFinalsWithoutLiveAudio_{0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5})
    void AC4_contract_transcribeFileEmitsCorrelatedFinalsWithoutLiveAudio(int utterance) throws Exception {
        try (Peer p = connected()) {
            Path input = speechFixture(utterance, p.mock);
            ObjectNode body = obj().put("job_id", SESSION).put("path", input.toString()).put("stream", "system");
            body.set("stt", stt());
            p.send("c-import", "transcribe.file", null, body);
            Packet first = p.match(e -> e.type().equals("stt.final") || e.type().equals("error"), 15000);
            assertThat(first.type()).as("file import must transcribe, not return unsupported_message").isEqualTo("stt.final");
            assertThat(first.body().path("session_id").asText()).isEqualTo(SESSION);
            assertThat(first.body().path("stream").asText()).isEqualTo("system");
            assertThat(first.body().path("seg").asText()).isNotBlank();
            assertThat(first.body().path("text").asText()).isNotBlank();
            assertThat(first.body().path("end_sample").asLong()).isGreaterThan(first.body().path("start_sample").asLong());
            Packet done = p.until("transcribe.file.done", 15000);
            assertThat(done.json.path("re").asText()).isEqualTo("c-import");
            assertThat(done.body().path("job_id").asText()).isEqualTo(SESSION);
            var imported = p.history.stream().filter(e -> e.type().equals("stt.final")).toList();
            assertThat(imported).isNotEmpty().allSatisfy(e -> {
                assertThat(e.body().path("session_id").asText()).isEqualTo(SESSION);
                assertThat(e.body().path("stream").asText()).isEqualTo("system");
                assertThat(e.at).isLessThan(done.at);
            });
            assertThat(done.body().path("segments").asInt()).isEqualTo(imported.size());
            try (var audio = javax.sound.sampled.AudioSystem.getAudioInputStream(input.toFile())) {
                long samples = Math.round(audio.getFrameLength() * 16000.0 / audio.getFormat().getSampleRate());
                assertThat(done.body().path("duration_samples").asLong()).isEqualTo(samples);
            }
            assertThat(p.history).noneMatch(e -> e.kind == 2);
            // Events correlate through session/stream; request replies use re when present.
            for (Packet e : p.history) if (e.json != null && e.json.has("re"))
                assertThat(e.json.path("re").asText()).isEqualTo("c-import");
        }
    }

    private static ObjectNode obj() { return JSON.createObjectNode(); }
    private Path speechFixture(int utterance, boolean mock) throws Exception {
        // MockAgent's script is the recognizer on headless Linux. Real macOS imports get actual
        // generated speech, not a sine wave mislabeled as speech or a checked-in recording.
        if (mock && !Files.isExecutable(Path.of("/usr/bin/say"))) return work.resolve("input.wav");
        Path aiff = work.resolve("utterance-" + utterance + ".aiff");
        Path wav = work.resolve("utterance-" + utterance + ".wav");
        if (Files.exists(wav)) return wav;
        generate(List.of("/usr/bin/say", "-o", aiff.toString(), utterances()[utterance]));
        generate(List.of("/usr/bin/afconvert", "-f", "WAVE", "-d", "LEI16@16000", "-c", "1", aiff.toString(), wav.toString()));
        assertThat(wav).isRegularFile();
        return wav;
    }
    private void speechTimeline(Path wav) throws Exception {
        // Six separated utterances, alternating mic/system, padded to four seconds each.
        // Real recognizers must hear speech; the deterministic tone is only the MockAgent oracle.
        byte[] pcm = new byte[384000 * 4];
        for (int n = 0; n < 6; n++) {
            Path clip = speechFixture(n, false);
            try (var input = javax.sound.sampled.AudioSystem.getAudioInputStream(clip.toFile())) {
                byte[] mono = input.readAllBytes();
                assertThat(input.getFormat().getSampleRate()).isEqualTo(16000f);
                assertThat(input.getFormat().getChannels()).isEqualTo(1);
                assertThat(mono.length).as("generated utterance fits its four-second slot").isLessThanOrEqualTo(128000);
                for (int i = 0; i < mono.length / 2; i++) {
                    int at = (n * 64000 + i) * 4 + (n % 2) * 2;
                    pcm[at] = mono[i * 2]; pcm[at + 1] = mono[i * 2 + 1];
                }
            }
        }
        ByteBuffer data = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        data.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVEfmt ".getBytes()).putInt(16)
                .putShort((short) 1).putShort((short) 2).putInt(16000).putInt(64000).putShort((short) 4).putShort((short) 16)
                .put("data".getBytes()).putInt(pcm.length).put(pcm);
        Files.write(wav, data.array());
    }
    private void generate(List<String> command) throws Exception {
        Process generator = new ProcessBuilder(command).redirectError(work.resolve("fixture-stderr.log").toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(generator.waitFor(15, TimeUnit.SECONDS)).as("fixture generator deadline: %s", command).isTrue();
            assertThat(generator.exitValue()).as("fixture generator: %s", command).isZero();
        } finally { if (generator.isAlive()) { generator.destroyForcibly(); generator.waitFor(); } }
    }
    private static String segmentKey(Packet e) { return e.body().path("session_id").asText() + "/" + e.body().path("stream").asText() + "/" + e.body().path("seg").asText(); }
    private static String[] utterances() { return new String[]{"Bonjour nous commencons la reunion.", "The budget is approved today.", "Nous gardons le calendrier actuel.", "Please review the project notes.", "Merci pour votre participation.", "We will meet again tomorrow."}; }
    private static short tone(long sample, int channel) {
        if (sample >= 96000) return 0;
        return (short) Math.round(6000 * Math.sin(2 * Math.PI * (channel == 0 ? 440 : 660) * sample / 16000));
    }
    private ObjectNode stt() {
        ObjectNode stt = obj().put("partials", true).put("language", "en").put("min_silence_ms", 500).put("max_chunk_s", 30).put("overlap_ms", 500);
        stt.putArray("target_chunk_s").add(5).add(15);
        // Optional real-model inputs are caller supplied, never fake model files.
        for (String key : List.of("model_path", "model_sha256", "vad_model_path")) {
            String value = System.getProperty("interline.conformance." + key);
            if (value != null) stt.put(key, value);
        }
        return stt;
    }
    private Peer connected(String... extraArgs) throws Exception {
        Peer p = launch(extraArgs);
        try { p.ackHello(p.next(1000)); return p; }
        catch (Throwable e) { p.close(); throw e; }
    }
    private Peer launch(String... extraArgs) throws Exception {
        Path wav = work.resolve("input.wav"), script = work.resolve("script.json");
        if (!Files.exists(wav)) {
            ByteBuffer data = ByteBuffer.allocate(44 + SAMPLES * 4).order(ByteOrder.LITTLE_ENDIAN);
            data.put("RIFF".getBytes()).putInt(36 + SAMPLES * 4).put("WAVEfmt ".getBytes()).putInt(16)
                    .putShort((short) 1).putShort((short) 2).putInt(16000).putInt(64000).putShort((short) 4).putShort((short) 16)
                    .put("data".getBytes()).putInt(SAMPLES * 4);
            for (int i = 0; i < SAMPLES; i++) for (int c = 0; c < 2; c++) data.putShort(tone(i, c));
            Files.write(wav, data.array());
            ObjectNode root = obj().put("sample_rate", 16000).put("samples", SAMPLES);
            var segments = root.putArray("segments");
            for (int i = 0; i < 6; i++) segments.addObject().put("stream", STREAMS[i % 2]).put("seg", "s-" + i)
                    .put("start_sample", i * 16000).put("end_sample", (i + 1) * 16000).put("text", utterances()[i]);
            JSON.writeValue(script.toFile(), root);
        }
        String executable = System.getProperty("interline.conformance.agent", "").trim();
        boolean mock = executable.isEmpty();
        if (!mock) speechTimeline(wav);
        List<String> command = new ArrayList<>();
        if (mock) {
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.add("-jar"); command.add(Path.of(System.getProperty("mock.agent.jar")).toAbsolutePath().toString());
            command.add("--wav=" + wav); command.add("--script=" + script); command.add("--speed=1");
        } else command.add(executable);
        command.add("--protocol=1"); command.add("--log-level=info");
        JsonNode args = JSON.readTree(System.getProperty("interline.conformance.agentArgs", "[]"));
        if (!args.isArray()) throw new IllegalArgumentException("agentArgs must be a JSON string array");
        for (JsonNode arg : args) {
            if (!arg.isTextual()) throw new IllegalArgumentException("agentArgs entries must be strings");
            command.add(arg.asText().replace("{wav}", wav.toString()).replace("{script}", script.toString()).replace("{work}", work.toString()));
        }
        command.addAll(List.of(extraArgs));
        return new Peer(command, mock);
    }

    private record Audio(int stream, int flags, int reserved, long seq, long sample, long host, byte[] pcm) { }
    private record Packet(int kind, byte[] payload, JsonNode json, long at) {
        String type() { return json == null ? "" : json.path("t").asText(); }
        JsonNode body() { return json == null ? obj() : json.path("body"); }
        Audio audio() {
            assertThat(payload.length).isEqualTo(3224);
            ByteBuffer b = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            return new Audio(Byte.toUnsignedInt(b.get()), Byte.toUnsignedInt(b.get()), Short.toUnsignedInt(b.getShort()),
                    Integer.toUnsignedLong(b.getInt()), b.getLong(), b.getLong(), Arrays.copyOfRange(payload, 24, payload.length));
        }
    }
    private final class Peer implements AutoCloseable {
        final Process process;
        final OutputStream out;
        final boolean mock;
        final long spawned;
        final BlockingQueue<Object> incoming = new LinkedBlockingQueue<>();
        final List<Packet> history = new ArrayList<>();
        final Object eof = new Object();
        final Path stderr;
        Peer(List<String> command, boolean mock) throws Exception {
            this.mock = mock;
            stderr = work.resolve("agent-stderr.log");
            spawned = System.nanoTime();
            process = new ProcessBuilder(command).redirectError(stderr.toFile()).start();
            out = process.getOutputStream();
            Thread.ofPlatform().daemon().name("conformance-wire-reader").start(() -> {
                try (DataInputStream in = new DataInputStream(process.getInputStream())) {
                    while (true) {
                        int first = in.read();
                        if (first < 0) { incoming.add(eof); return; }
                        int length = (first << 24) | (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
                        assertThat(length).as("stdout must contain frames, never logs").isBetween(1, 1048576);
                        int kind = in.readUnsignedByte();
                        assertThat(kind).isIn(1, 2);
                        assertThat(length).isLessThanOrEqualTo(kind == 1 ? 1048576 : 65536);
                        byte[] bytes = in.readNBytes(length - 1);
                        assertThat(bytes).hasSize(length - 1);
                        JsonNode json = kind == 1 ? JSON.readTree(bytes) : null;
                        if (json != null) {
                            assertThat(json.isObject()).isTrue();
                            assertThat(json.path("v").isIntegralNumber()).isTrue();
                            assertThat(json.path("v").asInt()).isEqualTo(1);
                            assertThat(json.path("t").isTextual()).isTrue();
                            assertThat(json.path("ts").isIntegralNumber()).isTrue();
                            assertThat(json.path("ts").asLong()).isNotNegative();
                            assertThat(json.path("body").isObject()).isTrue();
                        }
                        incoming.add(new Packet(kind, bytes, json, System.nanoTime()));
                    }
                } catch (Throwable e) { incoming.add(e); }
            });
        }
        Packet next(long ms) throws Exception {
            Object value = incoming.poll(ms, TimeUnit.MILLISECONDS);
            assertThat(value).as("agent frame within %d ms; stderr: %s", ms, Files.readString(stderr)).isNotNull();
            assertThat(value).as("agent must emit a protocol frame, not close stdout; stderr: %s", Files.readString(stderr)).isNotSameAs(eof);
            if (value instanceof Throwable t) throw new AssertionError("Invalid agent stdout", t);
            Packet packet = (Packet) value; history.add(packet); return packet;
        }
        Packet match(Predicate<Packet> predicate, long ms) throws Exception {
            long deadline = System.nanoTime() + ms * 1_000_000;
            while (true) {
                Packet packet = next(Math.max(1, (deadline - System.nanoTime()) / 1_000_000));
                if (predicate.test(packet)) return packet;
                assertThat(System.nanoTime()).as("expected response before deadline").isLessThan(deadline);
            }
        }
        Packet until(String type, long ms) throws Exception { return match(e -> e.type().equals(type), ms); }
        Audio nextAudio() throws Exception { return match(e -> e.kind == 2, 2000).audio(); }
        void send(String id, String type, String re, ObjectNode body) throws Exception {
            ObjectNode envelope = obj().put("v", 1).put("t", type).put("ts", System.currentTimeMillis());
            if (id != null) envelope.put("id", id);
            if (re != null) envelope.put("re", re);
            envelope.set("body", body);
            byte[] json = JSON.writeValueAsBytes(envelope);
            out.write(ByteBuffer.allocate(json.length + 5).putInt(json.length + 1).put((byte) 1).put(json).array()); out.flush();
        }
        void ackHello(Packet hello) throws Exception {
            assertThat(hello.type()).isEqualTo("hello");
            send(null, "hello.ack", hello.json.path("id").asText(), obj().put("protocol", 1).put("core_version", "conformance"));
        }
        void start(int window) throws Exception {
            ObjectNode body = obj().put("session_id", SESSION).put("spool_dir", Files.createDirectories(work.resolve("spool")).toString());
            body.putArray("streams").add("mic").add("system");
            body.putObject("audio").put("sample_rate", 16000).put("format", "s16le").put("channels", 1).put("chunk_ms", 100).put("window", window);
            body.putNull("resume"); body.set("stt", stt());
            send("c-start", "session.start", null, body);
            Packet started = match(e -> e.type().equals("session.started") || e.type().equals("error"), 3000);
            assertThat(started.type()).isEqualTo("session.started");
            assertThat(started.json.path("re").asText()).isEqualTo("c-start");
            assertThat(started.body().path("session_id").asText()).isEqualTo(SESSION);
            assertThat(started.body().path("t0_wall_ms").asLong()).isPositive();
        }
        void ack(Audio a, int window) throws Exception { send(null, "audio.ack", null, obj().put("stream", STREAMS[a.stream]).put("seq", a.seq).put("window", window)); }
        List<Packet> collect(long ms) throws Exception {
            List<Packet> result = new ArrayList<>();
            long end = System.nanoTime() + ms * 1_000_000;
            while (System.nanoTime() < end) {
                long remaining = Math.max(1, (end - System.nanoTime()) / 1_000_000);
                Object value = incoming.poll(remaining, TimeUnit.MILLISECONDS);
                if (value == null) break;
                if (value instanceof Throwable t) throw new AssertionError("Invalid agent stdout", t);
                assertThat(value).isNotSameAs(eof);
                Packet packet = (Packet) value; history.add(packet); result.add(packet);
            }
            return result;
        }
        List<Packet> captureToEnd() throws Exception {
            long[] seq = {-1, -1};
            List<Packet> events = new ArrayList<>();
            int finalSeq = mock ? 79 : 239;
            while (seq[0] < finalSeq || seq[1] < finalSeq) {
                Packet e = next(3000); events.add(e);
                if (e.kind == 2) { Audio a = e.audio(); seq[a.stream] = a.seq; ack(a, 50); }
            }
            send("c-stop", "session.stop", null, obj().put("session_id", SESSION));
            while (true) {
                Packet e = next(15000); events.add(e);
                if (e.type().equals("session.stopped")) break;
            }
            return events;
        }
        void exit(int code, long ms) throws Exception {
            assertThat(process.waitFor(ms, TimeUnit.MILLISECONDS)).as("agent exit within %d ms", ms).isTrue();
            assertThat(process.exitValue()).isEqualTo(code);
        }
        void shutdown() throws Exception {
            send("c-shutdown", "shutdown", null, obj());
            Packet ok = until("shutdown.ok", 2000);
            assertThat(ok.json.path("re").asText()).isEqualTo("c-shutdown");
            exit(0, 2000);
        }
        void closedOutput() throws Exception { closedOutput(false); }
        void closedOutput(boolean allowQueuedAudio) throws Exception {
            long deadline = System.nanoTime() + 1_000_000_000L;
            while (true) {
                Object value = incoming.poll(Math.max(1, (deadline - System.nanoTime()) / 1_000_000), TimeUnit.MILLISECONDS);
                if (value == eof) return;
                assertThat(value).as("stdout must reach EOF").isNotNull();
                if (value instanceof Throwable t) throw new AssertionError("Invalid trailing stdout", t);
                if (!allowQueuedAudio) assertThat(((Packet) value).kind).as("no audio after termination").isNotEqualTo(2);
                assertThat(System.nanoTime()).isLessThan(deadline);
            }
        }
        @Override public void close() throws Exception {
            process.destroyForcibly();
            assertThat(process.waitFor(2, TimeUnit.SECONDS)).as("child reaped after each test").isTrue();
        }
    }
}
