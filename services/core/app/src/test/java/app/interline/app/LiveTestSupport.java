package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.*;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

import static app.interline.app.AppTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Helpers for live echo-suppression and per-meeting STT-language tests.
 * Lives in its own file so AppTestSupport stays byte-identical to the M0 base.
 */
final class LiveTestSupport {
    static final String STANDUP = "standup-fr-2min.transcript.json";

    /** Boot the real core in process: same entry point the packaged jar uses, no test configuration class. */
    static ConfigurableApplicationContext boot(Path directory, Path agent, List<String> agentArguments, String... extra) {
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--server.address=127.0.0.1",
                "--interline.token=" + TOKEN, "--interline.data-dir=" + directory,
                "--interline.log-dir=" + directory.resolve("logs"), "--spring.main.banner-mode=off"));
        if (agent != null) args.add("--interline.agent=" + agent);
        for (int i = 0; i < agentArguments.size(); i++) args.add("--interline.agent-args[" + i + "]=" + agentArguments.get(i));
        args.addAll(List.of(extra));
        return SpringApplication.run(InterlineApplication.class, args.toArray(String[]::new));
    }

    static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    /** Raw exchange, for a request whose status the caller wants to assert itself. A null body sends no payload. */
    static HttpResponse<String> exchange(int port, String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** POST with an explicit JSON body (or no body when null); asserts 200 and returns the parsed reply. */
    static JsonNode post(int port, String path, String body) throws Exception {
        var response = exchange(port, "POST", path, body);
        assertThat(response.statusCode()).as("POST %s %s: %s", path, body, response.body()).isEqualTo(200);
        return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
    }

    static long count(Path dir, String sql) throws Exception {
        Object value = rows(dir, sql).getFirst().values().iterator().next();
        return ((Number) value).longValue();
    }

    /** Scripted MockAgent command line for an arbitrary transcript fixture. */
    static List<String> agentArgs(Path wav, String script, String fault) {
        List<String> args = new ArrayList<>(List.of("--speed=20", "--wav=" + wav,
                "--script=" + ROOT.resolve("fixtures/audio/" + script)));
        if (fault != null) args.add(fault);
        return args;
    }

    /** Deterministic stereo 16 kHz s16le WAV from an arbitrary transcript fixture. */
    static Path fixture(Path dir, String script) {
        Path wav = dir.resolve(script.replace(".transcript.json", ".wav"));
        if (Files.exists(wav)) return wav;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(wav)))) {
            JsonNode root = JSON.readTree(ROOT.resolve("fixtures/audio/" + script).toFile());
            JsonNode regions = root.path("segments");
            int samples = root.path("samples").asInt();
            ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            h.put("RIFF".getBytes()).putInt(36 + samples * 4).put("WAVEfmt ".getBytes())
                    .putInt(16).putShort((short) 1).putShort((short) 2).putInt(16000).putInt(64000)
                    .putShort((short) 4).putShort((short) 16).put("data".getBytes()).putInt(samples * 4);
            out.write(h.array());
            boolean[][] voiced = new boolean[2][samples];
            for (JsonNode region : regions) {
                int channel = region.path("stream").asText().equals("mic") ? 0 : 1;
                Arrays.fill(voiced[channel], region.path("start_sample").asInt(), region.path("end_sample").asInt(), true);
            }
            for (int i = 0; i < samples; i++) for (int channel = 0; channel < 2; channel++) {
                short sample = voiced[channel][i] ? (short) ((i * (channel == 0 ? 37L : 53L) % 20001) - 10000) : 0;
                out.writeByte(sample & 255); out.writeByte((sample >>> 8) & 255);
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
        return wav;
    }

    /** Events of the given name from an SSE listener. */
    static List<AppTestSupport.Event> of(AppTestSupport.Events events, String name) {
        return events.received.stream().filter(e -> e.name().equals(name)).toList();
    }

    /** {@code stt.final} and {@code transcript.suppressed} of one meeting, in arrival order, as "event:stream:seg". */
    static List<String> transcript(AppTestSupport.Events events, String id) {
        return events.received.stream()
                .filter(e -> e.name().equals("stt.final") || e.name().equals("transcript.suppressed"))
                .filter(e -> e.data().path("meetingId").asText().equals(id))
                .map(e -> e.name() + ":" + e.data().path("stream").asText() + ":" + e.data().path("seg").asText())
                .toList();
    }

    /** The rows the core asked the panel to remove, as "stream:seg", in arrival order. */
    static List<String> suppressed(AppTestSupport.Events events, String id) {
        return of(events, "transcript.suppressed").stream()
                .filter(e -> e.data().path("meetingId").asText().equals(id))
                .map(e -> e.data().path("stream").asText() + ":" + e.data().path("seg").asText())
                .toList();
    }
}
