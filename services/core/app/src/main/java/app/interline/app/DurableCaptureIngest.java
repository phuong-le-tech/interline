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

/**
 * Durable ingress of the recording pipeline: audio frames land in chunk files and the contiguous watermark in
 * SQLite, final segments are stored once. A frame is acknowledged only after its bytes were fsynced.
 */
public final class DurableCaptureIngest implements AutoCloseable {
    public static final int WINDOW_FRAMES = 50;
    private static final System.Logger LOG = System.getLogger(DurableCaptureIngest.class.getName());
    private static final String UNKNOWN_MODEL = "unknown";

    public record Ack(int stream, long seq, int window) { }

    private final SqliteDatabase database;
    private final CaptureStore store;
    private final AudioStore audio;
    private final Map<String, long[]> watermarks = new HashMap<>();

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
    public boolean recordFinal(String meetingId, FinalSegment segment) {
        return store.insertFinal(meetingId, segment.stream(), segment.seg(), segment.startSample(),
                segment.endSample(), segment.text(), segment.model(), segment.avgLogprob());
    }

    @Override public void close() {
        database.close();
    }
}
