package app.interline.app;

import app.interline.agent.AudioChunk;
import app.interline.domain.MeetingStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static app.interline.app.AppTestSupport.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** M1 red integration contracts. No replacement persistence, SSE, downloader or supervisor. */
@org.junit.jupiter.api.Timeout(240)
class M1Test {
    @TempDir Path data;

    private ConfigurableApplicationContext boot(Path directory, Path agent, List<String> agentArguments, String... extra) {
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--server.address=127.0.0.1",
                "--interline.token=" + TOKEN, "--interline.data-dir=" + directory,
                "--interline.log-dir=" + directory.resolve("logs"), "--spring.main.banner-mode=off"));
        if (agent != null) args.add("--interline.agent=" + agent);
        for (int i = 0; i < agentArguments.size(); i++) args.add("--interline.agent-args[" + i + "]=" + agentArguments.get(i));
        args.addAll(List.of(extra));
        return SpringApplication.run(InterlineApplication.class, args.toArray(String[]::new));
    }
    private ConfigurableApplicationContext mock(Path directory, String fault) {
        return boot(directory, ROOT.resolve("services/core/mock-agent/bin/mock-agent"), agentArgs(fixture(directory), fault));
    }
    private static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    @Test void AC1_regressionDurableIngressAndAuthenticatedHttpKeepM0TranscriptIdentity() throws Exception {
        try (var context = mock(data, null); var events = new Events(port(context))) {
            int port = port(context);
            String id = request(port, "POST", "/recording/start").path("meetingId").asText();
            eventually(10, () -> assertThat(events.states(id)).contains("recording"));
            eventually(15, () ->
                    assertThat(request(port, "GET", "/meetings/" + id + "/transcript").size()).isEqualTo(expectedSegments()));
            request(port, "POST", "/recording/stop"); events.state(id, "done");
            assertThat(rows(data, "SELECT agent_seg FROM transcript_segments WHERE meeting_id=?", id)).hasSize(expectedSegments());
            assertThat(rows(data, "SELECT agent_seg FROM transcript_segments WHERE meeting_id=? GROUP BY speaker_id,agent_seg HAVING count(*)>1", id)).isEmpty();
            assertThat(rows(data, "SELECT status FROM meetings WHERE id=?", id)).containsExactly(Map.of("status", "done"));
        }
    }

    @Test void AC5_restartAfterRealSigkillReplaysFramesAndPersistsEveryOutsideGapUtteranceOnce() throws Exception {
        Path launcher = M1AgentProbe.launcher(data, false);
        try (var probe = new PipelineAgentProbe(data);
             var context = boot(data, launcher, agentArgs(fixture(data), null)); var events = new Events(port(context))) {
            restartAssertions(context, events, probe);
        }
    }

    private void restartAssertions(ConfigurableApplicationContext context, Events events, PipelineAgentProbe probe) throws Exception {
        int port = port(context);
        String id = request(port, "POST", "/recording/start").path("meetingId").asText();
        eventually(10, () -> assertThat(events.states(id)).contains("recording"));
        eventually(5, () ->
                assertThat(probe.observations().stream().filter(o -> o.path("type").asText().equals("audio.chunk")).count()).isGreaterThan(100));
        JsonNode initial = probe.observations().stream().filter(o -> o.path("type").asText().equals("session.start")).findFirst().orElseThrow();
        long oldPid = initial.path("pid").asLong();
        // Kill the actual MockAgent child, not the tee; EOF then exercises production restart policy.
        ProcessHandle child = ProcessHandle.of(oldPid).orElseThrow().descendants().findFirst().orElseThrow();
        long killedPid = child.pid(); long killedAt = System.nanoTime();
        assertThat(child.destroyForcibly()).as("SIGKILL the real capture process").isTrue();
        eventually(4, () ->
                assertThat(probe.observations().stream().filter(o -> o.path("type").asText().equals("session.start")).toList()).hasSize(2));
        var observations = probe.observations();
        JsonNode resumed = observations.stream().filter(o -> o.path("type").asText().equals("session.start")).toList().getLast();
        assertThat(resumed.path("pid").asLong()).isNotEqualTo(oldPid);
        assertThat(ProcessHandle.of(killedPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
        long restartGap = resumed.path("nano").asLong() - killedAt;
        eventually(15, () ->
                assertThat(request(port, "GET", "/meetings/" + id + "/transcript").size()).isEqualTo(expectedSegments()));
        request(port, "POST", "/recording/stop"); events.state(id, "done");
        for (String stream : List.of("mic", "system")) {
            var frames = probe.observations().stream().filter(o -> o.path("type").asText().equals("audio.chunk"))
                    .filter(o -> o.path("stream").asText().equals(stream)).toList();
            assertThat(frames).anySatisfy(o -> assertThat(o.path("flags").asInt() & 1).isEqualTo(1));
            long[] seq = frames.stream().mapToLong(o -> o.path("seq").asLong()).distinct().sorted().toArray();
            for (int i = 0; i < seq.length; i++) assertThat(seq[i]).as("%s contiguous sequence", stream).isEqualTo(i);
            assertThat(track(data, id, stream)).isEqualTo(pcm(stream.equals("mic") ? 0 : 1, 0, (int) SAMPLES));
        }
        assertThat(rows(data, "SELECT agent_seg FROM transcript_segments WHERE meeting_id=?", id)).hasSize(expectedSegments());
        assertThat(rows(data, "SELECT agent_seg FROM transcript_segments WHERE meeting_id=? GROUP BY speaker_id,agent_seg HAVING count(*)>1", id)).isEmpty();
        var expected = JSON.readTree(ROOT.resolve("fixtures/audio/standup-fr-2min.transcript.json").toFile()).path("segments");
        var actual = request(port, "GET", "/meetings/" + id + "/transcript");
        for (JsonNode segment : expected) {
            var matching = new ArrayList<JsonNode>();
            actual.forEach(row -> { if (row.path("stream").equals(segment.path("stream")) && row.path("seg").equals(segment.path("seg"))) matching.add(row); });
            assertThat(matching).singleElement().satisfies(row -> {
                assertThat(row.path("text")).isEqualTo(segment.path("text"));
                assertThat(row.path("startMs").asLong()).isEqualTo(segment.path("start_sample").asLong() / 16);
                assertThat(row.path("endMs").asLong()).isEqualTo(segment.path("end_sample").asLong() / 16);
            });
        }
        assertThat(rows(data, "SELECT * FROM timeline_markers WHERE meeting_id=? AND kind='gap'", id)).hasSize(1);
        assertThat(restartGap).as("Capture restart gap target").isLessThan(2_000_000_000L);
    }

    @Test
    @EnabledIfSystemProperty(named = "interline.swift-agent", matches = ".+")
    void AC5_swiftSigkillHeadlessInputUsesRealNativeProcess() throws Exception {
        Path nativeAgent = Path.of(System.getProperty("interline.swift-agent"));
        assertThat(nativeAgent).as("Build Swift release before opting into the native integration test").isExecutable();
        Path speech = M1NativeContract.speech(data.resolve("speech"));
        // Same executable, real speech and model configuration in both runs. No TCC or transcript script oracle.
        var baseline = nativeRun(data.resolve("baseline"), nativeAgent.toAbsolutePath(), speech, -1);
        var recovery = nativeRun(data.resolve("sigkill"), nativeAgent.toAbsolutePath(), speech, M1NativeContract.KILL_SAMPLE);
        M1NativeContract.compare(baseline, recovery);
    }

    /**
     * Proposed native-only configuration seam: -Dinterline.swift-agent.test-args='["--model=/preinstalled/model.bin"]'.
     * This is a JSON argv array forwarded unchanged, not a downloader or an asserted production model CLI.
     * Use identical preinstalled model inputs in both runs; no production configuration is changed by this test.
     */
    private M1NativeContract.Run nativeRun(Path directory, Path executable, Path speech, long killSample) throws Exception {
        Path launcher = M1NativeProbe.launcher(directory, executable, killSample);
        List<String> args = new ArrayList<>(List.of("--test-input=" + speech));
        JsonNode supplied = JSON.readTree(System.getProperty("interline.swift-agent.test-args", "[]"));
        assertThat(supplied.isArray()).as("Native model arguments must be a JSON argv array").isTrue();
        for (JsonNode arg : supplied) {
            assertThat(arg.isTextual()).isTrue();
            assertThat(arg.asText()).as("Speech input cannot be replaced by caller model configuration").doesNotStartWith("--test-input");
            args.add(arg.asText());
        }
        try (var probe = new M1NativeProbe(directory);
             var context = boot(directory, launcher, args); var events = new Events(port(context))) {
            // The skeleton exits without hello. Fail this readiness assertion, not an unrelated API/setup exception.
            eventually(10, () ->
                    assertThat(M1NativeContract.ofType(probe.observations(), "hello.ack"))
                            .as("Real Swift agent must complete native readiness; observed child launches/exits: %s", probe.observations())
                            .isNotEmpty());
            String id = request(port(context), "POST", "/recording/start").path("meetingId").asText();
            events.state(id, "recording");
            await().atMost(Duration.ofSeconds(100)).untilAsserted(() -> {
                for (String stream : List.of("mic", "system"))
                    assertThat(M1NativeContract.frames(probe.observations(), stream)).anySatisfy(n ->
                            assertThat(n.path("first_sample").asLong() + n.path("samples").asLong())
                                    .isEqualTo(M1NativeContract.TOTAL_SAMPLES));
            });
            request(port(context), "POST", "/recording/stop");
            events.state(id, "done");
            JsonNode transcript = request(port(context), "GET", "/meetings/" + id + "/transcript");
            assertThat(transcript.isArray()).isTrue();
            assertThat(transcript.size()).as("Native STT must produce actual nonempty finals, not just PCM").isPositive();
            return new M1NativeContract.Run(directory, id, probe.observations(), transcript);
        }
    }

    @Test void AC6_silentSystemSurfacesPermissionBannerEventWithinTenSeconds() throws Exception {
        try (var context = mock(data, "--silent-system"); var events = new Events(port(context))) {
            long start = System.nanoTime();
            request(port(context), "POST", "/recording/start");
            eventually(10, () -> assertThat(events.received).anySatisfy(e -> {
                assertThat(e.name()).isEqualTo("permission.denied");
                assertThat(e.data().path("permission").asText()).isEqualTo("system_audio");
                assertThat(e.data().path("detection").asText()).isEqualTo("silent_stream");
                assertThat(e.data().path("settings_uri").asText()).isNotBlank();
            }));
            assertThat(System.nanoTime() - start).isLessThan(10_000_000_000L);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "interline.swift-agent", matches = ".+")
    void AC6_swiftSilentInjectionRelaysPermissionDenialOnRealSse() throws Exception {
        Path agent = Path.of(System.getProperty("interline.swift-agent"));
        assertThat(agent).isExecutable();
        try (var context = boot(data, agent, List.of("--test-input=" + fixture(data), "--simulate-silent-system"));
             var events = new Events(port(context))) {
            request(port(context), "POST", "/recording/start");
            eventually(10, () -> assertThat(events.received).anySatisfy(e -> {
                assertThat(e.name()).isEqualTo("permission.denied");
                assertThat(e.data().path("permission").asText()).isEqualTo("system_audio");
                assertThat(e.data().path("detection").asText()).isEqualTo("silent_stream");
                assertThat(e.data().path("settings_uri").asText()).isNotBlank();
            }));
        }
    }

    @Test void AC7_downloadResumesRangeAndOnlyRenamesAfterSha256Verification() throws Exception {
        byte[] bytes = pcm(0, 0, 1600);
        try (var server = new DownloadOrigin(bytes)) {
            Path models = Files.createDirectories(data.resolve("models"));
            Path part = models.resolve("small.bin.part"); Files.write(part, Arrays.copyOf(bytes, 700));
            var downloader = new ModelDownloader(models);
            assertThat(server.requests).as("Construction must never fetch a model").isEmpty();
            Path[] result = new Path[1];
            assertThatCode(() -> result[0] = downloader.download(server.uri(), "small.bin", sha256(bytes)))
                    .as("Real downloader must resume a user-requested local HTTP download").doesNotThrowAnyException();
            assertThat(server.requests).containsExactly("bytes=700-");
            assertThat(result[0]).isEqualTo(models.resolve("small.bin"));
            assertThat(Files.readAllBytes(result[0])).isEqualTo(bytes);
            assertThat(part).doesNotExist();
        }
    }

    @Test void AC7_interruptedHttpBodyLeavesPartThenResumesExactOffset() throws Exception {
        byte[] bytes = pcm(0, 0, 1600);
        try (var server = new DownloadOrigin(bytes, true)) {
            Path models = Files.createDirectories(data.resolve("models"));
            var downloader = new ModelDownloader(models);
            assertThatThrownBy(() -> downloader.download(server.uri(), "tiny.bin", sha256(bytes)))
                    .as("A real truncated HTTP body must surface an IO error").isInstanceOf(java.io.IOException.class);
            assertThat(Files.readAllBytes(models.resolve("tiny.bin.part"))).isEqualTo(Arrays.copyOf(bytes, 700));
            assertThat(models.resolve("tiny.bin")).doesNotExist();
            Path result = downloader.download(server.uri(), "tiny.bin", sha256(bytes));
            assertThat(server.requests).containsExactly("FULL", "bytes=700-");
            assertThat(Files.readAllBytes(result)).isEqualTo(bytes);
            assertThat(models.resolve("tiny.bin.part")).doesNotExist();
        }
    }

    @Test void AC7_checksumMismatchDeletesPartAndNeverPublishesFinalModel() throws Exception {
        byte[] bytes = pcm(0, 0, 1600);
        try (var server = new DownloadOrigin(bytes)) {
            Path models = Files.createDirectories(data.resolve("models"));
            Files.write(models.resolve("bad.bin.part"), Arrays.copyOf(bytes, 700));
            var downloader = new ModelDownloader(models);
            assertThatThrownBy(() -> downloader.download(server.uri(), "bad.bin", "0".repeat(64)))
                    .as("A checksum failure, not a missing implementation").isInstanceOf(java.io.IOException.class).hasMessageContaining("checksum");
            assertThat(server.requests).containsExactly("bytes=700-");
            assertThat(models.resolve("bad.bin.part")).doesNotExist();
            assertThat(models.resolve("bad.bin")).doesNotExist();
        }
    }

    @Test void AC7_explicitDownloadEndpointIsTheOnlyUserInitiatedFetchPath() throws Exception {
        try (var server = new DownloadOrigin(new byte[100]);
             var context = boot(data, ROOT.resolve("services/core/mock-agent/bin/mock-agent"), agentArgs(fixture(data), null),
                     "--interline.models.catalog=" + localCatalog(server.uri(), new byte[100]))) {
            var response = HTTP.send(java.net.http.HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port(context) + "/api/v1/models/tiny/download"))
                    .header("Authorization", "Bearer " + TOKEN).header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), java.net.http.HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as("Explicit model-download route must exist: %s", response.body()).isIn(200, 202);
            eventually(5, () -> assertThat(server.requests).isNotEmpty());
        }
    }

    @Test void AC7_bootRecordingAndShutdownNeverContactConfiguredModelOrigin() throws Exception {
        try (var server = new DownloadOrigin(new byte[100]);
             var context = boot(data, ROOT.resolve("services/core/mock-agent/bin/mock-agent"), agentArgs(fixture(data), null),
                     "--interline.models.catalog=" + localCatalog(server.uri(), new byte[100]))) {
            String id = request(port(context), "POST", "/recording/start").path("meetingId").asText();
            eventually(5, () -> assertThat(rows(data, "SELECT * FROM transcript_segments WHERE meeting_id=?", id)).isNotEmpty());
            request(port(context), "POST", "/recording/stop");
            context.close();
            assertThat(server.requests).as("No user download intent: zero unsolicited HTTP requests").isEmpty();
        }
    }

    // Proposed M1 binding: interline.echo.aec. Twenty distinct tokens make 70/85 percent exact, not rounded.
    @ParameterizedTest(name = "AC9 aec={0} shared={1} offset={2} suppress={3} reverse={4}")
    @CsvSource({"false,14,0,0,false,remote|echo", "false,15,0,1,false,remote", "false,15,2000,1,false,remote",
            "false,15,-2000,1,false,remote", "false,15,2001,0,false,remote|echo", "false,15,-2001,0,false,remote|echo",
            "true,17,0,0,false,remote|echo", "true,18,0,1,false,remote", "false,15,0,1,true,remote", "false,0,0,0,false,remote|echo"})
    void AC9_echoRetainsSqliteRowsButHidesOnlySuppressedMicFinals(boolean aec, int shared, int offset, int suppressed, boolean reverse, String visibleSegments) throws Exception {
        try (var context = boot(data, null, List.of(), "--interline.echo.aec=" + aec)) {
            var ingest = context.getBean(DurableCaptureIngest.class);
            ingest.openMeeting("echo");
            String system = tokens("word", 20);
            String mic = tokens("word", shared) + " " + tokens("other", 20 - shared);
            Runnable systemFinal = () -> ingest.finalSegment("echo", "system", "remote", 160000, 176000, system);
            Runnable micFinal = () -> ingest.finalSegment("echo", "mic", "echo", (10000L + offset) * 16, (11000L + offset) * 16, mic);
            if (reverse) { micFinal.run(); systemFinal.run(); } else { systemFinal.run(); micFinal.run(); }
            micFinal.run(); // at-least-once replay must retain one row, including suppressed rows
            var retained = rows(data, "SELECT agent_seg,suppressed,text FROM transcript_segments WHERE meeting_id='echo'");
            var api = request(port(context), "GET", "/meetings/echo/transcript");
            org.assertj.core.api.SoftAssertions.assertSoftly(soft -> {
                soft.assertThat(retained).hasSize(2);
                soft.assertThat(retained.stream().filter(r -> r.get("agent_seg").equals("echo")).toList()).singleElement()
                        .satisfies(r -> soft.assertThat(((Number) r.get("suppressed")).intValue()).as("actual persisted suppression").isEqualTo(suppressed));
                soft.assertThat(api.findValuesAsText("seg")).as("public transcript filters suppressed rows")
                        .containsExactlyInAnyOrder(visibleSegments.split("\\|"));
            });
        }
    }

    @Test void AC9_publicTranscriptHidesPersistedSuppressedRowsWithoutDeletingThem() throws Exception {
        try (var context = boot(data, null, List.of())) {
            var ingest = context.getBean(DurableCaptureIngest.class);
            ingest.openMeeting("hidden");
            ingest.finalSegment("hidden", "mic", "suppressed", 0, 1600, "Echo retained for recovery.");
            ingest.finalSegment("hidden", "system", "visible", 0, 1600, "Remote speech.");
            // Fixture the already-decided suppression independently of the heuristic, to isolate API filtering.
            try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + data.resolve("db.sqlite"));
                 var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE transcript_segments SET suppressed=1 WHERE meeting_id='hidden' AND agent_seg='suppressed'");
            }
            var api = request(port(context), "GET", "/meetings/hidden/transcript");
            assertThat(rows(data, "SELECT agent_seg FROM transcript_segments WHERE meeting_id='hidden'")).hasSize(2);
            assertThat(api.findValuesAsText("seg")).containsExactly("visible");
        }
    }

    @ParameterizedTest(name = "AC10 stale {0}") @CsvSource({"recording", "degraded"})
    void AC10_startupImportsStaleHeaderSpoolAcrossHoleExactlyOnce(String status) throws Exception {
        seedOrphan(status);
        for (int launch = 0; launch < 2; launch++) {
            try (var context = boot(data, null, List.of())) {
                // first_sample=1920000 maps to 120 s, core PCM chunk 12.
                Path recovered = data.resolve("audio/orphan/mic/12.pcm");
                assertThat(recovered).as("Startup imports orphan spool payload despite its stale RIFF length").isRegularFile();
                assertThat(Files.readAllBytes(recovered)).isEqualTo(pcm(0, 320000, 1600));
                assertThat(data.resolve("audio/orphan/mic/1.pcm")).doesNotExist();
                assertThat(rows(data, "SELECT * FROM timeline_markers WHERE meeting_id='orphan' AND kind='gap'")).hasSize(1);
                assertThat(request(port(context), "GET", "/meetings").findValuesAsText("id")).contains("orphan");
            }
        }
    }

    @ParameterizedTest(name = "AC10 queued {0}") @CsvSource({"recording", "degraded"})
    void AC10_recoveryQueuesUntilNextAgentThenPersistsFinalsWithoutDuplicates(String status) throws Exception {
        seedOrphan(status);
        try (var context = boot(data, null, List.of())) {
            assertThat(rows(data, "SELECT status FROM meetings WHERE id='orphan'"))
                    .as("Startup marks abandoned meetings recovered without requiring an agent")
                    .containsExactly(Map.of("status", "recovered"));
        }
        Path launcher = M1AgentProbe.launcher(data, false);
        try (var probe = new PipelineAgentProbe(data)) {
            for (int launch = 0; launch < 2; launch++) {
                try (var context = boot(data, launcher, agentArgs(fixture(data), null))) {
                    eventually(10, () -> assertThat(probe.observations())
                            .anySatisfy(o -> assertThat(o.path("type").asText()).isEqualTo("transcribe.file")));
                    eventually(10, () -> assertThat(rows(data,
                            "SELECT * FROM transcript_segments WHERE meeting_id='orphan'")).isNotEmpty());
                    assertThat(rows(data, "SELECT agent_seg FROM transcript_segments WHERE meeting_id='orphan' GROUP BY speaker_id,agent_seg HAVING count(*)>1")).isEmpty();
                }
            }
        }
    }

    private void seedOrphan(String status) throws Exception {
        try (var ingest = new DurableCaptureIngest(data)) {
            ingest.openMeeting("orphan");
            ingest.audio("orphan", new AudioChunk(0, 0, 0, 0, 0, pcm(0, 0, 1600)));
            ingest.store().setStatus("orphan", status.equals("recording") ? MeetingStatus.RECORDING : MeetingStatus.DEGRADED, 1L);
        }
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + data.resolve("db.sqlite"));
             var statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE capture_runs SET started_at=1 WHERE meeting_id='orphan'");
            statement.executeUpdate("UPDATE meetings SET started_at=1,created_at=1,updated_at=1 WHERE id='orphan'");
        }
        Path spool = Files.createDirectories(data.resolve("spool/orphan/run-1"));
        byte[] payload = pcm(0, 320000, 1600);
        ByteBuffer wav = ByteBuffer.allocate(44 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes()).putInt(36).put("WAVEfmt ".getBytes()).putInt(16).putShort((short) 1)
                .putShort((short) 1).putInt(16000).putInt(32000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(0).put(payload);
        Files.write(spool.resolve("mic-1200-1920000.wav"), wav.array());
    }
    private static int expectedSegments() throws Exception {
        return JSON.readTree(ROOT.resolve("fixtures/audio/standup-fr-2min.transcript.json").toFile()).path("segments").size();
    }
    private static void eventually(int seconds, org.awaitility.core.ThrowingRunnable assertion) {
        assertThatCode(() -> await().atMost(Duration.ofSeconds(seconds)).untilAsserted(assertion))
                .as("Required integration behavior within %s seconds", seconds).doesNotThrowAnyException();
    }
    private static String tokens(String prefix, int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj(i -> prefix + i).collect(java.util.stream.Collectors.joining(" "));
    }
    private static byte[] track(Path directory, String id, String stream) throws Exception {
        Path root = directory.resolve("audio").resolve(id).resolve(stream);
        assertThat(root).isDirectory();
        try (var paths = Files.list(root); var out = new java.io.ByteArrayOutputStream()) {
            for (var p : paths.filter(p -> p.toString().endsWith(".pcm")).sorted(Comparator.comparingLong(p -> Long.parseLong(p.getFileName().toString().replace(".pcm", "")))).toList()) out.write(Files.readAllBytes(p));
            return out.toByteArray();
        }
    }
    private Path localCatalog(URI origin, byte[] bytes) throws Exception {
        Path catalog = data.resolve("catalog.json");
        JSON.writeValue(catalog.toFile(), List.of(Map.of("id", "tiny", "name", "Tiny fixture",
                "url", origin.toString(), "sizeBytes", bytes.length, "sha256", sha256(bytes))));
        return catalog;
    }

    private static String sha256(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    private static final class DownloadOrigin implements AutoCloseable {
        final List<String> requests = new CopyOnWriteArrayList<>();
        final HttpServer server;
        DownloadOrigin(byte[] bytes) throws Exception { this(bytes, false); }
        DownloadOrigin(byte[] bytes, boolean interruptFirst) throws Exception {
            var first = new java.util.concurrent.atomic.AtomicBoolean(interruptFirst);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/model", exchange -> {
                String range = exchange.getRequestHeaders().getFirst("Range"); requests.add(range == null ? "FULL" : range);
                int start = range == null ? 0 : Integer.parseInt(range.substring(6, range.length() - 1));
                exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
                if (range != null) exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + (bytes.length - 1) + "/" + bytes.length);
                exchange.sendResponseHeaders(range == null ? 200 : 206, bytes.length - start);
                if (first.getAndSet(false)) {
                    // Advertise the full body, then sever this response after exactly 700 bytes.
                    exchange.getResponseBody().write(bytes, 0, 700);
                    exchange.getResponseBody().flush();
                    exchange.close();
                    return;
                }
                try (var output = exchange.getResponseBody()) { output.write(bytes, start, bytes.length - start); }
            });
            server.start();
        }
        URI uri() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model"); }
        @Override public void close() { server.stop(0); }
    }
}
