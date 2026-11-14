package app.interline.app;

import app.interline.agent.AudioChunk;
import java.nio.file.Path;

/**
 * Planned durable ingress used by the recording pipeline. It owns real persistence, not test results.
 * Audio acknowledgements are returned only after bytes and the contiguous watermark are durable.
 * Final segments are deduplicated atomically on meeting, stream and segment identity.
 */
public final class DurableCaptureIngest implements AutoCloseable {
    public record Ack(int stream, long seq, int window) { }
    public DurableCaptureIngest(Path dataDirectory) {
        throw new UnsupportedOperationException("Durable capture ingress not implemented");
    }
    public void openMeeting(String meetingId) {
        throw new UnsupportedOperationException("Meeting capture persistence not implemented");
    }
    public Ack audio(String meetingId, AudioChunk chunk) {
        throw new UnsupportedOperationException("Durable audio append and dedup not implemented");
    }
    public void finalSegment(String meetingId, String stream, String seg,
                             long startSample, long endSample, String text) {
        throw new UnsupportedOperationException("Atomic final segment persistence not implemented");
    }
    @Override public void close() {
        throw new UnsupportedOperationException("Durable capture close not implemented");
    }
}
