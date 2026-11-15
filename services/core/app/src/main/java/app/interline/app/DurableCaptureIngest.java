package app.interline.app;

import app.interline.agent.AudioChunk;
import app.interline.domain.AudioFrame;
import app.interline.domain.FinalSegment;
import app.interline.domain.StreamKind;
import app.interline.persistence.AudioStore;
import app.interline.persistence.CaptureStore;
import app.interline.persistence.SqliteDatabase;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Durable ingress of the recording pipeline: audio frames land in chunk files and the contiguous watermark in
 * SQLite, final segments are stored once. A frame is acknowledged only after its bytes were fsynced.
 */
public final class DurableCaptureIngest implements AutoCloseable {
    public static final int WINDOW_FRAMES = 50;
    private static final System.Logger LOG = System.getLogger(DurableCaptureIngest.class.getName());
    private static final String UNKNOWN_MODEL = "unknown";
    private static final long SAMPLES_PER_MS = 16;

    public record Ack(int stream, long seq, int window) { }

    private final SqliteDatabase database;
    private final CaptureStore store;
    private final AudioStore audio;
    private final Map<String, long[]> watermarks = new HashMap<>();
    private volatile BooleanSupplier aecActive = () -> false;

    public DurableCaptureIngest(Path dataDirectory) {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create data directory " + dataDirectory, e);
        }
        this.database = SqliteDatabase.open(dataDirectory.resolve("db.sqlite"));
        this.store = new CaptureStore(database);
        this.audio = new AudioStore(dataDirectory.resolve("audio"));
    }

    public CaptureStore store() { return store; }

    /** Where "is the agent cancelling echo?" comes from: its advertised capabilities, or a configured override. */
    public void useAecSource(BooleanSupplier aecActive) { this.aecActive = aecActive; }

    public void openMeeting(String meetingId) {
        store.createMeeting(meetingId, System.currentTimeMillis());
    }

    /** Persist one frame and return the durable contiguous watermark for its stream. */
    public Ack audio(String meetingId, AudioChunk chunk) {
        StreamKind stream = StreamKind.fromWireIndex(chunk.stream())
                .orElseThrow(() -> new IllegalArgumentException("unknown stream " + chunk.stream()));
        return append(meetingId, List.of(new AudioFrame(stream, chunk.flags(), chunk.seq(), chunk.firstSample(), chunk.pcm())))
                .get(stream.wireIndex());
    }

    /**
     * Persist a batch with one fsync per touched file. Frames at or below the watermark are duplicates and are
     * dropped; a frame that would leave a hole is refused. Returns the watermark of every stream.
     */
    public synchronized List<Ack> append(String meetingId, List<AudioFrame> frames) {
        long[] watermark = watermark(meetingId);
        Set<Path> touched = new HashSet<>();
        boolean[] advanced = new boolean[StreamKind.values().length];
        for (AudioFrame frame : frames) {
            int index = frame.stream().wireIndex();
            if (frame.seq() <= watermark[index]) continue;
            if (frame.seq() != watermark[index] + 1) {
                LOG.log(System.Logger.Level.WARNING, "refusing {0} frame {1}: watermark is {2}",
                        frame.stream().wireName(), frame.seq(), watermark[index]);
                continue;
            }
            audio.write(meetingId, frame.stream(), frame.firstSample(), frame.pcm(), touched);
            watermark[index] = frame.seq();
            advanced[index] = true;
        }
        audio.sync(touched);
        for (StreamKind stream : StreamKind.values()) {
            if (advanced[stream.wireIndex()]) store.updateLastSeq(meetingId, stream, watermark[stream.wireIndex()]);
        }
        List<Ack> acks = new ArrayList<>();
        for (StreamKind stream : StreamKind.values()) {
            acks.add(new Ack(stream.wireIndex(), watermark[stream.wireIndex()], WINDOW_FRAMES));
        }
        return acks;
    }

    /** Copy frames that were spooled but never received into their place on the timeline; no watermark moves. */
    public synchronized void importRecoveredAudio(String meetingId, StreamKind stream, long firstSample, byte[] pcm) {
        Set<Path> touched = new HashSet<>();
        audio.write(meetingId, stream, firstSample, pcm, touched);
        audio.sync(touched);
    }

    /** Highest durable seq of the stream, or -1. */
    public synchronized long watermark(String meetingId, StreamKind stream) {
        return watermark(meetingId)[stream.wireIndex()];
    }

    private long[] watermark(String meetingId) {
        return watermarks.computeIfAbsent(meetingId, id -> {
            long[] loaded = new long[StreamKind.values().length];
            for (StreamKind stream : StreamKind.values()) loaded[stream.wireIndex()] = store.lastSeq(id, stream);
            return loaded;
        });
    }

    public void finalSegment(String meetingId, String stream, String seg, long startSample, long endSample, String text) {
        StreamKind kind = StreamKind.fromWireName(stream)
                .orElseThrow(() -> new IllegalArgumentException("unknown stream " + stream));
        recordFinal(meetingId, new FinalSegment(kind, seg, startSample, endSample, text, UNKNOWN_MODEL, null));
    }

    /** Store once per (meeting, stream, seg). Returns false when this delivery was a duplicate. */
    public synchronized boolean recordFinal(String meetingId, FinalSegment segment) {
        return recordFinal(meetingId, segment, false);
    }

    /**
     * A mic final that repeats a system final of the same moment is kept but flagged {@code suppressed}; the check
     * runs in both delivery orders so the result does not depend on which STT result arrives first.
     */
    public synchronized boolean recordFinal(String meetingId, FinalSegment segment, boolean recovered) {
        boolean stored = store.insertFinal(meetingId, segment.stream(), segment.seg(), segment.startSample(),
                segment.endSample(), segment.text(), segment.model(), segment.avgLogprob(), recovered);
        if (stored) suppressEcho(meetingId, segment);
        return stored;
    }

    private void suppressEcho(String meetingId, FinalSegment segment) {
        long startMs = segment.startSample() / SAMPLES_PER_MS;
        boolean aec = aecActive.getAsBoolean();
        if (segment.stream() == StreamKind.MIC) {
            boolean echo = store.segmentsNear(meetingId, StreamKind.SYSTEM, startMs, EchoSuppression.WINDOW_MS).stream()
                    .anyMatch(other -> EchoSuppression.isEcho(segment.text(), other.text(), aec));
            if (echo) store.markSuppressed(meetingId, StreamKind.MIC, segment.seg());
        } else {
            store.segmentsNear(meetingId, StreamKind.MIC, startMs, EchoSuppression.WINDOW_MS).stream()
                    .filter(mic -> !mic.suppressed() && EchoSuppression.isEcho(mic.text(), segment.text(), aec))
                    .forEach(mic -> store.markSuppressed(meetingId, StreamKind.MIC, mic.seg()));
        }
    }

    @Override public void close() {
        database.close();
    }
}
