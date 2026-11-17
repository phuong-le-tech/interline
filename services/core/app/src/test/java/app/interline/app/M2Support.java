package app.interline.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static app.interline.app.AppTestSupport.HTTP;
import static app.interline.app.AppTestSupport.ROOT;
import static app.interline.app.AppTestSupport.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** Shared helpers of the M2 @SpringBootTest layer: boot, HTTP, seeding the golden meetings, provider setup. */
final class M2Support {
    static final ObjectMapper JSON = new ObjectMapper();
    /** Test credential only; never a real key. */
    static final String KEY = "sk-test-m2-0123456789abcdef";

    private M2Support() { }

    // ---- boot ---------------------------------------------------------------------------------------------------

    /** The real core with the real MockAgent; {@code extra} are additional --property=value arguments. */
    static ConfigurableApplicationContext boot(Path data, String... extra) {
        return boot(data, ROOT.resolve("services/core/mock-agent/bin/mock-agent"), extra);
    }

    static ConfigurableApplicationContext boot(Path data, Path agent, String... extra) {
        List<String> args = new ArrayList<>(List.of("--server.port=0", "--server.address=127.0.0.1",
                "--interline.token=" + TOKEN, "--interline.data-dir=" + data, "--interline.log-dir=" + data.resolve("logs"),
                "--spring.main.banner-mode=off", "--interline.agent=" + agent));
        List<String> agentArgs = AppTestSupport.agentArgs(AppTestSupport.fixture(data), null);
        for (int i = 0; i < agentArgs.size(); i++) args.add("--interline.agent-args[" + i + "]=" + agentArgs.get(i));
        args.addAll(List.of(extra));
        ConfigurableApplicationContext context = SpringApplication.run(InterlineApplication.class, args.toArray(String[]::new));
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                assertThat(call(port(context), "GET", "/meetings", null).status()).isEqualTo(200));
        return context;
    }

    static int port(ConfigurableApplicationContext context) {
        return ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    // ---- HTTP ---------------------------------------------------------------------------------------------------

    record Resp(int status, String body, String contentType) {
        JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (Exception e) {
                throw new AssertionError("not JSON (status " + status + "): " + body, e);
            }
        }
    }

    /** Any method, any status; {@code path} is relative to /api/v1 and may carry a query string. */
    static Resp call(int port, String method, String path, Object body) {
        try {
            String payload = body == null ? null : body instanceof String s ? s : JSON.writeValueAsString(body);
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
                    .timeout(Duration.ofSeconds(60)).header("Authorization", "Bearer " + TOKEN)
                    .header("Content-Type", "application/json");
            request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Resp(response.statusCode(), response.body(), response.headers().firstValue("Content-Type").orElse(""));
        } catch (Exception e) {
            throw new AssertionError("HTTP " + method + " " + path + " failed", e);
        }
    }

    static Resp ok(Resp response, String what) {
        assertThat(response.status()).as("%s: %s", what, response.body()).isBetween(200, 299);
        return response;
    }

    // ---- blocks -------------------------------------------------------------------------------------------------

    static Resp putBlock(int port, String meeting, String id, Object rev, String kind, String position, String text, Long anchorMs) {
        ObjectNode body = JSON.createObjectNode();
        if (rev != null) body.set("client_rev", JSON.valueToTree(rev));
        body.put("kind", kind).put("position", position).put("content", text).put("plain_text", text);
        if (anchorMs != null) body.put("anchor_ms", anchorMs);
        return call(port, "PUT", "/meetings/" + meeting + "/blocks/" + id, body);
    }

    static List<JsonNode> blocks(int port, String meeting) {
        List<JsonNode> blocks = new ArrayList<>();
        ok(call(port, "GET", "/meetings/" + meeting + "/blocks", null), "list blocks").json().forEach(blocks::add);
        return blocks;
    }

    static List<JsonNode> blocksOf(List<JsonNode> blocks, String origin) {
        return blocks.stream().filter(b -> b.path("origin").asText().equals(origin)).toList();
    }

    // ---- golden meetings ----------------------------------------------------------------------------------------

    record Seg(long id, String stream, String speaker, long startMs, long endMs, String text) { }

    record Note(String id, String kind, long anchorMs, String text) { }

    record Golden(int n, String title, String language, long t0WallMs, List<Seg> segments, List<Note> notes, String reply) {
        String meetingId() { return "gold-" + n; }
    }

    static Golden golden(int n) {
        try {
            Path dir = ROOT.resolve("fixtures/golden/meeting-" + n);
            JsonNode meeting = JSON.readTree(dir.resolve("meeting.json").toFile());
            List<Seg> segs = new ArrayList<>();
            meeting.path("segments").forEach(s -> segs.add(new Seg(s.path("id").asLong(), s.path("stream").asText(),
                    s.path("speaker").asText(), s.path("start_ms").asLong(), s.path("end_ms").asLong(), s.path("text").asText())));
            List<Note> notes = new ArrayList<>();
            meeting.path("notes").forEach(x -> notes.add(new Note(x.path("id").asText(), x.path("kind").asText(),
                    x.path("anchor_ms").asLong(), x.path("text").asText())));
            return new Golden(n, meeting.path("title").asText(), meeting.path("language").asText(),
                    meeting.path("t0_wall_ms").asLong(), segs, notes, Files.readString(dir.resolve("stub-reply.json"), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new AssertionError("cannot load golden meeting " + n, e);
        }
    }

    /** Insert a finished meeting with its speakers and transcript straight into the core's database. */
    static void seedMeeting(Path data, Golden g) {
        String meeting = g.meetingId();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("db.sqlite"))) {
            update(c, "PRAGMA busy_timeout = 5000");
            update(c, "INSERT INTO meetings(id,title,status,language,started_at,ended_at,t0_wall_ms,created_at,updated_at) "
                    + "VALUES (?,?,'done',?,?,?,?,?,?)", meeting, g.title(), g.language(), g.t0WallMs(),
                    g.t0WallMs() + 90_000, g.t0WallMs(), g.t0WallMs(), g.t0WallMs());
            update(c, "INSERT INTO speakers(id,meeting_id,source,label) VALUES (?,?,'mic','Me')", "spk-me-" + meeting, meeting);
            update(c, "INSERT INTO speakers(id,meeting_id,source,label) VALUES (?,?,'system','Them')", "spk-them-" + meeting, meeting);
            for (Seg s : g.segments()) {
                String speaker = (s.speaker().equals("Me") ? "spk-me-" : "spk-them-") + meeting;
                update(c, "INSERT INTO transcript_segments(id,meeting_id,speaker_id,agent_seg,start_ms,end_ms,text,model) "
                        + "VALUES (?,?,?,?,?,?,?,'golden')", s.id(), meeting, speaker, "seg-" + s.id(), s.startMs(), s.endMs(), s.text());
            }
        } catch (Exception e) {
            throw new AssertionError("cannot seed " + meeting, e);
        }
    }

    /** Mark a seeded segment as an echo hidden from the transcript. */
    static void suppress(Path data, long segmentId) {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("db.sqlite"))) {
            update(c, "PRAGMA busy_timeout = 5000");
            update(c, "UPDATE transcript_segments SET suppressed = 1 WHERE id = ?", segmentId);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static void update(Connection c, String sql, Object... params) throws Exception {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) statement.setObject(i + 1, params[i]);
            statement.execute();
        }
    }

    /** PUT every note of the golden meeting as a user block (rev 1, positions a0, a1, ...). */
    static void putNotes(int port, Golden g) {
        for (int i = 0; i < g.notes().size(); i++) {
            Note n = g.notes().get(i);
            ok(putBlock(port, g.meetingId(), n.id(), 1, n.kind(), "a" + i, n.text(), n.anchorMs()), "PUT " + n.id());
        }
    }

    // ---- providers ----------------------------------------------------------------------------------------------

    /** Save an OpenAI-compatible provider pointing at the stub and store the test key; returns the provider id. */
    static String provider(int port, FakeOpenAi stub, Map<String, Object> overrides) {
        ObjectNode body = JSON.createObjectNode().put("kind", "openai_compatible").put("name", "Stub")
                .put("base_url", stub.baseUrl()).put("model", "stub-model").put("supports_json_schema", false);
        overrides.forEach((k, v) -> body.set(k, JSON.valueToTree(v)));
        String id = ok(call(port, "POST", "/providers", body), "create provider").json().path("id").asText();
        assertThat(call(port, "PUT", "/providers/" + id + "/key", Map.of("key", KEY)).status()).isEqualTo(204);
        return id;
    }

    static Resp enhance(int port, String meeting, Object body) {
        return call(port, "POST", "/meetings/" + meeting + "/enhance", body == null ? Map.of() : body);
    }

    // ---- database -----------------------------------------------------------------------------------------------

    static List<Map<String, Object>> rows(Path data, String sql, Object... args) throws Exception {
        return AppTestSupport.rows(data, sql, args);
    }

    /** Every string value anywhere in a JSON document (field names unspecified on purpose). */
    static List<String> allStrings(JsonNode node) {
        List<String> strings = new ArrayList<>();
        if (node.isTextual()) strings.add(node.asText());
        node.forEach(child -> strings.addAll(allStrings(child)));
        return strings;
    }
}
