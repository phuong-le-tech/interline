package app.interline.mockagent;

import app.interline.agent.AgentConnection;
import app.interline.agent.AudioChunk;
import app.interline.agent.ControlMessage;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

/**
 * Plays the fixture on the session timeline at {@code speed}: every 100 ms tick sends one frame per stream and
 * then the scripted finals that end inside it. Honors the ack window and can resume after a crash.
 */
final class CaptureRun implements Runnable {
    static final int FRAME_SAMPLES = 1600;
    static final int WINDOW = 50;
    static final int CRASH_EXIT_CODE = 13;
    private static final String[] STREAMS = {"mic", "system"};
    private static final long TICK_NANOS = 100_000_000L;
    private static final long WINDOW_POLL_NANOS = 1_000_000L;
    private static final int RECOVERED_FLAG = 1;

    private final AgentConnection connection;
    private final MockOptions options;
    private final Script script;
    private final byte[][] pcm;
    private final String sessionId;
    private final long[] nextSeq = new long[2];
    private final long[] sttDone = new long[2];
    private final boolean resumed;
    private final long[] lastSent = {-1, -1};
    private final long[] acked = new long[2];
    private volatile boolean stopping;

    CaptureRun(AgentConnection connection, MockOptions options, Script script, byte[][] pcm,
               String sessionId, ResumeRequest resume) {
        this.connection = connection;
        this.options = options;
        this.script = script;
        this.pcm = pcm;
        this.sessionId = sessionId;
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
            sendFinals(tick);
            LockSupport.parkNanos(begin + (long) ((tick - startTick + 1) * TICK_NANOS / options.speed()) - System.nanoTime());
        }
    }

    private void sendFrame(int stream, long seq) throws IOException {
        if (seq < nextSeq[stream]) return;
        while (!stopping && seq - ackedValue(stream) > WINDOW) LockSupport.parkNanos(WINDOW_POLL_NANOS);
        if (stopping) return;
        int from = (int) (seq * FRAME_SAMPLES * 2);
        byte[] chunk = java.util.Arrays.copyOfRange(pcm[stream], from, from + FRAME_SAMPLES * 2);
        boolean replayed = resumed && seq < nextSeq[stream] + WINDOW;
        connection.send(new AudioChunk(stream, replayed ? RECOVERED_FLAG : 0, seq, seq * FRAME_SAMPLES,
                System.nanoTime(), chunk));
        lastSent[stream] = seq;
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
