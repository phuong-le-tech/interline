package app.interline.agent;

import app.interline.domain.AgentInfo;
import app.interline.domain.AudioCapture;
import app.interline.domain.AudioFrame;
import app.interline.domain.CaptureListener;
import app.interline.domain.CaptureRequest;
import app.interline.domain.FinalSegment;
import app.interline.domain.PartialSegment;
import app.interline.domain.PlatformCapabilities;
import app.interline.domain.ResumeState;
import app.interline.domain.StreamKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single Java adapter that turns "an agent executable speaking Agent Protocol v1" into the capture port.
 * It owns the child process, its supervision and the framing; nothing above it knows that a process exists.
 */
public final class AgentPlatformCapabilities implements PlatformCapabilities, AudioCapture, AutoCloseable {
    /** @param command executable followed by its fixed arguments, @param logFile receives the agent stderr */
    public record Config(List<String> command, Path logFile, Duration heartbeatInterval, String coreVersion) { }

    private static final System.Logger LOG = System.getLogger(AgentPlatformCapabilities.class.getName());
    private ProcessRegistry processRegistry;

    public void trackProcesses(ProcessRegistry registry) { this.processRegistry = registry; }
    private static final int PROTOCOL = 1;
    private static final int WINDOW_FRAMES = 50;
    private static final java.util.Set<String> DISPLAY_ONLY = java.util.Set.of("level", "stt.partial", "heartbeat");
    private static final int MAX_BATCH_FRAMES = 32;
    private static final Duration POLL_PERIOD = Duration.ofMillis(20);
    private static final Duration READER_JOIN = Duration.ofSeconds(2);
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(2);
    /** A freshly launched JVM needs time to say hello; the heartbeat deadline only applies after that. */
    private static final Duration STARTUP_GRACE = Duration.ofSeconds(5);
    private static final Duration READY_WAIT = Duration.ofSeconds(15);
    private static final Duration READY_POLL = Duration.ofMillis(50);

    private final Config config;
    private final Clock clock;
    private final CaptureListener listener;
    private final AtomicInteger requestCounter = new AtomicInteger();
    private final Map<String, String> pendingStarts = new ConcurrentHashMap<>();
    private final Map<String, String> pendingImports = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.CompletableFuture<ControlMessage>> pendingExchanges = new ConcurrentHashMap<>();
    private volatile boolean calendarSupported;
    private volatile Runnable calendarChanged = () -> {};
    private volatile Link link;
    private volatile boolean terminated;
    private volatile CountDownLatch shutdownAcknowledged = new CountDownLatch(0);
    private AgentProcess supervisor;
    private ScheduledExecutorService poller;

    public AgentPlatformCapabilities(Config config, Clock clock, CaptureListener listener) {
        this.config = config;
        this.clock = clock;
        this.listener = listener;
    }

    @Override public AudioCapture audioCapture() { return this; }

    /** Launch the agent and start supervising it. A launch failure is reported as unavailable capture. */
    public synchronized void start() {
        Process first;
        try {
            first = launch();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.ERROR, "cannot launch capture agent", e);
            listener.captureUnavailable("agent_launch_failed", "The capture agent could not be started");
            return;
        }
        supervisor = new AgentProcess(first, this::relaunch, clock, config.heartbeatInterval(), STARTUP_GRACE, new AgentProcess.Listener() {
            @Override public void crashed(String reason, Duration restartIn, boolean unavailable) {
                LOG.log(System.Logger.Level.WARNING, "capture agent down ({0}), restart in {1}", reason, restartIn);
                if (unavailable) listener.captureUnavailable("agent_crash_loop", "The capture agent keeps crashing");
            }
        });
        attach(first);
        poller = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-supervisor");
            thread.setDaemon(true);
            return thread;
        });
        poller.scheduleWithFixedDelay(this::pollSafely, POLL_PERIOD.toMillis(), POLL_PERIOD.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void pollSafely() {
        try {
            supervisor.poll();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "agent supervision failed", e);
        }
    }

    private Process launch() throws IOException {
        Files.createDirectories(config.logFile().getParent());
        ProcessBuilder builder = new ProcessBuilder(config.command())
                .redirectError(ProcessBuilder.Redirect.appendTo(config.logFile().toFile()));
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        sanitizeEnvironment(builder.environment());
        Process process = builder.start();
        try {
            if (processRegistry != null) processRegistry.agent(process);
            return process;
        } catch (IOException e) {
            ProcessTree.destroy(process);
            throw e;
        }
    }

    static void sanitizeEnvironment(Map<String, String> environment) {
        environment.keySet().removeIf(name -> {
            String key = name.toUpperCase(java.util.Locale.ROOT);
            return key.startsWith("DYLD_") || java.util.Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(key);
        });
    }

    private Process relaunch() {
        Link previous = link;
        if (previous != null) previous.awaitReader();
        try {
            Process process = launch();
            attach(process);
            return process;
        } catch (IOException e) {
            throw new IllegalStateException("cannot relaunch capture agent", e);
        }
    }

    private void attach(Process process) {
        Link attached = new Link(process, new AgentConnection(process.getInputStream(), process.getOutputStream()));
        link = attached;
        attached.reader = Thread.ofPlatform().name("agent-reader").daemon().start(() -> read(attached));
    }

    private void read(Link source) {
        AgentConnection connection = source.connection;
        List<AudioFrame> batch = new ArrayList<>();
        String reason = "eof";
        try {
            ControlMessage reply = connection.handshake(PROTOCOL, PROTOCOL, config.coreVersion());
            if (!"hello.ack".equals(reply.type())) {
                LOG.log(System.Logger.Level.WARNING, "capture agent rejected: protocol mismatch");
                return;
            }
            supervisor.heartbeatReceived();
            calendarSupported = connection.hello().body().path("capabilities").path("calendar").asBoolean(false);
            source.ready = true;
            calendarChanged.run();
            listener.agentConnected(agentInfo(connection.hello()));
            while (true) {
                Inbound inbound = connection.receive();
                if (inbound instanceof Inbound.Closed closed) {
                    reason = closed.reason();
                    return;
                }
                handle(inbound, batch, connection);
            }
        } catch (IOException e) {
            reason = "handshake_failed";
        } finally {
            source.ready = false;
            calendarSupported = false;
            flush(batch);
            if (!terminated) {
                supervisor.outputClosed(source.process);
                listener.agentLost(reason);
            }
        }
    }

    private void handle(Inbound inbound, List<AudioFrame> batch, AgentConnection connection) {
        if (inbound instanceof AudioChunk chunk) {
            StreamKind.fromWireIndex(chunk.stream()).ifPresent(stream ->
                    batch.add(new AudioFrame(stream, chunk.flags(), chunk.seq(), chunk.firstSample(), chunk.pcm())));
            if (batch.size() >= MAX_BATCH_FRAMES || !connection.hasBufferedInput()) flush(batch);
            return;
        }
        ControlMessage message = (ControlMessage) inbound;
        // Meters and partials are display-only: flushing for each of them would cost one fsync per tick.
        if (!DISPLAY_ONLY.contains(message.type())) flush(batch);
        try {
            dispatch(message);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "failed handling " + message.type(), e);
        }
    }

    private void dispatch(ControlMessage message) {
        var exchange = message.replyTo() == null ? null : pendingExchanges.remove(message.replyTo());
        if (exchange != null) {
            exchange.complete(message);
            return;
        }
        JsonNode body = message.body();
        switch (message.type()) {
            case "calendar.changed" -> calendarChanged.run();
            case "heartbeat" -> supervisor.heartbeatReceived();
            case "session.started" -> {
                pendingStarts.remove(message.replyTo());
                listener.sessionStarted(body.path("session_id").asText(), body.path("t0_wall_ms").asLong(-1));
            }
            case "stt.final" -> StreamKind.fromWireName(body.path("stream").asText()).ifPresent(stream ->
                    onFinal(body, stream));
            case "session.stopped" -> listener.sessionStopped(body.path("session_id").asText());
            case "level" -> StreamKind.fromWireName(body.path("stream").asText()).ifPresent(stream ->
                    listener.level(body.path("session_id").asText(), stream, body.path("rms").asDouble()));
            case "stt.partial" -> StreamKind.fromWireName(body.path("stream").asText()).ifPresent(stream ->
                    listener.partialSegment(body.path("session_id").asText(), new PartialSegment(stream,
                            body.path("seg").asText(), body.path("rev").asLong(), body.path("start_sample").asLong(),
                            body.path("text").asText())));
            case "device.changed" -> StreamKind.fromWireName(body.path("stream").asText()).ifPresent(stream ->
                    listener.deviceChanged(body.path("session_id").asText(), stream,
                            body.path("device").asText(""), body.path("at_sample").asLong()));
            case "permission.denied" -> {
                if (!"calendar".equals(body.path("permission").asText())) listener.permissionDenied(body.path("session_id").asText(null),
                    body.path("permission").asText(), body.path("detection").asText(), body.path("settings_uri").asText());
            }
            case "transcribe.file.done" -> {
                String jobId = body.path("job_id").asText();
                pendingImports.remove(jobId);
                listener.importFinished(jobId, body.path("segments").asInt());
            }
            case "shutdown.ok" -> shutdownAcknowledged.countDown();
            case "error" -> onError(message);
            default -> { /* vad.segment and unknown events carry nothing the core persists */ }
        }
    }

    private void onError(ControlMessage message) {
        JsonNode body = message.body();
        for (var pending : pendingImports.entrySet()) {
            if (pending.getValue().equals(message.replyTo())) {
                pendingImports.remove(pending.getKey());
                listener.importFailed(pending.getKey(), body.path("code").asText("internal"), body.path("message").asText(""));
                return;
            }
        }
        String sessionId = pendingStarts.remove(message.replyTo());
        if (sessionId == null) sessionId = body.path("session_id").asText(null);
        String code = body.path("code").asText("internal");
        LOG.log(System.Logger.Level.WARNING, "capture agent error {0}", code);
        if (sessionId != null && "fatal".equals(body.path("severity").asText())) {
            listener.sessionFailed(sessionId, code, body.path("message").asText(""));
        }
    }

    private static AgentInfo agentInfo(ControlMessage hello) {
        JsonNode agent = hello.body().path("agent");
        JsonNode capabilities = hello.body().path("capabilities");
        return new AgentInfo(agent.path("name").asText("unknown"), agent.path("version").asText("unknown"), PROTOCOL,
                capabilities.path("capture").path("aec").asBoolean(false),
                capabilities.path("stt").path("engine").asText("none"));
    }

    private void flush(List<AudioFrame> batch) {
        if (batch.isEmpty()) return;
        List<AudioFrame> frames = List.copyOf(batch);
        batch.clear();
        try {
            listener.audio(frames);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "audio batch rejected", e);
        }
    }

    private void onFinal(JsonNode body, StreamKind stream) {
        FinalSegment segment = new FinalSegment(stream, body.path("seg").asText(),
                body.path("start_sample").asLong(), body.path("end_sample").asLong(),
                body.path("text").asText(), body.path("model").asText("unknown"),
                body.hasNonNull("avg_logprob") ? body.path("avg_logprob").asDouble() : null);
        String sessionId = body.path("session_id").asText();
        if (pendingImports.containsKey(sessionId)) listener.importedSegment(sessionId, segment);
        else listener.finalSegment(segment);
    }

    @Override public void transcribeFile(String jobId, String path, StreamKind stream, String language) {
        Link current = link;
        if (current == null || !current.ready) {
            listener.importFailed(jobId, "agent_unavailable", "The capture agent is not connected");
            return;
        }
        String id = nextRequestId();
        pendingImports.put(jobId, id);
        ObjectNode body = ControlMessage.nodes().objectNode().put("job_id", jobId).put("path", path)
                .put("stream", stream.wireName());
        ObjectNode stt = body.putObject("stt");
        if (language != null) stt.put("language", language);
        else stt.putNull("language");
        try {
            current.connection.send(ControlMessage.request(id, "transcribe.file", body));
        } catch (IOException e) {
            pendingImports.remove(jobId);
            listener.importFailed(jobId, "agent_unavailable", "The capture agent did not accept the request");
        }
    }

    @Override public void start(CaptureRequest request) {
        Link current = link;
        if (current == null || !current.ready) {
            listener.sessionFailed(request.sessionId(), "agent_unavailable", "The capture agent is not connected");
            return;
        }
        String id = nextRequestId();
        pendingStarts.put(id, request.sessionId());
        try {
            current.connection.send(ControlMessage.request(id, "session.start", startBody(request)));
        } catch (IOException e) {
            pendingStarts.remove(id);
            listener.sessionFailed(request.sessionId(), "agent_unavailable", "The capture agent did not accept the request");
        }
    }

    @Override public void stop(String sessionId) {
        Link current = link;
        if (current == null || !current.ready) {
            listener.sessionStopped(sessionId);
            return;
        }
        ObjectNode body = ControlMessage.nodes().objectNode().put("session_id", sessionId);
        try {
            current.connection.send(ControlMessage.request(nextRequestId(), "session.stop", body));
        } catch (IOException e) {
            listener.sessionStopped(sessionId);
        }
    }

    @Override public boolean acknowledge(StreamKind stream, long seq, int window) {
        Link current = link;
        if (current == null || !current.ready || !current.process.isAlive()) return false;
        ObjectNode body = ControlMessage.nodes().objectNode()
                .put("stream", stream.wireName()).put("seq", seq).put("window", window);
        try {
            current.connection.send(ControlMessage.event("audio.ack", body));
            return true;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "ack not delivered, the agent is going away");
            return false;
        }
    }

    public boolean calendarSupported() { return calendarSupported; }

    public JsonNode capabilities() { return awaitReadyLink().connection.hello().body().path("capabilities").deepCopy(); }

    public JsonNode permissions(String permission) {
        ObjectNode body = ControlMessage.nodes().objectNode();
        if (permission != null) body.put("permission", permission);
        ControlMessage reply = exchange(permission == null ? "permission.status" : "permission.request", body, Duration.ofSeconds(25));
        if ("error".equals(reply.type())) throw new IllegalStateException("The agent could not read permissions");
        return reply.body().deepCopy();
    }

    public app.interline.domain.CalendarSource calendar() { return new AgentCalendarSource(this::exchange); }

    /** The callback must queue work: the reader must remain free to deliver its reply. */
    public void onCalendarChanged(Runnable callback) { calendarChanged = callback; }

    /** The OS secret store, reached through the connected agent. */
    public app.interline.domain.SecretStore secrets() { return new AgentSecretStore(this::exchange); }

    /** The agent may still be starting right after boot or restarting: wait briefly instead of failing at once. */
    private Link awaitReadyLink() {
        long deadline = System.nanoTime() + READY_WAIT.toNanos();
        while (true) {
            Link current = link;
            if (current != null && current.ready) return current;
            if (terminated || System.nanoTime() >= deadline) throw new IllegalStateException("The capture agent is not connected");
            try {
                Thread.sleep(READY_POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for the capture agent", e);
            }
        }
    }

    private ControlMessage exchange(String type, ObjectNode body, Duration timeout) {
        Link current = awaitReadyLink();
        String id = nextRequestId();
        var reply = new java.util.concurrent.CompletableFuture<ControlMessage>();
        pendingExchanges.put(id, reply);
        try {
            current.connection.send(ControlMessage.request(id, type, body));
            return reply.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (IOException e) {
            throw new IllegalStateException("The capture agent did not accept the request", e);
        } catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("The capture agent did not answer " + type, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for " + type, e);
        } finally {
            pendingExchanges.remove(id);
        }
    }

    @Override public void terminate() {
        terminated = true;
        if (poller != null) poller.shutdownNow();
        if (supervisor != null) supervisor.close();
        Link current = link;
        if (current != null) current.connection.close();
    }

    /** Ask the agent to exit, wait briefly, then make sure nothing is left. */
    @Override public void close() {
        Link current = link;
        if (current != null && current.ready && !terminated) {
            shutdownAcknowledged = new CountDownLatch(1);
            try {
                current.connection.send(ControlMessage.request(nextRequestId(), "shutdown", ControlMessage.nodes().objectNode()));
                shutdownAcknowledged.await(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
                current.process.waitFor(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (IOException e) {
                LOG.log(System.Logger.Level.DEBUG, "agent already gone at shutdown");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        terminate();
    }

    private ObjectNode startBody(CaptureRequest request) {
        ObjectNode body = ControlMessage.nodes().objectNode().put("session_id", request.sessionId());
        var streams = body.putArray("streams");
        for (StreamKind stream : StreamKind.values()) streams.add(stream.wireName());
        body.putObject("audio").put("sample_rate", 16000).put("format", "s16le").put("channels", 1)
                .put("chunk_ms", 100).put("window", WINDOW_FRAMES);
        body.put("spool_dir", request.spoolDir());
        var stt = body.putObject("stt");
        if (request.language() != null) stt.put("language", request.language());
        else stt.putNull("language");
        if (request.modelPath() != null) stt.put("model_path", request.modelPath());
        if (request.vadModelPath() != null) stt.put("vad_model_path", request.vadModelPath());
        ResumeState resume = request.resume();
        if (resume == null) {
            body.putNull("resume");
        } else {
            ObjectNode node = body.putObject("resume");
            var dirs = node.putArray("previous_spool_dirs");
            resume.previousSpoolDirs().forEach(dirs::add);
            putPerStream(node.putObject("acked"), resume.acked());
            putPerStream(node.putObject("stt_done_until_sample"), resume.sttDoneUntilSample());
            putPerStream(node.putObject("next_seq"), resume.nextSeq());
        }
        return body;
    }

    private static void putPerStream(ObjectNode target, Map<StreamKind, Long> values) {
        values.forEach((stream, value) -> target.put(stream.wireName(), value));
    }

    private String nextRequestId() { return "c-" + requestCounter.incrementAndGet(); }

    private static final class Link {
        final Process process;
        final AgentConnection connection;
        volatile boolean ready;
        Thread reader;

        Link(Process process, AgentConnection connection) {
            this.process = process;
            this.connection = connection;
        }

        void awaitReader() {
            try {
                if (reader != null) reader.join(READER_JOIN.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
