package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Test conventions, not new production protocol requirements. See APP_TEST_CONTRACT.md. */
final class AppTestSupport {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    static final Path ROOT = root();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static final long SAMPLES = 1_920_000;

    private static Path root() {
        Path p = Path.of(System.getProperty("repo.root", ".")).toAbsolutePath().normalize();
        while (p != null && !Files.exists(p.resolve("docs/ARCHITECTURE.md"))) p = p.getParent();
        if (p == null) throw new AssertionError("Cannot locate repository root");
        return p;
    }

    static Path workspace(String prefix) {
        try { return Files.createTempDirectory(Path.of(System.getProperty("java.io.tmpdir")), prefix); }
        catch (IOException e) { throw new UncheckedIOException(e); }
    }

    static void properties(DynamicPropertyRegistry r, Path dir, String fault) {
        Path wav = fixture(dir);
        r.add("interline.token", () -> TOKEN);
        r.add("interline.data-dir", () -> dir.toString());
        r.add("interline.log-dir", () -> dir.resolve("logs").toString());
        r.add("interline.agent", () -> ROOT.resolve("services/core/mock-agent/bin/mock-agent").toString());
        List<String> args = agentArgs(wav, fault);
        for (int i = 0; i < args.size(); i++) {
            String value = args.get(i);
            r.add("interline.agent-args[" + i + "]", () -> value);
        }
        r.add("server.address", () -> "127.0.0.1");
    }

    static List<String> agentArgs(Path wav, String fault) {
        List<String> args = new ArrayList<>(List.of("--speed=20", "--wav=" + wav,
                "--script=" + ROOT.resolve("fixtures/audio/standup-fr-2min.transcript.json")));
        if (fault != null) args.add(fault);
        return args;
    }

    static byte[] pcm(int stream, int first, int count) throws IOException {
        JsonNode regions = JSON.readTree(ROOT.resolve("fixtures/audio/standup-fr-2min.transcript.json").toFile()).path("segments");
        ByteBuffer b = ByteBuffer.allocate(count * 2).order(ByteOrder.LITTLE_ENDIAN);
        String source = stream == 0 ? "mic" : "system";
        java.util.List<int[]> intervals = java.util.stream.StreamSupport.stream(regions.spliterator(), false)
                .filter(r -> r.path("stream").asText().equals(source))
                .map(r -> new int[]{r.path("start_sample").asInt(), r.path("end_sample").asInt()}).toList();
        boolean[] voiced = new boolean[count];
        for (int[] interval : intervals) {
            int start = Math.max(first, interval[0]);
            int end = Math.min(first + count, interval[1]);
            if (start < end) Arrays.fill(voiced, start - first, end - first, true);
        }
        for (int i = first; i < first + count; i++) {
            b.putShort(voiced[i - first] ? (short) (((i * (stream == 0 ? 37L : 53L)) % 20001) - 10000) : 0);
        }
        return b.array();
    }

    static Path fixture(Path dir) {
        Path wav = dir.resolve("standup-fr-2min.wav");
        if (Files.exists(wav)) return wav;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(wav)))) {
            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes()).putInt(36 + (int) SAMPLES * 4).put("WAVEfmt ".getBytes())
                    .putInt(16).putShort((short) 1).putShort((short) 2).putInt(16000).putInt(64000)
                    .putShort((short) 4).putShort((short) 16).put("data".getBytes()).putInt((int) SAMPLES * 4);
            out.write(h.array());
            // Independently build voiced regions before interleaving the two generated channels.
            JsonNode regions = JSON.readTree(ROOT.resolve("fixtures/audio/standup-fr-2min.transcript.json").toFile()).path("segments");
            boolean[][] voiced = new boolean[2][(int) SAMPLES];
            for (JsonNode region : regions) {
                int channel = region.path("stream").asText().equals("mic") ? 0 : 1;
                Arrays.fill(voiced[channel], region.path("start_sample").asInt(), region.path("end_sample").asInt(), true);
            }
            for (int i = 0; i < SAMPLES; i++) for (int channel = 0; channel < 2; channel++) {
                short sample = voiced[channel][i] ? (short) ((i * (channel == 0 ? 37L : 53L) % 20001) - 10000) : 0;
                out.writeByte(sample & 255); out.writeByte((sample >>> 8) & 255);
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return wav;
    }

    static JsonNode request(int port, String method, String path) throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .method(method, method.equals("POST") ? HttpRequest.BodyPublishers.ofString("{}") : HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s: %s", method, path, response.body()).isEqualTo(200);
        return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
    }

    static List<Map<String, Object>> rows(Path dir, String sql, Object... args) throws Exception {
        assertThat(dir.resolve("db.sqlite")).as("Core must create its actual persistent database").exists();
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("db.sqlite")); var s = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
            try (var rs = s.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    result.add(row);
                }
                return result;
            }
        }
    }

    record Event(String name, JsonNode data) { }
    static final class Events implements AutoCloseable {
        final List<Event> received = new CopyOnWriteArrayList<>();
        final InputStream input;
        final Thread reader;
        volatile Throwable failure;
        Events(int port) throws Exception {
            var response = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/events"))
                    .header("Authorization", "Bearer " + TOKEN).header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.ofInputStream());
            input = response.body();
            assertThat(response.statusCode()).as("SSE endpoint").isEqualTo(200);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
            reader = Thread.ofVirtual().start(() -> {
                try (var lines = new BufferedReader(new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))) {
                    String name = "message"; StringBuilder data = new StringBuilder(); String line;
                    while ((line = lines.readLine()) != null) {
                        if (line.isEmpty()) {
                            if (!data.isEmpty()) received.add(new Event(name, JSON.readTree(data.toString())));
                            name = "message"; data.setLength(0);
                        } else if (line.startsWith("event:")) name = line.substring(6).strip();
                        else if (line.startsWith("data:")) { if (!data.isEmpty()) data.append('\n'); data.append(line.substring(5).stripLeading()); }
                    }
                } catch (Exception e) { failure = e; }
            });
        }
        List<String> states(String id) { return received.stream().filter(e -> e.name.equals("recording.state") && e.data.path("meetingId").asText().equals(id)).map(e -> e.data.path("state").asText()).toList(); }
        void state(String id, String state) { await().atMost(Duration.ofSeconds(25)).untilAsserted(() -> assertThat(states(id)).contains(state)); }
        public void close() throws Exception { input.close(); reader.join(2000); }
    }
}
