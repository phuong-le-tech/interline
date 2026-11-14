package app.interline.app;

import app.interline.domain.AgentInfo;
import app.interline.domain.AudioCapture;
import app.interline.domain.AudioFrame;
import app.interline.domain.CaptureListener;
import app.interline.domain.CaptureRequest;
import app.interline.domain.FinalSegment;
import app.interline.domain.MeetingStatus;
import app.interline.domain.ResumeState;
import app.interline.domain.StreamKind;
import app.interline.domain.UuidV7;
import app.interline.persistence.CaptureStore;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The recording use case: one meeting at a time, from {@code start} to {@code done}, surviving agent restarts.
 * Status flow: recording, degraded while the agent is down, recording again, processing after stop, done.
 */
public class RecordingService implements CaptureListener {
    private static final System.Logger LOG = System.getLogger(RecordingService.class.getName());
    private static final long TICK_MS = 100;
    private static final long STOP_TIMEOUT_MS = 3000;
    private static final long SHUTDOWN_WAIT_MS = 3500;
    private static final long FRAME_MS = 100;
    private static final String PERMISSION_MISSING = "permission_missing";

    /** Raised when a request cannot be served; the HTTP layer maps {@code status} to the response. */
    public static final class RecordingException extends RuntimeException {
        private final int status;

        RecordingException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() { return status; }
    }

    private final DurableCaptureIngest ingest;
    private final CaptureStore store;
    private final EventBus events;
    private final Clock clock;
    private final Path spoolRoot;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "recording-timer");
        thread.setDaemon(true);
        return thread;
    });

    private AudioCapture capture;
    private boolean agentConnected;
    private AgentInfo agent;
    private String unavailableCode;
    private Meeting active;

    /** Mutable state of the meeting being recorded; guarded by the service monitor. */
    private static final class Meeting {
        final String id;
        final CountDownLatch finished = new CountDownLatch(1);
        final Map<StreamKind, Long> lastAcked = new EnumMap<>(StreamKind.class);
        final List<String> spoolDirs = new ArrayList<>();
        MeetingStatus status = MeetingStatus.RECORDING;
        boolean sessionStartPending = true;
        int sessionsStarted;
        long stopRequestedAt;

        Meeting(String id) {
            this.id = id;
            for (StreamKind stream : StreamKind.values()) lastAcked.put(stream, -1L);
        }
    }

    public RecordingService(DurableCaptureIngest ingest, EventBus events, Clock clock, Path dataDirectory) {
        this.ingest = ingest;
        this.store = ingest.store();
        this.events = events;
        this.clock = clock;
        this.spoolRoot = dataDirectory.resolve("spool");
        timer.scheduleWithFixedDelay(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    public synchronized void bind(AudioCapture capture) { this.capture = capture; }

    public synchronized String start() {
        if (capture == null || unavailableCode != null) {
            throw new RecordingException(503, "Capture is unavailable" + (unavailableCode == null ? "" : ": " + unavailableCode));
        }
        if (active != null) throw new RecordingException(409, "A recording is already running");
        String id = UuidV7.next(clock.millis());
        ingest.openMeeting(id);
        active = new Meeting(id);
        if (agentConnected) sendSessionStart();
        return id;
    }

    /** Ask the agent to drain and stop. Completion is reported through the recording.state event. */
    public synchronized void stop() {
        if (active == null) throw new RecordingException(409, "No recording is running");
        if (active.status == MeetingStatus.PROCESSING) return;
        flushAcks();
        active.stopRequestedAt = clock.millis();
        changeState(MeetingStatus.PROCESSING);
        capture.stop(active.id);
    }

    /** Stop any running recording and wait for it to finish, for a clean process exit. */
    public void shutdown() {
        CountDownLatch finished;
        synchronized (this) {
            finished = active == null ? null : active.finished;
            if (active != null) stop();
        }
        try {
            if (finished != null && !finished.await(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
                synchronized (this) { if (active != null) finish(MeetingStatus.DONE, "stopped"); }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        timer.shutdownNow();
    }

    // ---- CaptureListener: callbacks from the agent adapter ------------------------------------------------

    @Override public synchronized void agentConnected(AgentInfo info) {
        agent = info;
        agentConnected = true;
        if (active != null && active.sessionStartPending) sendSessionStart();
    }

    @Override public synchronized void agentLost(String reason) {
        agentConnected = false;
        if (active == null) return;
        if (active.status == MeetingStatus.PROCESSING) {
            finish(MeetingStatus.DONE, "stopped");
            return;
        }
        long resumeAtMs = (Math.min(ingest.watermark(active.id, StreamKind.MIC),
                ingest.watermark(active.id, StreamKind.SYSTEM)) + 1) * FRAME_MS;
        store.endRun(active.id, "agent_crash", clock.millis());
        store.addGapMarker(active.id, resumeAtMs, reason);
        active.sessionStartPending = true;
        changeState(MeetingStatus.DEGRADED);
    }

    @Override public synchronized void captureUnavailable(String code, String message) {
        unavailableCode = code;
        events.publish("capture.unavailable", Map.of("code", code, "message", message));
        if (active != null) {
            events.publish("recording.error", Map.of("meetingId", active.id, "code", code, "message", message));
            finish(MeetingStatus.FAILED, "agent_crash");
        }
    }

    @Override public synchronized void sessionStarted(String sessionId) {
        if (active == null || !active.id.equals(sessionId) || active.status == MeetingStatus.PROCESSING) return;
        changeState(MeetingStatus.RECORDING);
    }

    @Override public synchronized void sessionFailed(String sessionId, String code, String message) {
        if (active == null || !active.id.equals(sessionId)) return;
        events.publish("recording.error", Map.of("meetingId", sessionId, "code", code, "message", message));
        if (PERMISSION_MISSING.equals(code)) {
            unavailableCode = code;
            events.publish("capture.unavailable", Map.of("code", code, "message", message));
            capture.terminate();
        }
        finish(MeetingStatus.FAILED, "agent_crash");
    }

    @Override public synchronized void audio(List<AudioFrame> frames) {
        if (active != null) ingest.append(active.id, frames);
    }

    @Override public synchronized void finalSegment(FinalSegment segment) {
        if (active == null) return;
        if (!ingest.recordFinal(active.id, segment)) return;
        events.publish("stt.final", Map.of("meetingId", active.id, "stream", segment.stream().wireName(),
                "seg", segment.seg(), "speaker", segment.stream().speakerLabel(),
                "startMs", segment.startSample() / SAMPLES_PER_MS, "endMs", segment.endSample() / SAMPLES_PER_MS,
                "text", segment.text()));
    }

    @Override public synchronized void sessionStopped(String sessionId) {
        if (active == null || !active.id.equals(sessionId) || active.status != MeetingStatus.PROCESSING) return;
        flushAcks();
        finish(MeetingStatus.DONE, "stopped");
    }

    // ---- internals ----------------------------------------------------------------------------------------

    private static final long SAMPLES_PER_MS = 16;

    private void sendSessionStart() {
        Meeting meeting = active;
        meeting.sessionStartPending = false;
        boolean resuming = meeting.sessionsStarted > 0;
        if (resuming) {
            store.beginRun(meeting.id, agent.name(), agent.version(), agent.protocol(), clock.millis());
        } else {
            store.describeLatestRun(meeting.id, agent.name(), agent.version(), agent.protocol());
        }
        meeting.sessionsStarted++;
        String spoolDir = spoolRoot.resolve(meeting.id).resolve("run-" + meeting.sessionsStarted).toString();
        ResumeState resume = resuming ? resumeState(meeting) : null;
        meeting.spoolDirs.add(spoolDir);
        capture.start(new CaptureRequest(meeting.id, spoolDir, resume));
    }

    private ResumeState resumeState(Meeting meeting) {
        Map<StreamKind, Long> acked = new EnumMap<>(meeting.lastAcked);
        Map<StreamKind, Long> next = new EnumMap<>(StreamKind.class);
        Map<StreamKind, Long> sttDone = new EnumMap<>(StreamKind.class);
        for (StreamKind stream : StreamKind.values()) {
            next.put(stream, acked.get(stream) + 1);
            sttDone.put(stream, store.sttDoneUntil(meeting.id, stream));
        }
        List<String> previous = new ArrayList<>(meeting.spoolDirs);
        return new ResumeState(previous, acked, sttDone, next);
    }

    private void changeState(MeetingStatus status) {
        active.status = status;
        store.setStatus(active.id, status, clock.millis());
        events.publish("recording.state", Map.of("meetingId", active.id, "state", status.dbValue()));
    }

    private void finish(MeetingStatus terminal, String endReason) {
        Meeting meeting = active;
        store.endRun(meeting.id, endReason, clock.millis());
        meeting.status = terminal;
        store.setStatus(meeting.id, terminal, clock.millis());
        events.publish("recording.state", Map.of("meetingId", meeting.id, "state", terminal.dbValue()));
        active = null;
        meeting.finished.countDown();
    }

    private void tick() {
        try {
            synchronized (this) {
                if (active == null) return;
                flushAcks();
                if (active.status == MeetingStatus.PROCESSING
                        && clock.millis() - active.stopRequestedAt > STOP_TIMEOUT_MS) {
                    LOG.log(System.Logger.Level.WARNING, "agent did not confirm stop in time, closing the meeting");
                    finish(MeetingStatus.DONE, "stopped");
                }
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "recording timer failed", e);
        }
    }

    /** Acknowledge what is durable; the resume point is the last ack that actually reached the agent. */
    private void flushAcks() {
        if (active == null || !agentConnected) return;
        for (StreamKind stream : StreamKind.values()) {
            long durable = ingest.watermark(active.id, stream);
            if (durable > active.lastAcked.get(stream)
                    && capture.acknowledge(stream, durable, DurableCaptureIngest.WINDOW_FRAMES)) {
                active.lastAcked.put(stream, durable);
            }
        }
    }
}
