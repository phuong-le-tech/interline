package app.interline.mockagent;

import app.interline.agent.AgentConnection;
import app.interline.agent.ControlMessage;
import app.interline.agent.Inbound;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Standalone Agent Protocol v1 agent that replays a fixture instead of capturing audio. It is spawned and
 * supervised exactly like a real agent. Stdout carries frames only; everything else goes to stderr.
 */
public final class MockAgent {
    static final int EXIT_OK = 0;
    static final int EXIT_BAD_ARGUMENTS = 2;
    static final int EXIT_PROTOCOL_MISMATCH = 3;
    private static final String AGENT_NAME = "mock-agent";
    private static final String AGENT_VERSION = "0.1.0";
    private static final String STT_ENGINE = "scripted";
    private static final int DEFAULT_WINDOW = 50;
    private static final long HEARTBEAT_PERIOD_MS = 2000;
    private static final long PLAYBACK_JOIN_MS = 2000;

    private final MockOptions options;
    private final AgentConnection connection;
    private final Map<String, String> secrets = new ConcurrentHashMap<>();
    private final com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
    private final java.util.concurrent.ScheduledExecutorService calendarChanges =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform()
                    .name("mock-calendar").daemon().unstarted(task));
    private volatile JsonNode changedCalendar;
    private boolean calendarStarted;
    private boolean calendarDeniedSent;
    private Script script;
    private byte[][] pcm;
    private CaptureRun run;
    private Thread playback;

    private MockAgent(MockOptions options, InputStream in, OutputStream out) {
        this.options = options;
        this.connection = new AgentConnection(in, out);
    }

    public static void main(String[] args) {
        MockOptions options;
        try {
            options = MockOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("mock-agent: " + e.getMessage());
            System.exit(EXIT_BAD_ARGUMENTS);
            return;
        }
        int code;
        try {
            code = new MockAgent(options, System.in, System.out).serve();
        } catch (IOException e) {
            System.err.println("mock-agent: " + e.getMessage());
            code = 1;
        }
        System.exit(code);
    }

    private int serve() throws IOException {
        connection.send(ControlMessage.request("a-1", "hello", helloBody()));
        if (!(connection.receive() instanceof ControlMessage reply) || !"hello.ack".equals(reply.type())) {
            System.err.println("mock-agent: handshake refused");
            return EXIT_PROTOCOL_MISMATCH;
        }
        Thread.ofPlatform().name("heartbeat").daemon().start(this::heartbeats);
        while (true) {
            Inbound inbound = connection.receive();
            if (inbound instanceof Inbound.Closed) {
                stopPlayback();
                calendarChanges.shutdownNow();
                return EXIT_OK;
            }
            if (inbound instanceof ControlMessage message && handle(message)) return EXIT_OK;
        }
    }

    /** Returns true once a clean shutdown was acknowledged. */
    private boolean handle(ControlMessage message) throws IOException {
        switch (message.type()) {
            case "session.start" -> startSession(message);
            case "session.stop" -> stopSession(message);
            case "permission.status", "permission.request" -> answerPermissions(message);
            case "transcribe.file" -> transcribeFile(message);
            case "calendar.query" -> answerCalendar(message);
            case "secret.get", "secret.put", "secret.delete" -> answerSecret(message);
            case "audio.ack" -> {
                CaptureRun current = run;
                if (current != null) {
                    current.acknowledged(message.body().path("stream").asText().equals("mic") ? 0 : 1,
                            message.body().path("seq").asLong());
                }
            }
            case "shutdown" -> {
                calendarChanges.shutdownNow();
                stopPlayback();
                connection.send(ControlMessage.response(message.id(), "shutdown.ok", ControlMessage.nodes().objectNode()));
                return true;
            }
            default -> {
                if (message.id() != null) {
                    connection.send(ControlMessage.response(message.id(), "error",
                            error("unsupported_message", "warn", "Unsupported request " + message.type(), null)));
                }
            }
        }
        return false;
    }

    private void startSession(ControlMessage request) throws IOException {
        String sessionId = request.body().path("session_id").asText();
        if (options.denySystemAudio()) {
            connection.send(ControlMessage.response(request.id(), "error",
                    error("permission_missing", "fatal", "System audio permission denied", sessionId)));
            return;
        }
        if (playback != null && playback.isAlive()) {
            connection.send(ControlMessage.response(request.id(), "error",
                    error("invalid_request", "warn", "A session is already running", sessionId)));
            return;
        }
        loadFixtureOnce();
        int window = request.body().path("audio").path("window").asInt(DEFAULT_WINDOW);
        run = new CaptureRun(connection, options, script, pcm, sessionId, window, resumeOf(request.body().path("resume")));
        ObjectNode started = ControlMessage.nodes().objectNode().put("session_id", sessionId)
                .put("t0_wall_ms", System.currentTimeMillis());
        started.putObject("devices");
        started.putObject("permissions").put("microphone", "granted").put("system_audio", "granted");
        connection.send(ControlMessage.response(request.id(), "session.started", started));
        playback = Thread.ofPlatform().name("playback").daemon().start(run);
    }

    private void loadFixtureOnce() throws IOException {
        if (script != null) return;
        if (options.wav() == null || options.script() == null) {
            throw new IOException("--wav and --script are required to run a session");
        }
        FixtureWav.generateIfMissing(options.wav(), options.script());
        script = Script.load(options.script());
        pcm = FixtureWav.readChannels(options.wav());
    }

    private static CaptureRun.ResumeRequest resumeOf(JsonNode resume) {
        if (resume.isMissingNode() || resume.isNull()) return null;
        long[] next = {resume.path("next_seq").path("mic").asLong(), resume.path("next_seq").path("system").asLong()};
        long[] done = {resume.path("stt_done_until_sample").path("mic").asLong(),
                resume.path("stt_done_until_sample").path("system").asLong()};
        return new CaptureRun.ResumeRequest(next, done);
    }

    private void stopSession(ControlMessage request) throws IOException {
        long[] lastSeq = stopPlayback();
        ObjectNode body = ControlMessage.nodes().objectNode().put("session_id", request.body().path("session_id").asText());
        body.putObject("final_seq").put("mic", lastSeq[0]).put("system", lastSeq[1]);
        connection.send(ControlMessage.response(request.id(), "session.stopped", body));
    }

    /** The mock never prompts: both permissions are simply reported, mirroring the status query. */
    private void answerPermissions(ControlMessage request) throws IOException {
        if (request.id() == null) return;
        ObjectNode body = ControlMessage.nodes().objectNode()
                .put("microphone", "granted").put("system_audio", options.denySystemAudio() ? "denied" : "granted")
                .put("calendar", options.denyCalendar() ? "denied" : "granted");
        connection.send(ControlMessage.response(request.id(), request.type(), body));
    }

    private void answerCalendar(ControlMessage request) throws IOException {
        JsonNode body = request.body();
        if (request.id() == null) return;
        if (!body.path("from_ms").isIntegralNumber() || !body.path("to_ms").isIntegralNumber()
                || !body.path("from_ms").canConvertToLong() || !body.path("to_ms").canConvertToLong()
                || body.path("to_ms").asLong() <= body.path("from_ms").asLong()
                || (double) body.path("to_ms").asLong() - body.path("from_ms").asLong() > 31L * 86400000) {
            connection.send(ControlMessage.response(request.id(), "error", error("invalid_request", "warn", "Invalid calendar window", null)));
            return;
        }
        ObjectNode reply = ControlMessage.nodes().objectNode().put("complete", true);
        var events = reply.putArray("events");
        if (options.denyCalendar()) {
            if (!calendarDeniedSent) {
                calendarDeniedSent = true;
                connection.send(ControlMessage.event("permission.denied", ControlMessage.nodes().objectNode()
                        .put("permission", "calendar").put("detection", "tcc_denied")
                        .put("settings_uri", "x-apple.systempreferences:com.apple.preference.security?Privacy_Calendars")));
            }
        } else {
            JsonNode calendar;
            try {
                calendar = changedCalendar != null ? changedCalendar : options.script() == null
                        ? ControlMessage.nodes().objectNode() : json.readTree(options.script().toFile()).path("calendar");
            } catch (IOException e) {
                connection.send(ControlMessage.response(request.id(), "error", error("calendar_unavailable", "warn", "Cannot read scripted calendar", null)));
                return;
            }
            for (JsonNode event : calendar.path("events")) {
                long start = event.path("starts_at").asLong(), end = event.path("ends_at").asLong();
                if (start < body.path("to_ms").asLong() && (end > body.path("from_ms").asLong()
                        || start == end && start >= body.path("from_ms").asLong())) {
                    if (events.size() == 5000) { reply.put("complete", false); break; }
                    events.add(event);
                }
            }
            if (!calendar.path("complete").asBoolean(true)) reply.put("complete", false);
            if (!calendarStarted) {
                calendarStarted = true;
                for (JsonNode change : calendar.path("changes")) {
                    calendarChanges.schedule(() -> {
                        changedCalendar = change;
                        try { connection.send(ControlMessage.event("calendar.changed", ControlMessage.nodes().objectNode())); }
                        catch (IOException ignored) { /* the core has gone away */ }
                    }, Math.max(0, change.path("after_ms").asLong()), java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            }
        }
        connection.send(ControlMessage.response(request.id(), "calendar.events", reply));
    }

    /** Scripted import: the script regions of the requested stream that fit the file, else one final over all of it. */
    private void transcribeFile(ControlMessage request) throws IOException {
        String jobId = request.body().path("job_id").asText();
        String stream = request.body().path("stream").asText();
        long durationSamples;
        try {
            loadFixtureOnce();
            durationSamples = FileImport.durationSamples(Path.of(request.body().path("path").asText()));
        } catch (IOException e) {
            connection.send(ControlMessage.response(request.id(), "error",
                    error("invalid_request", "warn", "Cannot read import file: " + e.getMessage(), null)));
            return;
        }
        List<Region> regions = FileImport.regionsFor(script, stream, durationSamples);
        for (Region region : regions) {
            ObjectNode body = ControlMessage.nodes().objectNode().put("session_id", jobId).put("stream", stream)
                    .put("seg", region.seg()).put("start_sample", region.startSample())
                    .put("end_sample", region.endSample()).put("text", region.text())
                    .put("lang", "fr").put("avg_logprob", -0.3).put("model", "mock-script");
            body.putNull("words");
            connection.send(ControlMessage.event("stt.final", body));
        }
        ObjectNode done = ControlMessage.nodes().objectNode().put("job_id", jobId)
                .put("segments", regions.size()).put("duration_samples", durationSamples);
        connection.send(ControlMessage.response(request.id(), "transcribe.file.done", done));
    }

    /** In-memory secret store: the stand-in for the Keychain. Values are never written to stderr. */
    private void answerSecret(ControlMessage request) throws IOException {
        if (request.id() == null) return;
        String account = request.body().path("account").asText("");
        if (account.isEmpty()) {
            connection.send(ControlMessage.response(request.id(), "error",
                    error("invalid_request", "warn", "account is required", null)));
            return;
        }
        ObjectNode reply = ControlMessage.nodes().objectNode();
        switch (request.type()) {
            case "secret.put" -> secrets.put(account, request.body().path("value").asText(""));
            case "secret.delete" -> secrets.remove(account);
            default -> {
                String value = secrets.get(account);
                if (value == null) {
                    connection.send(ControlMessage.response(request.id(), "error",
                            error("secret_not_found", "warn", "No secret for this account", null)));
                    return;
                }
                reply.put("value", value);
            }
        }
        connection.send(ControlMessage.response(request.id(), request.type(), reply));
    }

    private long[] stopPlayback() {
        long[] lastSeq = {-1, -1};
        if (run != null) lastSeq = run.stop();
        try {
            if (playback != null) playback.join(PLAYBACK_JOIN_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return lastSeq;
    }

    private void heartbeats() {
        try {
            while (true) {
                ObjectNode body = ControlMessage.nodes().objectNode().put("stt_backlog_ms", 0).put("rss_mb", 0);
                connection.send(ControlMessage.event("heartbeat", body));
                Thread.sleep(HEARTBEAT_PERIOD_MS);
            }
        } catch (IOException e) {
            System.err.println("mock-agent: heartbeat stopped: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ObjectNode helloBody() {
        ObjectNode body = ControlMessage.nodes().objectNode();
        body.putObject("protocol").put("min", options.protocol()).put("max", options.protocol());
        body.putObject("agent").put("name", AGENT_NAME).put("version", AGENT_VERSION).put("os", "mock")
                .put("os_version", "none").put("arch", "none");
        ObjectNode capabilities = body.putObject("capabilities");
        ObjectNode capture = capabilities.putObject("capture");
        capture.putArray("streams").add("mic").add("system");
        capture.put("aec", false).put("device_events", options.deviceChangeAtSample() >= 0);
        capabilities.put("test_input", true).put("calendar", true);
        capabilities.putObject("stt").put("engine", STT_ENGINE);
        return body;
    }

    private static ObjectNode error(String code, String severity, String message, String sessionId) {
        ObjectNode body = ControlMessage.nodes().objectNode().put("code", code).put("severity", severity)
                .put("retryable", false).put("message", message);
        if (sessionId != null) body.put("session_id", sessionId);
        return body;
    }
}
