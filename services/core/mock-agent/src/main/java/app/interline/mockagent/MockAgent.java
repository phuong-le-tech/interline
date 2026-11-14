package app.interline.mockagent;

import app.interline.agent.AgentConnection;
import app.interline.agent.ControlMessage;
import app.interline.agent.Inbound;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

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
    private static final long HEARTBEAT_PERIOD_MS = 2000;
    private static final long PLAYBACK_JOIN_MS = 2000;

    private final MockOptions options;
    private final AgentConnection connection;
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
            case "audio.ack" -> {
                CaptureRun current = run;
                if (current != null) {
                    current.acknowledged(message.body().path("stream").asText().equals("mic") ? 0 : 1,
                            message.body().path("seq").asLong());
                }
            }
            case "shutdown" -> {
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
        run = new CaptureRun(connection, options, script, pcm, sessionId, resumeOf(request.body().path("resume")));
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
        capture.put("aec", false).put("device_events", false);
        capabilities.put("test_input", true);
        return body;
    }

    private static ObjectNode error(String code, String severity, String message, String sessionId) {
        ObjectNode body = ControlMessage.nodes().objectNode().put("code", code).put("severity", severity)
                .put("retryable", false).put("message", message);
        if (sessionId != null) body.put("session_id", sessionId);
        return body;
    }
}
