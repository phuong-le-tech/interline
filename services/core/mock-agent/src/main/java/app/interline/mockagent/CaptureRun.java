package app.interline.mockagent;

import app.interline.agent.AgentConnection;
import app.interline.agent.AudioChunk;
import app.interline.agent.ControlMessage;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * Plays the fixture on the session timeline at {@code speed}: every 100 ms tick sends one frame per stream and
 * then the scripted finals that end inside it. Honors the ack window and can resume after a crash.
 */
final class CaptureRun implements Runnable {
    static final int FRAME_SAMPLES = 1600;
    static final int CRASH_EXIT_CODE = 13;
    static final String SYSTEM_AUDIO_SETTINGS_URI =
            "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture";
    private static final String[] STREAMS = {"mic", "system"};
    private static final long TICK_NANOS = 100_000_000L;
    private static final long WINDOW_POLL_NANOS = 1_000_000L;
    private static final long MAX_CATCH_UP_NANOS = 50_000_000L;
    private static final long SPIN_NANOS = 200_000_000L;
    private static final int RECOVERED_FLAG = 1;
    private static final double PCM_FULL_SCALE = 32768.0;
    /** Fractions of a region at which a partial hypothesis is revised before the final. */
    private static final double[] PARTIAL_AT = {0.2, 0.6};
    private static final long SILENT_DETECTION_TICK = 10;
    private static final String DEVICE_AFTER_CHANGE = "mock-device-2";

    private final AgentConnection connection;
    private final MockOptions options;
    private final Script script;
    private final byte[][] pcm;
    private final String sessionId;
    private final int window;
    private final long[] nextSeq = new long[2];
    private final long[] sttDone = new long[2];
    private final boolean resumed;
    private final long[] lastSent = {-1, -1};
    private final long[] acked = new long[2];
    private final Map<String, Integer> partialRevisions = new HashMap<>();
    private boolean deviceChangeSent;
    private boolean denialSent;
    private volatile boolean stopping;

    CaptureRun(AgentConnection connection, MockOptions options, Script script, byte[][] pcm,
               String sessionId, int window, ResumeRequest resume) {
        this.connection = connection;
        this.options = options;
        this.script = script;
        this.pcm = pcm;
        this.sessionId = sessionId;
        this.window = window;
        this.resumed = resume != null;
        if (resumed) {
            for (int i = 0; i < 2; i++) {
                nextSeq[i] = resume.nextSeq()[i];
                sttDone[i] = resume.sttDone()[i];
            }
        }
        for (int i = 0; i < 2; i++) acked[i] = nextSeq[i] - 1;
    }

    record ResumeRequest(long[] nextSeq, long[] sttDone) { }

    void acknowledged(int stream, long seq) {
        synchronized (acked) {
            acked[stream] = Math.max(acked[stream], seq);
        }
    }

    /** Ask the playback loop to end and return the last seq sent per stream. */
    long[] stop() {
        stopping = true;
        return lastSent.clone();
    }

    @Override public void run() {
        try {
            play();
        } catch (IOException e) {
            System.err.println("mock-agent: stream closed: " + e.getMessage());
        }
    }

    private void play() throws IOException {
        long totalTicks = script.samples() / FRAME_SAMPLES;
        long startTick = Math.min(nextSeq[0], nextSeq[1]);
        long begin = System.nanoTime();
        for (long tick = startTick; tick < totalTicks && !stopping; tick++) {
            if (!resumed && options.crashAtSample() >= 0 && tick * FRAME_SAMPLES >= options.crashAtSample()) {
                Runtime.getRuntime().halt(CRASH_EXIT_CODE);
            }
            for (int stream = 0; stream < 2; stream++) sendFrame(stream, tick);
            sendLevels(tick);
            sendDeviceChange((tick + 1) * FRAME_SAMPLES);
            sendSilentDenial(tick);
            sendPartials(tick);
            sendFinals(tick);
            long deadline = begin + (long) ((tick - startTick + 1) * TICK_NANOS / options.speed());
            long behind = System.nanoTime() - deadline;
            if (behind > MAX_CATCH_UP_NANOS) {
                // A stall (JIT, GC, a full window) must not turn into a burst of frames and meter readings.
                begin += behind;
                deadline += behind;
            }
            waitUntil(deadline);
        }
    }

    /**
     * Sleep timers on a loaded or throttled macOS process overshoot by 100 ms or more, which wrecks the 10 Hz
     * meter cadence the conformance suite checks. Only the long part of a wait is slept; the last stretch spins.
     */
    private void waitUntil(long deadline) {
        long sleepable = deadline - System.nanoTime() - SPIN_NANOS;
        if (sleepable > 0) LockSupport.parkNanos(sleepable);
        while (!stopping && System.nanoTime() < deadline) Thread.onSpinWait();
    }

    private void sendFrame(int stream, long seq) throws IOException {
        if (seq < nextSeq[stream]) return;
        while (!stopping && seq - ackedValue(stream) > window) LockSupport.parkNanos(WINDOW_POLL_NANOS);
        if (stopping) return;
        int from = (int) (seq * FRAME_SAMPLES * 2);
        byte[] chunk = java.util.Arrays.copyOfRange(pcm[stream], from, from + FRAME_SAMPLES * 2);
        boolean replayed = resumed && seq < nextSeq[stream] + window;
        connection.send(new AudioChunk(stream, replayed ? RECOVERED_FLAG : 0, seq, seq * FRAME_SAMPLES,
                System.nanoTime(), chunk));
        lastSent[stream] = seq;
    }

    /** One RMS reading per stream and 100 ms frame, which is the 10 Hz meter rate. */
    private void sendLevels(long tick) throws IOException {
        if (stopping) return;
        for (int stream = 0; stream < 2; stream++) {
            int from = (int) (tick * FRAME_SAMPLES * 2);
            java.nio.ByteBuffer samples = java.nio.ByteBuffer.wrap(pcm[stream], from, FRAME_SAMPLES * 2)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            double sum = 0;
            double peak = 0;
            for (int i = 0; i < FRAME_SAMPLES; i++) {
                double value = samples.getShort() / PCM_FULL_SCALE;
                sum += value * value;
                peak = Math.max(peak, Math.abs(value));
            }
            ObjectNode body = ControlMessage.nodes().objectNode().put("session_id", sessionId)
                    .put("stream", STREAMS[stream]).put("rms", Math.sqrt(sum / FRAME_SAMPLES))
                    .put("peak", Math.min(peak, 1.0));
            connection.send(ControlMessage.event("level", body));
        }
    }

    private void sendDeviceChange(long horizon) throws IOException {
        if (stopping || deviceChangeSent || options.deviceChangeAtSample() < 0 || horizon < options.deviceChangeAtSample()) return;
        deviceChangeSent = true;
        ObjectNode body = ControlMessage.nodes().objectNode().put("session_id", sessionId).put("stream", STREAMS[0])
                .put("device", DEVICE_AFTER_CHANGE).put("at_sample", horizon);
        connection.send(ControlMessage.event("device.changed", body));
    }

    private void sendSilentDenial(long tick) throws IOException {
        if (stopping || denialSent || !options.silentSystem() || resumed || tick < SILENT_DETECTION_TICK) return;
        denialSent = true;
        ObjectNode body = ControlMessage.nodes().objectNode().put("session_id", sessionId)
                .put("permission", "system_audio").put("detection", "silent_stream")
                .put("settings_uri", SYSTEM_AUDIO_SETTINGS_URI);
        connection.send(ControlMessage.event("permission.denied", body));
    }

    /** Revise each open region's hypothesis a few times, with a growing prefix of the scripted text. */
    private void sendPartials(long tick) throws IOException {
        if (stopping) return;
        long horizon = (tick + 1) * FRAME_SAMPLES;
        for (Region region : script.regions()) {
            int stream = region.stream().equals("mic") ? 0 : 1;
            if (region.endSample() <= sttDone[stream] || region.endSample() <= horizon) continue;
            String key = region.stream() + "/" + region.seg();
            int revision = partialRevisions.getOrDefault(key, 0);
            if (revision >= PARTIAL_AT.length) continue;
            double progress = (double) (horizon - region.startSample()) / (region.endSample() - region.startSample());
            if (progress < PARTIAL_AT[revision]) continue;
            partialRevisions.put(key, revision + 1);
            ObjectNode body = ControlMessage.nodes().objectNode().put("session_id", sessionId)
                    .put("stream", region.stream()).put("seg", region.seg()).put("rev", revision + 1)
                    .put("start_sample", region.startSample()).putNull("end_sample")
                    .put("text", prefix(region.text(), PARTIAL_AT[revision]));
            connection.send(ControlMessage.event("stt.partial", body));
        }
    }

    private static String prefix(String text, double fraction) {
        String[] words = text.split(" ");
        int count = Math.max(1, (int) Math.round(words.length * fraction));
        return String.join(" ", java.util.Arrays.copyOf(words, Math.min(count, words.length)));
    }

    private long ackedValue(int stream) {
        synchronized (acked) {
            return acked[stream];
        }
    }

    private void sendFinals(long tick) throws IOException {
        long horizon = (tick + 1) * FRAME_SAMPLES;
        List<Region> due = new ArrayList<>();
        for (Region region : script.regions()) {
            int stream = region.stream().equals("mic") ? 0 : 1;
            if (region.endSample() <= horizon && region.endSample() > sttDone[stream]) due.add(region);
        }
        for (Region region : due) {
            connection.send(ControlMessage.event("vad.segment", segmentBody(region, "closed")));
            ObjectNode body = segmentBody(region, "closed");
            body.remove("state");
            body.put("text", region.text()).put("lang", "fr").put("avg_logprob", -0.3).put("model", "mock-script");
            body.putNull("words");
            connection.send(ControlMessage.event("stt.final", body));
            int stream = region.stream().equals("mic") ? 0 : 1;
            sttDone[stream] = Math.max(sttDone[stream], region.endSample());
        }
    }

    private ObjectNode segmentBody(Region region, String state) {
        return ControlMessage.nodes().objectNode().put("session_id", sessionId).put("stream", region.stream())
                .put("seg", region.seg()).put("start_sample", region.startSample())
                .put("end_sample", region.endSample()).put("state", state);
    }
}
